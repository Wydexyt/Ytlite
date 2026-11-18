package by.hradovich.ytlite

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.widget.ProgressBar
import android.widget.Toast
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlin.math.abs

/** Превью 16:9 по ширине родителя. */
class Thumb @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 9 / 16)
    }
}

fun ImageView.loadAvatar(url: String?) = load(url) {
    crossfade(false)
    transformations(CircleCropTransformation())
}

/** Состояние подписок, изменённое в этой сессии (поверх данных из лент). */
object Subs {
    val state = HashMap<String, Boolean>()
}

/** Кнопка «Подписаться / Вы подписаны». */
fun TextView.bindSubscribe(channelId: String?, subscribed: Boolean) {
    if (channelId == null || !Auth.loggedIn) {
        visibility = View.GONE
        return
    }
    visibility = View.VISIBLE
    fun render(s: Boolean) {
        text = if (s) "Вы подписаны" else "Подписаться"
        setBackgroundResource(if (s) R.drawable.chip else R.drawable.chip_selected)
        setTextColor(if (s) 0xFFF1F1F1.toInt() else 0xFF0F0F0F.toInt())
    }
    render(Subs.state[channelId] ?: subscribed)
    setOnClickListener {
        val target = !(Subs.state[channelId] ?: subscribed)
        val go = {
            Subs.state[channelId] = target
            render(target)
            isEnabled = false
            Net.io.execute {
                val ok = runCatching { InnerTube.subscribe(channelId, target) }.isSuccess
                post {
                    isEnabled = true
                    if (!ok) {
                        Subs.state[channelId] = !target
                        render(!target)
                        Toast.makeText(context, "Не получилось, попробуйте ещё раз", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        if (target) go() else AlertDialog.Builder(context)
            .setMessage("Отписаться от канала?")
            .setPositiveButton("Отписаться") { _, _ -> go() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

class VideoAdapter(private val onClick: (Video) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = ArrayList<Video>()
    private val ids = HashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val watched: ProgressBar = v.findViewById(R.id.watched)
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    class ChannelVH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.chAvatar)
        val title: TextView = v.findViewById(R.id.chTitle)
        val meta: TextView = v.findViewById(R.id.chMeta)
        val subscribe: TextView = v.findViewById(R.id.chSubscribe)
    }

    val isEmpty get() = items.isEmpty()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Video>) {
        items.clear()
        ids.clear()
        list.filterTo(items) { ids.add(it.id) }
        notifyDataSetChanged()
    }

    fun append(list: List<Video>) {
        val start = items.size
        list.filterTo(items) { ids.add(it.id) }
        notifyItemRangeInserted(start, items.size - start)
    }

    /** Обновить полоски «просмотрено» после возврата из плеера. */
    @SuppressLint("NotifyDataSetChanged")
    fun refreshProgress() = notifyDataSetChanged()

    override fun getItemViewType(position: Int) = if (items[position].isChannel) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) ChannelVH(inf.inflate(R.layout.item_channel, parent, false))
        else VH(inf.inflate(R.layout.item_video, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = items[position]
        if (holder is ChannelVH) {
            holder.title.text = v.title
            holder.meta.text = v.meta
            holder.avatar.loadAvatar(v.avatar)
            holder.subscribe.bindSubscribe(v.id, v.subscribed)
            holder.itemView.setOnClickListener { ChannelActivity.open(it.context, v.id) }
            return
        }
        val h = holder as VH
        h.title.text = v.title
        h.meta.text = listOf(v.channel, v.meta).filter { it.isNotBlank() }.joinToString(" · ")
        h.duration.text = v.duration
        h.duration.visibility = if (v.duration.isEmpty()) View.GONE else View.VISIBLE
        val pct = Prefs.watchedPercent(v.id).takeIf { it > 0 } ?: v.watched
        h.watched.progress = pct
        h.watched.visibility = if (pct > 0) View.VISIBLE else View.GONE
        h.thumb.load(v.thumb) { crossfade(false) }
        h.avatar.loadAvatar(v.avatar)
        h.avatar.visibility = if (v.avatar == null && v.channel.isEmpty()) View.GONE else View.VISIBLE
        val ch = v.channelId
        h.avatar.setOnClickListener(if (ch != null) View.OnClickListener { ChannelActivity.open(it.context, ch) } else null)
        h.itemView.setOnClickListener { onClick(v) }
    }
}

/** Обработчик вертикального перетаскивания по видео. */
interface DragListener {
    /** startXFraction: где по горизонтали начался жест (0..1). */
    fun onDragStart(startXFraction: Float)
    fun onDrag(dy: Float)
    fun onDragEnd(dy: Float)
}

/**
 * Жесты поверх видео, как в YouTube: одиночный тап — показать/скрыть управление,
 * двойной тап слева/справа — перемотка на 5 с, каждый следующий тап — ещё 5 с,
 * вертикальный свайп — DragListener (свернуть плеер / яркость / громкость).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TapOverlay(ctx: Context, private val onSingleTap: () -> Unit) : FrameLayout(ctx) {
    var player: Player? = null
    var drag: DragListener? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val left = label(Gravity.START or Gravity.CENTER_VERTICAL)
    private val right = label(Gravity.END or Gravity.CENTER_VERTICAL)
    private val info = label(Gravity.CENTER).apply {
        setBackgroundColor(0x99000000.toInt())
        val p = (resources.displayMetrics.density * 12).toInt()
        setPadding(p, p / 2, p, p / 2)
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var lastTapTime = 0L
    private var lastSide = 0
    private var seekUntil = 0L
    private var seekSide = 0
    private var seconds = 0

    private val singleTap = Runnable { onSingleTap() }
    private val hideLabels = Runnable {
        left.animate().alpha(0f).setDuration(200)
        right.animate().alpha(0f).setDuration(200)
    }
    private val hideInfo = Runnable { info.animate().alpha(0f).setDuration(200) }

    private fun label(gravity: Int) = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
        alpha = 0f
        val pad = (resources.displayMetrics.density * 32).toInt()
        setPadding(pad, 0, pad, 0)
        this@TapOverlay.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
    }

    /** Подсказка по центру (яркость, громкость). */
    fun showInfo(text: String) {
        info.text = text
        info.animate().cancel()
        info.alpha = 1f
        removeCallbacks(hideInfo)
        postDelayed(hideInfo, 700)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = e.eventTime
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && drag != null && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f) {
                    dragging = true
                    removeCallbacks(singleTap)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    drag?.onDragStart(if (width > 0) downX / width else 0.5f)
                }
                if (dragging) drag?.onDrag(dy)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                drag?.onDragEnd(0f)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    drag?.onDragEnd(e.y - downY)
                    return true
                }
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop || e.eventTime - downTime > 400) return true
                val side = when {
                    e.x < width * 0.4f -> -1
                    e.x > width * 0.6f -> 1
                    else -> 0
                }
                val now = e.eventTime
                // Режим перемотки: каждый тап на той же стороне добавляет 5 с
                if (side != 0 && now < seekUntil && side == seekSide) {
                    seek(side)
                    return true
                }
                if (side != 0 && now - lastTapTime < DOUBLE_TAP && side == lastSide) {
                    removeCallbacks(singleTap)
                    seekSide = side
                    seconds = 0
                    lastTapTime = 0
                    seek(side)
                    return true
                }
                lastTapTime = now
                lastSide = side
                removeCallbacks(singleTap)
                postDelayed(singleTap, DOUBLE_TAP)
            }
        }
        return true
    }

    private fun seek(side: Int) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + side * 5_000L).coerceIn(0, dur))
        seconds += 5
        seekUntil = SystemClock.uptimeMillis() + 800
        val l = if (side < 0) left else right
        (if (side < 0) right else left).alpha = 0f
        l.text = if (side < 0) "◀◀  $seconds с" else "$seconds с  ▶▶"
        l.animate().cancel()
        l.alpha = 1f
        removeCallbacks(hideLabels)
        postDelayed(hideLabels, 800)
    }

    companion object {
        private const val DOUBLE_TAP = 280L
    }
}
