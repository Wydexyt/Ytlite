package by.hradovich.ytlite

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Обычная страница входа Google; после входа забираем cookie youtube.com. */
class LoginActivity : Activity() {

    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Без пометки WebView Google реже ругается на «небезопасный браузер»
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!url.contains("youtube.com") || url.contains("accounts.google")) return
                val c = cm.getCookie("https://www.youtube.com") ?: return
                if (c.contains("SAPISID") && !done) {
                    done = true
                    Auth.add(c)
                    cm.flush()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        // Чистый вход: старые cookie WebView не должны подставить прошлый аккаунт
        cm.removeAllCookies {
            web.loadUrl(
                "https://accounts.google.com/ServiceLogin?service=youtube&passive=true" +
                    "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
                    "%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
