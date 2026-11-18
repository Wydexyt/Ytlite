package by.hradovich.ytlite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

class MainActivity : Activity() {

    private enum class Tab(val browseId: String?, val needsLogin: Boolean) {
        HOME("FEwhat_to_watch", false),
        SUBS("FEsubscriptions", true),
        HISTORY("FEhistory", true),
        SEARCH(null, false),
    }

    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var query: EditText
    private lateinit var loginTab: TextView
    private lateinit var tabViews: Map<Tab, View>

    private val adapter = VideoAdapter { v ->
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, v.id)
                .putExtra(PlayerActivity.EXTRA_TITLE, v.title)
        )
    }

    private var tab = Tab.HOME
    private var continuation: String? = null
    private var loading = false
    private var generation = 0
    private var wasLoggedIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        query = findViewById(R.id.query)
        loginTab = findViewById(R.id.tabLogin)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) loadMore()
            }
        })

        tabViews = mapOf(
            Tab.HOME to findViewById(R.id.tabHome),
            Tab.SUBS to findViewById(R.id.tabSubs),
            Tab.HISTORY to findViewById(R.id.tabHistory),
            Tab.SEARCH to findViewById(R.id.tabSearch),
        )
        tabViews.forEach { (t, v) -> v.setOnClickListener { select(t) } }

        loginTab.setOnClickListener {
            if (Auth.loggedIn) {
                AlertDialog.Builder(this)
                    .setMessage("Выйти из аккаунта?")
                    .setPositiveButton("Выйти") { _, _ ->
                        Auth.logout()
                        CookieManager.getInstance().removeAllCookies(null)
                        refreshLogin()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
                load()
                true
            } else false
        }

        wasLoggedIn = Auth.loggedIn
        select(Tab.HOME)
    }

    override fun onResume() {
        super.onResume()
        refreshLogin()
    }

    private fun refreshLogin() {
        loginTab.text = if (Auth.loggedIn) "Выйти" else "Войти"
        if (Auth.loggedIn != wasLoggedIn) {
            wasLoggedIn = Auth.loggedIn
            load()
        }
    }

    private fun select(t: Tab) {
        tab = t
        tabViews.forEach { (k, v) -> v.alpha = if (k == t) 1f else 0.5f }
        query.visibility = if (t == Tab.SEARCH) View.VISIBLE else View.GONE
        if (t == Tab.SEARCH && query.text.isBlank()) {
            generation++
            adapter.submit(emptyList())
            showMessage("Введите запрос")
            query.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(query, 0)
        } else {
            load()
        }
    }

    private fun load() {
        val t = tab
        val q = query.text.toString().trim()
        if (t == Tab.SEARCH && q.isEmpty()) return
        val g = ++generation
        continuation = null
        adapter.submit(emptyList())
        if (t.needsLogin && !Auth.loggedIn) {
            showMessage("Войдите в аккаунт")
            return
        }
        loading = true
        message.visibility = View.GONE
        progress.visibility = View.VISIBLE
        Net.io.execute {
            val r = runCatching { if (t == Tab.SEARCH) InnerTube.search(q) else InnerTube.browse(t.browseId!!) }
            runOnUiThread {
                if (g != generation) return@runOnUiThread
                loading = false
                progress.visibility = View.GONE
                r.onSuccess {
                    adapter.submit(it.items)
                    continuation = it.continuation
                    if (it.items.isEmpty()) showMessage("Здесь пусто")
                }.onFailure { showMessage("Ошибка: ${it.message}") }
            }
        }
    }

    private fun loadMore() {
        if (loading) return
        val token = continuation ?: return
        loading = true
        val g = generation
        val endpoint = if (tab == Tab.SEARCH) "search" else "browse"
        Net.io.execute {
            val r = runCatching { InnerTube.more(endpoint, token) }
            runOnUiThread {
                if (g != generation) return@runOnUiThread
                loading = false
                r.onSuccess {
                    adapter.append(it.items)
                    continuation = it.continuation
                }.onFailure { continuation = null }
            }
        }
    }

    private fun showMessage(text: String) {
        progress.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
    }
}

class VideoAdapter(private val onClick: (Video) -> Unit) : RecyclerView.Adapter<VideoAdapter.VH>() {
    private val items = ArrayList<Video>()
    private val ids = HashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
        val meta: TextView = v.findViewById(R.id.meta)
    }

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
        h.channel.text = v.channel
        h.meta.text = v.meta
        h.thumb.load(v.thumb) { crossfade(false) }
        h.itemView.setOnClickListener { onClick(v) }
    }
}
