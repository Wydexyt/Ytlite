package by.hradovich.ytlite

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity() {

    private class TabDef(val browseId: String, val needsLogin: Boolean, val navId: Int)

    private val tabs = listOf(
        TabDef("FEwhat_to_watch", false, R.id.navHome),
        TabDef("FEsubscriptions", true, R.id.navSubs),
        TabDef("FEhistory", true, R.id.navHistory),
    )

    private lateinit var pager: ViewPager2
    private lateinit var feeds: List<Feed>
    private lateinit var navItems: List<View>
    private lateinit var accountIcon: ImageView
    private var shownAccount: String? = null

    // поиск
    private lateinit var header: View
    private lateinit var searchBar: View
    private lateinit var searchContainer: FrameLayout
    private lateinit var suggestions: RecyclerView
    private lateinit var query: EditText
    private lateinit var searchFeed: Feed
    private var searchQuery = ""
    private var suggestGen = 0
    private val suggestAdapter = SuggestionAdapter(
        onPick = { submitSearch(it) },
        onInsert = { query.setText("$it "); query.setSelection(query.text.length) },
        onRemove = { q ->
            AlertDialog.Builder(this)
                .setMessage("Удалить «$q» из истории?")
                .setPositiveButton("Удалить") { _, _ ->
                    Prefs.removeHistory(q)
                    updateSuggestions()
                }
                .setNegativeButton("Отмена", null)
                .show()
        },
    )

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
        accountIcon = findViewById(R.id.account)
        header = findViewById(R.id.header)
        searchBar = findViewById(R.id.searchBar)
        searchContainer = findViewById(R.id.searchContainer)
        suggestions = findViewById(R.id.suggestions)
        query = findViewById(R.id.query)
        mini = findViewById(R.id.mini)
        miniProgress = findViewById(R.id.miniProgress)
        miniTitle = findViewById(R.id.miniTitle)
        miniChannel = findViewById(R.id.miniChannel)
        miniThumb = findViewById(R.id.miniThumb)
        miniPlay = findViewById(R.id.miniPlay)
        pager = findViewById(R.id.pager)

        // Вкладки: каждая — отдельная лента, живут все три (кэш и позиция сохраняются)
        feeds = tabs.map { t ->
            Feed(this, pager, ::openVideo, t.needsLogin, "browse") { InnerTube.browse(t.browseId) }
        }
        pager.offscreenPageLimit = feeds.size
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = feeds.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = feeds[viewType].root
                (v.parent as? ViewGroup)?.removeView(v)
                v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                navItems.forEachIndexed { i, v -> v.alpha = if (i == position) 1f else 0.55f }
                if (!feeds[position].loaded) feeds[position].load()
            }
        })

        navItems = tabs.map { findViewById(it.navId) }
        navItems.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (searchContainer.visibility == View.VISIBLE) exitSearch()
                if (pager.currentItem == i) feeds[i].scrollTop() else pager.setCurrentItem(i, false)
            }
        }

        // Поиск
        searchFeed = Feed(this, searchContainer, ::openVideo, false, "search") { InnerTube.search(searchQuery) }
        searchContainer.addView(searchFeed.root, 0)
        suggestions.layoutManager = LinearLayoutManager(this)
        suggestions.adapter = suggestAdapter
        findViewById<View>(R.id.searchBtn).setOnClickListener { enterSearch() }
        findViewById<View>(R.id.searchBack).setOnClickListener { exitSearch() }
        findViewById<View>(R.id.searchClear).setOnClickListener {
            query.setText("")
            showSuggestions()
        }
        query.setOnClickListener { showSuggestions() }
        query.setOnFocusChangeListener { _, has -> if (has) showSuggestions() }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suggestions.visibility == View.VISIBLE) updateSuggestions()
            }
        })
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(query.text.toString())
                true
            } else false
        }

        accountIcon.setOnClickListener { showAccountMenu() }

        mini.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        miniPlay.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        findViewById<View>(R.id.miniClose).setOnClickListener {
            controller?.run { stop(); clearMediaItems() }
            mini.visibility = View.GONE
        }

        shownAccount = Auth.active?.id
        navItems.forEachIndexed { i, v -> v.alpha = if (i == 0) 1f else 0.55f }
        feeds[0].load()
    }

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
        val acc = Auth.active
        if (acc != null && acc.name.isEmpty()) {
            Net.io.execute {
                val info = runCatching { InnerTube.accountInfo() }.getOrNull() ?: return@execute
                Auth.setInfo(acc, info.first, info.second)
                runOnUiThread { updateAccountIcon() }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            searchContainer.visibility != View.VISIBLE -> super.onBackPressed()
            suggestions.visibility == View.VISIBLE && searchFeed.loaded -> hideSuggestions()
            else -> exitSearch()
        }
    }

    private fun openVideo(v: Video) {
        Streams.prefetch(v.id) // потоки начинают готовиться ещё до открытия плеера
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, v.id)
                .putExtra(PlayerActivity.EXTRA_TITLE, v.title)
        )
    }

    // ---------- поиск ----------

    private fun enterSearch() {
        header.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
        searchContainer.visibility = View.VISIBLE
        query.requestFocus()
        showSuggestions()
        getSystemService(InputMethodManager::class.java).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitSearch() {
        hideKeyboard()
        searchBar.visibility = View.GONE
        header.visibility = View.VISIBLE
        searchContainer.visibility = View.GONE
        query.clearFocus()
    }

    private fun submitSearch(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        query.setText(text)
        query.setSelection(text.length)
        Prefs.addHistory(text)
        searchQuery = text
        hideSuggestions()
        searchFeed.load()
    }

    private fun showSuggestions() {
        suggestions.visibility = View.VISIBLE
        updateSuggestions()
    }

    private fun hideSuggestions() {
        suggestions.visibility = View.GONE
        hideKeyboard()
        query.clearFocus()
    }

    private fun hideKeyboard() =
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)

    private fun updateSuggestions() {
        val q = query.text.toString().trim()
        val g = ++suggestGen
        val hist = Prefs.history.filter { q.isEmpty() || it.startsWith(q, ignoreCase = true) }
        suggestAdapter.set(hist.take(if (q.isEmpty()) 30 else 3).map { it to true })
        if (q.isEmpty()) return
        // небольшая задержка, чтобы не слать запрос на каждую букву
        ui.postDelayed({
            if (g != suggestGen) return@postDelayed
            Net.io.execute {
                val s = runCatching { InnerTube.suggestions(q) }.getOrDefault(emptyList())
                runOnUiThread {
                    if (g != suggestGen) return@runOnUiThread
                    val h = hist.take(3)
                    suggestAdapter.set(h.map { it to true } + s.filter { x -> h.none { it.equals(x, true) } }.map { it to false })
                }
            }
        }, 200)
    }

    // ---------- аккаунты ----------

    private fun updateAccountIcon() {
        val acc = Auth.active
        if (acc?.avatar != null) accountIcon.loadAvatar(acc.avatar)
        else accountIcon.setImageResource(R.drawable.ic_account)
    }

    private fun accountChanged() {
        shownAccount = Auth.active?.id
        feeds.forEach { it.reset() }
        searchFeed.reset()
        feeds[pager.currentItem].load()
        if (searchQuery.isNotEmpty()) searchFeed.load()
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
