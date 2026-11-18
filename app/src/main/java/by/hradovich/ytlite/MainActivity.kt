package by.hradovich.ytlite

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity() {

    private enum class Tab(val browseId: String?, val needsLogin: Boolean) {
        HOME("FEwhat_to_watch", false),
        SUBS("FEsubscriptions", true),
        HISTORY("FEhistory", true),
        SEARCH(null, false),
    }

    /** Состояние вкладки живёт, пока открыто приложение: список, позиция, догрузка. */
    private inner class TabState {
        val adapter = VideoAdapter { openVideo(it) }
        var continuation: String? = null
        var scroll: Parcelable? = null
        var loaded = false
        var loading = false
        var message: String? = null
        var generation = 0
    }

    private lateinit var list: RecyclerView
    private lateinit var layout: LinearLayoutManager
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var query: EditText
    private lateinit var accountIcon: ImageView
    private lateinit var tabViews: Map<Tab, View>

    private var states = Tab.entries.associateWith { TabState() }
    private var tab = Tab.HOME
    private var shownAccount: String? = null

    // мини-плеер
    private lateinit var mini: View
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniTitle: TextView
    private lateinit var miniChannel: TextView
    private lateinit var miniThumb: ImageView
    private lateinit var miniPlay: ImageButton
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateMiniProgress()
            if (controller?.isPlaying == true) ui.postDelayed(this, 1000)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateMini()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        refresh = findViewById(R.id.refresh)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        query = findViewById(R.id.query)
        accountIcon = findViewById(R.id.account)
        mini = findViewById(R.id.mini)
        miniProgress = findViewById(R.id.miniProgress)
        miniTitle = findViewById(R.id.miniTitle)
        miniChannel = findViewById(R.id.miniChannel)
        miniThumb = findViewById(R.id.miniThumb)
        miniPlay = findViewById(R.id.miniPlay)

        layout = LinearLayoutManager(this)
        list.layoutManager = layout
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= rv.adapter!!.itemCount - 5) loadMore()
            }
        })

        refresh.setColorSchemeColors(0xFFFF0033.toInt())
        refresh.setProgressBackgroundColorSchemeColor(0xFF272727.toInt())
        refresh.setOnRefreshListener {
            if (tab == Tab.SEARCH && query.text.isBlank()) refresh.isRefreshing = false else load(tab, pull = true)
        }

        tabViews = mapOf(
            Tab.HOME to findViewById(R.id.tabHome),
            Tab.SUBS to findViewById(R.id.tabSubs),
            Tab.HISTORY to findViewById(R.id.tabHistory),
            Tab.SEARCH to findViewById(R.id.tabSearch),
        )
        tabViews.forEach { (t, v) -> v.setOnClickListener { select(t) } }
        accountIcon.setOnClickListener { showAccountMenu() }

        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
                load(Tab.SEARCH)
                true
            } else false
        }

        mini.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        miniPlay.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        miniClose.setOnClickListener {
            controller?.run { stop(); clearMediaItems() }
            mini.visibility = View.GONE
        }

        shownAccount = Auth.active?.id
        select(Tab.HOME)
    }

    private val miniClose: View get() = findViewById(R.id.miniClose)

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            updateMini()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        controller?.removeListener(playerListener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateAccountIcon()
        if (Auth.active?.id != shownAccount) accountChanged()
        // Имя и аватар нового аккаунта
        val acc = Auth.active
        if (acc != null && acc.name.isEmpty()) {
            Net.io.execute {
                val info = runCatching { InnerTube.accountInfo() }.getOrNull() ?: return@execute
                Auth.setInfo(acc, info.first, info.second)
                runOnUiThread { updateAccountIcon() }
            }
        }
    }

    // ---------- вкладки и ленты ----------

    private fun openVideo(v: Video) {
        Streams.prefetch(v.id) // потоки начинают готовиться ещё до открытия плеера
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, v.id)
                .putExtra(PlayerActivity.EXTRA_TITLE, v.title)
        )
    }

    private fun select(t: Tab) {
        if (t == tab && list.adapter != null) {
            list.smoothScrollToPosition(0)
            return
        }
        states[tab]?.scroll = layout.onSaveInstanceState()
        tab = t
        val st = states.getValue(t)
        tabViews.forEach { (k, v) -> v.alpha = if (k == t) 1f else 0.5f }
        list.adapter = st.adapter
        st.scroll?.let { layout.onRestoreInstanceState(it) }
        query.visibility = if (t == Tab.SEARCH) View.VISIBLE else View.GONE

        if (t == Tab.SEARCH && query.text.isBlank()) {
            st.message = "Введите запрос"
            query.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(query, 0)
        } else if (!st.loaded && !st.loading) {
            load(t)
            return
        }
        render()
    }

    private fun render() {
        val st = states.getValue(tab)
        progress.visibility = if (st.loading && st.adapter.isEmpty && !refresh.isRefreshing) View.VISIBLE else View.GONE
        if (!st.loading) refresh.isRefreshing = false
        message.text = st.message
        message.visibility = if (st.message != null && st.adapter.isEmpty && !st.loading) View.VISIBLE else View.GONE
    }

    private fun load(t: Tab, pull: Boolean = false) {
        val st = states.getValue(t)
        val q = query.text.toString().trim()
        val g = ++st.generation
        st.continuation = null
        st.message = null
        if (t.needsLogin && !Auth.loggedIn) {
            st.adapter.submit(emptyList())
            st.loaded = true
            st.loading = false
            st.message = "Войдите в аккаунт"
            if (t == tab) render()
            return
        }
        if (!pull) st.adapter.submit(emptyList())
        st.loading = true
        if (t == tab) render()
        Net.io.execute {
            val r = runCatching { if (t == Tab.SEARCH) InnerTube.search(q) else InnerTube.browse(t.browseId!!) }
            runOnUiThread {
                if (g != st.generation) return@runOnUiThread
                st.loading = false
                st.loaded = true
                r.onSuccess {
                    st.adapter.submit(it.items)
                    st.continuation = it.continuation
                    if (it.items.isEmpty()) st.message = "Здесь пусто"
                    if (t == tab) list.scrollToPosition(0)
                }.onFailure { st.message = "Ошибка: ${it.message}" }
                if (t == tab) render()
            }
        }
    }

    private fun loadMore() {
        val t = tab
        val st = states.getValue(t)
        if (st.loading) return
        val token = st.continuation ?: return
        st.loading = true
        val g = st.generation
        val endpoint = if (t == Tab.SEARCH) "search" else "browse"
        Net.io.execute {
            val r = runCatching { InnerTube.more(endpoint, token) }
            runOnUiThread {
                if (g != st.generation) return@runOnUiThread
                st.loading = false
                r.onSuccess {
                    st.adapter.append(it.items)
                    st.continuation = it.continuation
                }.onFailure { st.continuation = null }
            }
        }
    }

    // ---------- аккаунты ----------

    private fun updateAccountIcon() {
        val acc = Auth.active
        if (acc?.avatar != null) {
            accountIcon.setPadding(dp(10))
            accountIcon.loadAvatar(acc.avatar)
        } else {
            accountIcon.setPadding(dp(10))
            accountIcon.setImageResource(R.drawable.ic_account)
        }
    }

    private fun ImageView.setPadding(p: Int) = setPadding(p, p, p, p)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun accountChanged() {
        shownAccount = Auth.active?.id
        states = Tab.entries.associateWith { TabState() }
        val t = tab
        tab = Tab.HOME
        list.adapter = null
        select(t)
    }

    private fun showAccountMenu() {
        val menu = PopupMenu(this, accountIcon)
        val accounts = Auth.accounts
        val active = Auth.active?.id
        accounts.forEachIndexed { i, a ->
            val title = (if (a.id == active) "✓ " else "    ") + a.name.ifEmpty { "Аккаунт ${i + 1}" }
            menu.menu.add(0, i, i, title)
        }
        menu.menu.add(1, ADD, 100, "Добавить аккаунт")
        if (active != null) menu.menu.add(1, LOGOUT, 101, "Выйти")
        menu.menu.add(2, QUALITY, 102, "Качество по умолчанию: ${qualityName(Prefs.quality)}")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ADD -> startActivity(Intent(this, LoginActivity::class.java))
                LOGOUT -> AlertDialog.Builder(this)
                    .setMessage("Выйти из аккаунта ${Auth.active?.name.orEmpty()}?")
                    .setPositiveButton("Выйти") { _, _ ->
                        Auth.logout()
                        updateAccountIcon()
                        accountChanged()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                QUALITY -> chooseQuality()
                else -> accounts.getOrNull(item.itemId)?.let {
                    if (it.id != active) {
                        Auth.switchTo(it.id)
                        updateAccountIcon()
                        accountChanged()
                    }
                }
            }
            true
        }
        menu.show()
    }

    private fun chooseQuality() {
        val values = intArrayOf(2160, 1440, 1080, 720, 480, 360, 0)
        val names = values.map { qualityName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Качество по умолчанию")
            .setSingleChoiceItems(names, values.indexOf(Prefs.quality)) { d, i ->
                Prefs.quality = values[i]
                d.dismiss()
            }
            .show()
    }

    private fun qualityName(q: Int) = if (q == 0) "максимальное" else "${q}p"

    // ---------- мини-плеер ----------

    private fun updateMini() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null || c.playbackState == Player.STATE_IDLE) {
            mini.visibility = View.GONE
            ui.removeCallbacks(tick)
            return
        }
        mini.visibility = View.VISIBLE
        val md = c.mediaMetadata
        miniTitle.text = md.title ?: item.mediaMetadata.title
        miniChannel.text = md.artist ?: ""
        if (miniThumb.tag != item.mediaId) {
            miniThumb.tag = item.mediaId
            miniThumb.load("https://i.ytimg.com/vi/${item.mediaId}/mqdefault.jpg") { crossfade(false) }
        }
        miniPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateMiniProgress()
        ui.removeCallbacks(tick)
        if (c.isPlaying) ui.postDelayed(tick, 1000)
    }

    private fun updateMiniProgress() {
        val c = controller ?: return
        val d = c.duration
        miniProgress.progress = if (d > 0) (c.currentPosition * 1000 / d).toInt() else 0
    }

    companion object {
        private const val ADD = Menu.FIRST + 1000
        private const val LOGOUT = Menu.FIRST + 1001
        private const val QUALITY = Menu.FIRST + 1002
    }
}
