package by.hradovich.ytlite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Уведомления аккаунта YouTube: новые видео подписок, ответы на комментарии. */
class NotificationsPage(private val act: MainActivity, parent: ViewGroup) : AppPage {
    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_channel, parent, false)
    private val list: RecyclerView = view.findViewById(R.id.list)
    private val progress: ProgressBar = view.findViewById(R.id.progress)
    private val message: TextView = view.findViewById(R.id.message)
    private var items: List<InnerTube.Notification> = emptyList()

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val n = items[position]
            val v = h.itemView
            v.findViewById<View>(R.id.nUnread).visibility = if (n.read) View.INVISIBLE else View.VISIBLE
            v.findViewById<ImageView>(R.id.nAvatar).loadAvatar(n.avatar)
            v.findViewById<TextView>(R.id.nText).text = n.text
            v.findViewById<TextView>(R.id.nTime).text = n.time
            v.findViewById<ImageView>(R.id.nThumb).apply {
                visibility = if (n.thumb == null) View.GONE else View.VISIBLE
                load(n.thumb) { crossfade(false) }
            }
            v.setOnClickListener {
                when {
                    n.videoId != null && n.isComment -> act.openVideoComments(n.videoId, n.params)
                    n.videoId != null -> act.openVideo(n.videoId, null)
                    n.channelId != null -> act.openChannel(n.channelId)
                    else -> android.widget.Toast.makeText(act, R.string.link_unsupported, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    init {
        view.findViewById<View>(R.id.back).setOnClickListener { act.closePage(this) }
        view.findViewById<TextView>(R.id.topTitle).setText(R.string.notifications)
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        Net.io.execute {
            val r = runCatching { InnerTube.notifications() }.onFailure { ErrorLog.log("Уведомления", it) }
            act.runOnUiThread {
                progress.visibility = View.GONE
                r.onSuccess {
                    items = it
                    adapter.notifyItemRangeInserted(0, it.size)
                    if (it.isEmpty()) {
                        message.setText(R.string.no_notifications)
                        message.visibility = View.VISIBLE
                    }
                }.onFailure {
                    message.text = act.errorText(R.string.channel_error, it)
                    message.visibility = View.VISIBLE
                }
            }
        }
    }
}
