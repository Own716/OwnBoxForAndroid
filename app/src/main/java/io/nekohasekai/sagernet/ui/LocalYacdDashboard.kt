package io.nekohasekai.sagernet.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Only the bundled, same-origin controller may receive the application secret. */
object LocalYacdDashboard {
    const val ENDPOINT = "http://127.0.0.1:9090"
    enum class Failure { DISCONNECTED, DISABLED, AUTHENTICATION, API, HTML }

    fun isLocalDocument(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "http" && parsed.host == "127.0.0.1" && parsed.port == 9090 &&
            parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedPath in setOf("/ui", "/ui/", "/ui/index.html")
    }

    fun readiness(connected: Boolean, apiEnabled: Boolean): Failure? = when {
        !connected -> Failure.DISCONNECTED
        !apiEnabled -> Failure.DISABLED
        else -> null
    }

    fun checkApi(client: OkHttpClient, secret: String): Failure? = try {
        // No URL argument, redirects or remote fallback: the credential cannot leave this origin.
        val request = Request.Builder().url("$ENDPOINT/version")
            .header("Authorization", "Bearer $secret").get().build()
        val localClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
        localClient.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> Failure.AUTHENTICATION
                !response.isSuccessful -> Failure.API
                runCatching {
                    val bodyText = response.body?.string().orEmpty()
                    JSONObject(bodyText).optString("version").isNotBlank()
                }.getOrDefault(false).not() -> Failure.API
                else -> null
            }
        }
    } catch (_: Exception) {
        // Return a visible error category; never expose request headers or exception messages.
        Failure.API
    }

    fun bootstrap(script: String, secret: String, storageError: String): String {
        val settings = JSONObject().put("endpoint", ENDPOINT).put("secret", secret)
            .put("storageError", storageError).toString()
            .replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return "<script>" + script.replace("__OWNBOX_YACD_CONFIG__", settings) + "</script>"
    }

    fun errorHtml(message: String, reload: String): String {
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        return """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><p role="alert">${escape(message)}</p><button onclick="location.reload()">${escape(reload)}</button></body></html>"""
    }
}
