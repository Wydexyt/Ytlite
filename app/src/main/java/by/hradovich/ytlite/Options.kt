package by.hradovich.ytlite

import android.app.Activity
import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Меню из пунктов и переключателей (шестерёнка плеера, «Звук»). */
object Options {
    sealed class Row
    class Action(val text: String, val onClick: () -> Unit) : Row()
    class Toggle(val text: String, val checked: Boolean, val hint: String? = null, val onChange: (Boolean) -> Unit) : Row()

    fun show(act: Activity, title: String?, rows: List<Row>): AlertDialog {
        val d = act.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        val bg = TypedValue().also { act.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        lateinit var dialog: AlertDialog
        for (row in rows) {
            val v: View = when (row) {
                is Action -> TextView(act).apply {
                    text = row.text
                    setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = (52 * d).toInt()
                    setPadding(pad, 0, pad, 0)
                    setBackgroundResource(bg)
                    setOnClickListener {
                        dialog.dismiss()
                        row.onClick()
                    }
                }
                is Toggle -> Switch(act).apply {
                    text = row.text
                    setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                    isChecked = row.checked
                    minHeight = (52 * d).toInt()
                    setPadding(pad, 0, pad, 0)
                    setBackgroundResource(bg)
                    setOnCheckedChangeListener { _, on ->
                        row.onChange(on)
                        if (on && row.hint != null) Toast.makeText(act, row.hint, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            box.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(act)
            .apply { if (title != null) setTitle(title) }
            .setView(ScrollView(act).apply { addView(box) })
            .show()
        return dialog
    }
}

/** Очередь: включить, поднять выше, убрать; очистить всю. */
fun showQueue(act: Activity) {
    val items = Queue.items
    if (items.isEmpty()) {
        AlertDialog.Builder(act).setTitle(R.string.queue).setMessage(R.string.queue_empty).setPositiveButton("OK", null).show()
        return
    }
    val names = items.mapIndexed { i, q -> "${i + 1}. ${q.title}" + if (q.channel.isNotEmpty()) " — ${q.channel}" else "" }.toTypedArray()
    AlertDialog.Builder(act)
        .setTitle(R.string.queue)
        .setItems(names) { _, i ->
            val q = items.getOrNull(i) ?: return@setItems
            AlertDialog.Builder(act)
                .setTitle(q.title)
                .setItems(arrayOf(act.getString(R.string.play_now), act.getString(R.string.move_up), act.getString(R.string.remove))) { _, w ->
                    when (w) {
                        0 -> {
                            Queue.removeAt(i)
                            PlaybackService.instance?.play(q)
                        }
                        1 -> { Queue.move(i, maxOf(0, i - 1)); showQueue(act) }
                        2 -> { Queue.removeAt(i); showQueue(act) }
                    }
                }
                .show()
        }
        .setNeutralButton(R.string.clear) { _, _ -> Queue.clear() }
        .setNegativeButton(R.string.close, null)
        .show()
}
