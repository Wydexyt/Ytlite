package by.hradovich.ytlite

import android.annotation.SuppressLint
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

class VideoAdapter(private val onClick: (Video) -> Unit) : RecyclerView.Adapter<VideoAdapter.VH>() {
    private val items = ArrayList<Video>()
    private val ids = HashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        h.title.text = v.title
        h.meta.text = listOf(v.channel, v.meta).filter { it.isNotBlank() }.joinToString(" · ")
        h.duration.text = v.duration
        h.duration.visibility = if (v.duration.isEmpty()) View.GONE else View.VISIBLE
        h.thumb.load(v.thumb) { crossfade(false) }
        h.avatar.loadAvatar(v.avatar)
        h.itemView.setOnClickListener { onClick(v) }
    }
}

/**
 * Жесты поверх видео, как в YouTube: одиночный тап — показать/скрыть управление,
 * двойной тап слева/справа — перемотка на 5 с, каждый следующий тап — ещё 5 с.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TapOverlay(ctx: Context, private val onSingleTap: () -> Unit) : FrameLayout(ctx) {
    var player: Player? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val left = label(Gravity.START or Gravity.CENTER_VERTICAL)
    private val right = label(Gravity.END or Gravity.CENTER_VERTICAL)

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
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

    private fun label(gravity: Int) = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
        alpha = 0f
        val pad = (resources.displayMetrics.density * 32).toInt()
        setPadding(pad, 0, pad, 0)
        this@TapOverlay.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = e.eventTime
            }
            MotionEvent.ACTION_UP -> {
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
