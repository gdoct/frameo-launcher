package app.framelauncher

import android.view.View
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** A full-screen native show (photos, clock, animations) hosted by [MainActivity]. */
interface Show {
    val view: View
    /** Timer tick or swipe left. */
    fun next() {}
    /** Swipe right. */
    fun prev() {}
    /** Swipe up: something different (a fresh batch of photos). */
    fun skip() {}
    /** Release threads and animations; the view is discarded afterwards. */
    fun stop() {}
}

/** A non-2xx HTTP response. */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Blocking HTTP GET for worker threads. */
object Net {
    /** Mirrors the "accept self-signed certificates" setting. */
    @Volatile var allowInsecureSsl = true

    fun get(url: String, timeoutMs: Int = 15_000): ByteArray {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false   // followed below, also across http/https
            if (c is HttpsURLConnection && allowInsecureSsl) {
                c.sslSocketFactory = trustAll
                c.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                    u = URL(u, loc)
                } else if (code !in 200..299) {
                    throw HttpStatusException(code, "HTTP $code for $u")
                } else {
                    return c.inputStream.use { it.readBytes() }
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects for $url")
    }

    fun getString(url: String) = String(get(url), Charsets.UTF_8)

    private val trustAll: SSLSocketFactory by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
    }
}
