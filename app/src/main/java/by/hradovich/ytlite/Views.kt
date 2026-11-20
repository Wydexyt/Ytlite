package by.hradovich.ytlite

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.widget.PopupMenu
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
        text = context.getString(if (s) R.string.subscribed else R.string.subscribe)
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
                val ok = runCatching { InnerTube.subscribe(channelId, target) }
                    .onFailure { ErrorLog.log("Подписка $channelId", it) }.isSuccess
                post {
                    isEnabled = true
                    if (!ok) {
                        Subs.state[channelId] = !target
                        render(!target)
                        Toast.makeText(context, R.string.try_again, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        if (target) go() else AlertDialog.Builder(context)
            .setMessage(R.string.unsubscribe_confirm)
            .setPositiveButton(R.string.unsubscribe) { _, _ -> go() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

class VideoAdapter(
    private val onClick: (Video) -> Unit,
    /** Пункт «Убрать из плейлиста» (на странице своего плейлиста). */
    private val onRemove: ((Video) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** Можно ли убирать видео (только в своих плейлистах). */
    var removable = true
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

    /** Пока лента грузится — серые карточки-заглушки. */
    private var skeleton = false

    @SuppressLint("NotifyDataSetChanged")
    fun showSkeleton() {
        skeleton = true
        items.clear()
        ids.clear()
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Video>) {
        skeleton = false
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

    override fun getItemViewType(position: Int) = when {
        skeleton -> 2
        items[position].isChannel -> 1
        else -> 0
    }

    class SkeletonVH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> ChannelVH(inf.inflate(R.layout.item_channel, parent, false))
            2 -> SkeletonVH(inf.inflate(R.layout.item_skeleton, parent, false))
            else -> VH(inf.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun getItemCount() = if (skeleton) 5 else items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SkeletonVH) holder.itemView.animate().cancel()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SkeletonVH) {
            pulse(holder.itemView)
            return
        }
        val v = items[position]
        if (holder is ChannelVH) {
            holder.title.text = v.title
            holder.meta.text = v.meta
            holder.avatar.loadAvatar(v.avatar)
            holder.subscribe.bindSubscribe(v.id, v.subscribed)
            holder.itemView.setOnClickListener { it.context.openChannel(v.id) }
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
        h.avatar.setOnClickListener(if (ch != null) View.OnClickListener { it.context.openChannel(ch) } else null)
        if (v.isPlaylist) {
            h.meta.text = v.channel
            h.avatar.visibility = View.GONE
            h.itemView.setOnClickListener { it.context.openPlaylist(v.id) }
            h.itemView.setOnLongClickListener(null)
            return
        }
        h.itemView.setOnClickListener { onClick(v) }
        h.itemView.setOnLongClickListener { showMenu(it, v); true }
    }

    /** Меню долгого тапа: очередь, «Не интересует», «Поделиться», «Открыть канал». */
    private fun showMenu(anchor: View, v: Video) {
        val ctx = anchor.context
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 4, 0, R.string.play_next)
        menu.menu.add(0, 5, 0, R.string.add_to_queue)
        if (Auth.loggedIn) {
            menu.menu.add(0, 6, 0, R.string.watch_later_add)
            menu.menu.add(0, 7, 0, R.string.save_to_playlist)
        }
        if (onRemove != null && removable) menu.menu.add(0, 8, 0, R.string.remove_from_playlist)
        if (v.notInterestedToken != null && Auth.loggedIn) menu.menu.add(0, 1, 0, R.string.not_interested)
        menu.menu.add(0, 2, 1, R.string.share)
        if (v.channelId != null) menu.menu.add(0, 3, 2, R.string.open_channel)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    remove(v.id)
                    Toast.makeText(ctx, R.string.video_hidden, Toast.LENGTH_SHORT).show()
                    val token = v.notInterestedToken!!
                    Net.io.execute {
                        runCatching { InnerTube.notInterested(token) }.onFailure { ErrorLog.log("Не интересует", it) }
                    }
                }
                2 -> Share.video(ctx, v.id, v.title)
                6 -> Library.addToWatchLater(ctx, v.id)
                7 -> (ctx as? android.app.Activity)?.let { Library.saveToPlaylist(it, v.id) }
                8 -> {
                    remove(v.id)
                    onRemove?.invoke(v)
                }
                4, 5 -> {
                    val svc = PlaybackService.instance
                    if (svc != null) svc.enqueue(QItem.of(v), next = item.itemId == 4)
                    else if (item.itemId == 4) Queue.addNext(QItem.of(v)) else Queue.addLast(QItem.of(v))
                    Toast.makeText(ctx, if (item.itemId == 4) R.string.queued_next else R.string.queued, Toast.LENGTH_SHORT).show()
                }
                3 -> v.channelId?.let { ctx.openChannel(it) }
            }
            true
        }
        menu.show()
    }

    /** Мягкая пульсация заглушки, пока она на экране. */
    private fun pulse(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        fun down(): Unit = run { v.animate().alpha(0.45f).setDuration(650).withEndAction { if (skeleton && v.isAttachedToWindow) v.animate().alpha(1f).setDuration(650).withEndAction { if (skeleton && v.isAttachedToWindow) down() } } }
        down()
    }

    private fun remove(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items.removeAt(i)
        notifyItemRemoved(i)
    }
}

object Share {
    /** Поделиться ссылкой на видео; startMs > 0 — с текущего момента. */
    fun video(ctx: Context, id: String, title: String?, startMs: Long = 0) {
        val sec = startMs / 1000
        val url = "https://youtu.be/$id" + if (sec > 0) "?t=$sec" else ""
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
            title?.let { putExtra(android.content.Intent.EXTRA_SUBJECT, it) }
        }
        ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.share)))
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
    /** Щипок: true — развести пальцы (заполнить экран), false — свести. */
    var onPinch: ((Boolean) -> Unit)? = null

    private var multiTouch = false
    private var pinchScale = 1f
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
            pinchScale = 1f
            return true
        }

        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            pinchScale *= d.scaleFactor
            return true
        }

        override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
            if (pinchScale > 1.15f) onPinch?.invoke(true) else if (pinchScale < 0.87f) onPinch?.invoke(false)
        }
    })

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
        scaler.onTouchEvent(e)
        // Два пальца — это щипок: тапы и свайпы до конца жеста не обрабатываем
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true
            removeCallbacks(singleTap)
            if (dragging) {
                dragging = false
                drag?.onDragEnd(0f)
            }
        }
        if (multiTouch) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multiTouch = false
            return true
        }
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
