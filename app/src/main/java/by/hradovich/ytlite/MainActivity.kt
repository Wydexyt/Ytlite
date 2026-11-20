package by.hradovich.ytlite

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.common.util.concurrent.ListenableFuture

/** Единственное окно приложения: ленты, поиск, страницы каналов, статистика и плеер-панель. */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private class TabDef(val title: Int, val browseId: String, val needsLogin: Boolean, val navId: Int)

    private val tabs = listOf(
        TabDef(R.string.tab_home, "FEwhat_to_watch", false, R.id.navHome),
        TabDef(R.string.tab_subs, "FEsubscriptions", true, R.id.navSubs),
        TabDef(R.string.tab_history, "FEhistory", true, R.id.navHistory),
    )

    private lateinit var pager: ViewPager2
    private lateinit var feeds: List<Feed>
    private lateinit var navItems: List<View>
    private lateinit var accountIcon: ImageView
    private var shownAccount: String? = null
    private var firstFeedLoaded = false

    // страницы поверх лент
    private lateinit var pagesBox: FrameLayout
    private val pages = ArrayList<AppPage>()

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
                .setMessage(getString(R.string.history_remove, q))
                .setPositiveButton(R.string.remove) { _, _ ->
                    Prefs.removeHistory(q)
                    updateSuggestions()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        },
    )

    // плеер
    private lateinit var panel: View
    private lateinit var player: PlayerScreen
    private var started = false

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

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        // Заставка с иконкой — пока не загрузится первая лента (не дольше 2 с)
        val splash = installSplashScreen()
        val splashStart = System.currentTimeMillis()
        splash.setKeepOnScreenCondition { !firstFeedLoaded && System.currentTimeMillis() - splashStart < 2000 }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Рисуем и в области выреза камеры — иначе в полноэкранном режиме видео смещено от центра
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        accountIcon = findViewById(R.id.account)
        // Тестовая версия видна сразу — пометка β в шапке
        if (Updates.isBeta) findViewById<TextView>(R.id.appTitle).text = getString(R.string.app_name_beta)
        header = findViewById(R.id.header)
        searchBar = findViewById(R.id.searchBar)
        searchContainer = findViewById(R.id.searchContainer)
        suggestions = findViewById(R.id.suggestions)
        query = findViewById(R.id.query)
        pagesBox = findViewById(R.id.pages)
        mini = findViewById(R.id.mini)
        miniProgress = findViewById(R.id.miniProgress)
        miniTitle = findViewById(R.id.miniTitle)
        miniChannel = findViewById(R.id.miniChannel)
        miniThumb = findViewById(R.id.miniThumb)
        miniPlay = findViewById(R.id.miniPlay)
        pager = findViewById(R.id.pager)
        panel = findViewById(R.id.playerPanel)
        player = PlayerScreen(this, panel)

        // Вкладки: каждая — отдельная лента, живут все три (кэш и позиция сохраняются)
        feeds = tabs.map { t ->
            Feed(this, getString(t.title), pager, { openVideo(it.id, it.title) }, t.needsLogin, "browse") {
                InnerTube.browse(t.browseId)
            }
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
                closeAllPages()
                if (searchContainer.visibility == View.VISIBLE) exitSearch()
                if (pager.currentItem == i) feeds[i].scrollTop() else pager.setCurrentItem(i, false)
            }
        }

        // Поиск
        searchFeed = Feed(this, getString(R.string.search), searchContainer, { openVideo(it.id, it.title) }, false, "search") {
            InnerTube.search(searchQuery)
        }
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

        mini.setOnClickListener { expandPlayer() }
        miniPlay.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        findViewById<View>(R.id.miniClose).setOnClickListener {
            controller?.run { stop(); clearMediaItems() }
            mini.visibility = View.GONE
        }

        shownAccount = Auth.active?.id
        navItems.forEachIndexed { i, v -> v.alpha = if (i == 0) 1f else 0.55f }
        // Сначала Главная, сразу после неё — остальные вкладки (только списки, превью — по мере показа)
        feeds[0].onFirstLoad = {
            firstFeedLoaded = true
            feeds.drop(1).forEach { if (!it.loaded) it.load() }
        }
        feeds[0].load()
        Updates.check(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Видео из ссылки (ACTION_VIEW), «Поделиться» (ACTION_SEND) или уведомления плеера. */
    private fun handleIntent(i: Intent?) {
        i ?: return
        val link = when (i.action) {
            Intent.ACTION_VIEW -> Links.parse(i.data)
            Intent.ACTION_SEND -> Links.parseText(i.getStringExtra(Intent.EXTRA_TEXT))
            else -> null
        }
        when {
            link != null -> openVideo(link.first, null, link.second)
            i.action == Intent.ACTION_VIEW || i.action == Intent.ACTION_SEND ->
                Toast.makeText(this, R.string.link_unsupported, Toast.LENGTH_SHORT).show()
            i.getBooleanExtra(EXTRA_EXPAND, false) -> expandPlayer()
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        started = true
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            player.attach(c)
            updateVideoTrack()
            updateMini()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        started = false
        updateVideoTrack() // окно скрыто — дальше только звук
        ui.removeCallbacks(tick)
        controller?.removeListener(playerListener)
        player.detach()
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        feeds.forEach { it.refreshProgress() } // полоски «просмотрено»
        searchFeed.refreshProgress()
        pages.lastOrNull()?.onResume()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        player.onConfigurationChanged()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            player.expanded -> if (!player.onBack()) collapsePlayer()
            pages.isNotEmpty() -> if (!pages.last().onBack()) closePage(pages.last())
            searchContainer.visibility == View.VISIBLE ->
                if (suggestions.visibility == View.VISIBLE && searchFeed.loaded) hideSuggestions() else exitSearch()
            else -> super.onBackPressed()
        }
    }

    // ---------- плеер-панель ----------

    fun openVideo(id: String, title: String?, start: Long = 0) {
        player.open(id, title, start)
        expandPlayer()
    }

    /** Видео показывается только в развёрнутом плеере при открытом окне; иначе — только звук. */
    private fun updateVideoTrack() {
        val c = controller ?: return
        val video = started && player.expanded && !PlaybackService.audioOnly
        if (c.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == !video) return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !video)
            .build()
    }

    fun onAudioOnlyChanged() = updateVideoTrack()

    fun expandPlayer() {
        if (player.expanded && panel.translationY == 0f) return
        hideKeyboard()
        // сначала снять обработчик конца сворачивания, иначе отмена анимации спрячет панель
        panel.animate().setListener(null)
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        if (!player.expanded) panel.translationY = (panel.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        player.setExpanded(true)
        updateVideoTrack()
        panel.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2f))
            .setListener(null)
        updateMini()
    }

    fun collapsePlayer() {
        if (!player.expanded) return
        player.prepareCollapse()
        panel.animate().setListener(null)
        panel.animate().cancel()
        panel.animate().translationY(panel.height.toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.animate().setListener(null)
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                }
            })
        player.setExpanded(false)
        updateVideoTrack()
        updateMini()
    }

    /** Свайп вниз по видео: панель тянется за пальцем. */
    fun dragPlayer(dy: Float) {
        val d = dy.coerceAtLeast(0f)
        panel.translationY = d
        panel.alpha = 1f - (d / panel.height.coerceAtLeast(1)).coerceIn(0f, 0.5f)
        if (mini.visibility != View.VISIBLE && controller?.currentMediaItem != null) mini.visibility = View.VISIBLE
    }

    fun dragPlayerEnd(dy: Float) {
        if (dy > panel.height * 0.18f) collapsePlayer()
        else {
            panel.animate().translationY(0f).alpha(1f).setDuration(160).setListener(null)
            updateMini()
        }
    }

    // ---------- страницы: канал, статистика ----------

    fun openChannel(id: String) {
        if (player.expanded) collapsePlayer()
        openPage(ChannelPage(this, pagesBox, id))
    }

    fun openStats() = openPage(StatsPage(this, pagesBox))

    private fun openPage(p: AppPage) {
        pages += p
        pagesBox.addView(p.view)
        p.view.translationX = resources.displayMetrics.widthPixels.toFloat()
        p.view.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f))
    }

    fun closePage(p: AppPage) {
        pages.remove(p)
        p.view.animate().translationX(p.view.width.toFloat()).setDuration(200)
            .withEndAction { pagesBox.removeView(p.view) }
    }

    private fun closeAllPages() {
        pages.forEach { pagesBox.removeView(it.view) }
        pages.clear()
    }

    // ---------- поиск ----------

    private fun enterSearch() {
        closeAllPages()
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

    // ---------- аккаунты и меню ----------

    private fun updateAccountIcon() {
        val acc = Auth.active
        if (acc?.avatar != null) accountIcon.loadAvatar(acc.avatar)
        else accountIcon.setImageResource(R.drawable.ic_account)
    }

    private fun accountChanged() {
        shownAccount = Auth.active?.id
        feeds.forEach { it.reset() }
        searchFeed.reset()
        feeds.forEach { it.load() }
        if (searchQuery.isNotEmpty()) searchFeed.load()
    }

    private fun showAccountMenu() {
        val menu = PopupMenu(this, accountIcon)
        val accounts = Auth.accounts
        val active = Auth.active?.id
        accounts.forEachIndexed { i, a ->
            val title = (if (a.id == active) "✓ " else "    ") + a.name.ifEmpty { getString(R.string.account_n, i + 1) }
            menu.menu.add(0, i, i, title)
        }
        menu.menu.add(1, ADD, 100, R.string.add_account)
        if (active != null) menu.menu.add(1, LOGOUT, 101, R.string.logout)
        menu.menu.add(2, STATS, 102, R.string.stats)
        menu.menu.add(2, QUALITY_WIFI, 103, getString(R.string.quality_wifi, qualityName(Prefs.qualityWifi)))
        menu.menu.add(2, QUALITY_MOBILE, 104, getString(R.string.quality_mobile, qualityName(Prefs.qualityMobile)))
        menu.menu.add(2, SPONSOR, 105, R.string.sponsorblock_menu)
        menu.menu.add(2, LANGUAGE, 106, getString(R.string.language_menu, languageName(Prefs.language)))
        menu.menu.add(3, LOG, 107, R.string.error_log)
        menu.menu.add(3, CHANNEL, 108, getString(R.string.channel_menu, getString(if (Prefs.channel == "beta") R.string.channel_beta else R.string.channel_stable)))
        if (Updates.isBeta) menu.menu.add(3, ROLLBACK, 109, R.string.rollback_title)
        menu.menu.add(3, UPDATE, 110, getString(R.string.check_updates, BuildConfig.VERSION_NAME))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ADD -> startActivity(Intent(this, LoginActivity::class.java))
                LOGOUT -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.logout_confirm, Auth.active?.name.orEmpty()))
                    .setPositiveButton(R.string.logout) { _, _ ->
                        Auth.logout()
                        updateAccountIcon()
                        accountChanged()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                STATS -> openStats()
                QUALITY_WIFI -> chooseQuality(true)
                QUALITY_MOBILE -> chooseQuality(false)
                SPONSOR -> showSponsorSettings()
                LANGUAGE -> chooseLanguage()
                UPDATE -> Updates.check(this, force = true)
                CHANNEL -> chooseChannel()
                ROLLBACK -> Updates.rollback(this)
                LOG -> showErrorLog()
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

    private fun chooseChannel() {
        val values = arrayOf("stable", "beta")
        AlertDialog.Builder(this)
            .setTitle(R.string.channel)
            .setSingleChoiceItems(
                arrayOf(getString(R.string.channel_stable_long), getString(R.string.channel_beta_long)),
                values.indexOf(Prefs.channel)
            ) { d, i ->
                d.dismiss()
                Prefs.channel = values[i]
                if (values[i] == "beta") Updates.check(this, force = true)
                else if (Updates.isBeta) Updates.rollback(this) // стоит бета — предложить вернуться на стабильную
            }
            .show()
    }

    private fun languageName(l: String) = getString(
        when (l) {
            "ru" -> R.string.lang_ru
            "en" -> R.string.lang_en
            else -> R.string.lang_system
        }
    )

    private fun chooseLanguage() {
        val values = arrayOf("system", "ru", "en")
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(values.map { languageName(it) }.toTypedArray(), values.indexOf(Prefs.language)) { d, i ->
                d.dismiss()
                if (values[i] != Prefs.language) {
                    Prefs.language = values[i]
                    Lang.changed(this)
                    recreate()
                }
            }
            .show()
    }

    /** Настройки SponsorBlock: общий переключатель и категории. */
    private fun showSponsorSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val main = android.widget.Switch(this).apply {
            text = getString(R.string.sb_auto)
            textSize = 16f
            isChecked = Prefs.sponsorBlock
            setPadding(0, pad / 2, 0, pad / 2)
        }
        box.addView(main)
        box.addView(TextView(this).apply {
            text = getString(R.string.sb_what)
            setTextColor(getColor(R.color.sub))
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 4)
        })
        val selected = Prefs.sponsorCategories.toMutableSet()
        val checks = SponsorBlock.CATEGORIES.map { (key, label) ->
            android.widget.CheckBox(this).apply {
                text = getString(label)
                isChecked = key in selected
                isEnabled = main.isChecked
                setOnCheckedChangeListener { _, c -> if (c) selected += key else selected -= key }
                box.addView(this)
            }
        }
        main.setOnCheckedChangeListener { _, c -> checks.forEach { it.isEnabled = c } }
        box.addView(TextView(this).apply {
            text = getString(R.string.sb_credit)
            setTextColor(getColor(R.color.sub))
            textSize = 11f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        AlertDialog.Builder(this)
            .setTitle("SponsorBlock")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.sponsorBlock = main.isChecked
                Prefs.sponsorCategories = selected
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showErrorLog() {
        val text = ErrorLog.read()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            this.text = text.ifEmpty { getString(R.string.no_errors) }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.error_log)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.copy_all) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("YT Lite errors", ErrorLog.export()))
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.clear) { _, _ -> ErrorLog.clear() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun chooseQuality(wifi: Boolean) {
        val values = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240, 144, 0)
        val names = values.map { qualityName(it) }.toTypedArray()
        val cur = if (wifi) Prefs.qualityWifi else Prefs.qualityMobile
        AlertDialog.Builder(this)
            .setTitle(if (wifi) R.string.quality_wifi_title else R.string.quality_mobile_title)
            .setSingleChoiceItems(names, values.indexOf(cur)) { d, i ->
                if (wifi) Prefs.qualityWifi = values[i] else Prefs.qualityMobile = values[i]
                d.dismiss()
            }
            .show()
    }

    private fun qualityName(q: Int) = if (q == 0) getString(R.string.quality_max) else "${q}p"

    // ---------- мини-плеер ----------

    private fun updateMini() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null || c.playbackState == Player.STATE_IDLE || (player.expanded && panel.translationY == 0f)) {
            if (!(player.expanded && panel.translationY > 0f)) mini.visibility = View.GONE
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
        const val EXTRA_EXPAND = "expand"
        private const val ADD = Menu.FIRST + 1000
        private const val LOGOUT = Menu.FIRST + 1001
        private const val QUALITY_WIFI = Menu.FIRST + 1002
        private const val QUALITY_MOBILE = Menu.FIRST + 1003
        private const val SPONSOR = Menu.FIRST + 1004
        private const val UPDATE = Menu.FIRST + 1005
        private const val LOG = Menu.FIRST + 1006
        private const val STATS = Menu.FIRST + 1007
        private const val LANGUAGE = Menu.FIRST + 1008
        private const val CHANNEL = Menu.FIRST + 1009
        private const val ROLLBACK = Menu.FIRST + 1010
    }
}

/** Открыть канал из любого места интерфейса. */
fun Context.openChannel(id: String) {
    (this as? MainActivity)?.openChannel(id)
}
