package by.hradovich.ytlite

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class Account(val id: String, var name: String, var avatar: String?, var cookies: String)

/** Несколько аккаунтов YouTube: cookie каждого и заголовки авторизации для InnerTube. */
object Auth {
    private lateinit var prefs: SharedPreferences
    private val list = ArrayList<Account>()
    private var activeId: String? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        runCatching {
            val a = JSONArray(prefs.getString("accounts", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list += Account(
                    o.getString("id"), o.optString("name"),
                    o.optString("avatar").ifEmpty { null }, o.getString("cookies")
                )
            }
        }
        activeId = prefs.getString("active", null)
        // Перенос со старой версии (один аккаунт)
        prefs.getString("cookies", null)?.let { old ->
            if (list.isEmpty()) add(old)
            prefs.edit().remove("cookies").apply()
        }
        if (active == null) activeId = list.firstOrNull()?.id
    }

    val accounts: List<Account> @Synchronized get() = list.toList()

    val active: Account? @Synchronized get() = list.firstOrNull { it.id == activeId }

    val loggedIn: Boolean
        get() = parse(active?.cookies).let { it.containsKey("SAPISID") || it.containsKey("__Secure-3PAPISID") }

    @Synchronized
    fun add(cookies: String): Account {
        val a = Account(UUID.randomUUID().toString(), "", null, cookies)
        list += a
        activeId = a.id
        save()
        return a
    }

    @Synchronized
    fun switchTo(id: String) {
        if (list.any { it.id == id }) {
            activeId = id
            save()
        }
    }

    /** Выход из активного аккаунта. */
    @Synchronized
    fun logout() {
        list.removeAll { it.id == activeId }
        activeId = list.firstOrNull()?.id
        save()
    }

    @Synchronized
    fun setInfo(acc: Account, name: String, avatar: String?) {
        // Тот же аккаунт добавили повторно — убираем старую копию
        list.removeAll { it.id != acc.id && it.name == name && name.isNotEmpty() }
        acc.name = name
        acc.avatar = avatar
        save()
    }

    private fun save() {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("avatar", it.avatar ?: "").put("cookies", it.cookies)
            )
        }
        prefs.edit().putString("accounts", a.toString()).putString("active", activeId).apply()
    }

    private fun parse(cookies: String?): LinkedHashMap<String, String> {
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
        val acc = active ?: return
        if (setCookie.isEmpty()) return
        val m = parse(acc.cookies)
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
        if (changed) {
            acc.cookies = m.entries.joinToString("; ") { "${it.key}=${it.value}" }
            save()
        }
    }

    fun headers(origin: String = "https://www.youtube.com"): Map<String, String> {
        val c = active?.cookies ?: return emptyMap()
        val m = parse(c)
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
