package by.hradovich.ytlite

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** Cookie аккаунта YouTube и заголовки авторизации для InnerTube. */
object Auth {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    }

    var cookies: String?
        get() = prefs.getString("cookies", null)
        set(v) = prefs.edit().putString("cookies", v).apply()

    val loggedIn: Boolean
        get() = parse().let { it.containsKey("SAPISID") || it.containsKey("__Secure-3PAPISID") }

    fun logout() {
        cookies = null
    }

    private fun parse(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        cookies?.split(';')?.forEach { p ->
            val i = p.indexOf('=')
            if (i > 0) m[p.substring(0, i).trim()] = p.substring(i + 1).trim()
        }
        return m
    }

    /** Google периодически ротирует часть cookie — подхватываем новые значения. */
    @Synchronized
    fun merge(setCookie: List<String>) {
        if (setCookie.isEmpty() || cookies == null) return
        val m = parse()
        var changed = false
        for (h in setCookie) {
            val kv = h.substringBefore(';')
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val k = kv.substring(0, i).trim()
            val v = kv.substring(i + 1).trim()
            if ((k.endsWith("SIDTS") || k.endsWith("SIDCC")) && v.isNotEmpty() && m[k] != v) {
                m[k] = v
                changed = true
            }
        }
        if (changed) cookies = m.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun headers(origin: String = "https://www.youtube.com"): Map<String, String> {
        val c = cookies ?: return emptyMap()
        val m = parse()
        val ts = System.currentTimeMillis() / 1000
        fun hash(scheme: String, v: String?) = v?.let { "$scheme ${ts}_${sha1("$ts $it $origin")}" }
        val auth = listOfNotNull(
            hash("SAPISIDHASH", m["SAPISID"] ?: m["__Secure-3PAPISID"]),
            hash("SAPISID1PHASH", m["__Secure-1PAPISID"]),
            hash("SAPISID3PHASH", m["__Secure-3PAPISID"]),
        ).joinToString(" ")
        val h = mutableMapOf(
            "Cookie" to c,
            "X-Origin" to origin,
            "Origin" to origin,
            "X-Goog-AuthUser" to "0",
        )
        if (auth.isNotEmpty()) h["Authorization"] = auth
        return h
    }

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
