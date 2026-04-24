package tw.pp.kazi.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object HttpClients {

    private val verifyingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(NetworkConfig.CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectTimeout(NetworkConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(NetworkConfig.WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionPool(
                ConnectionPool(
                    NetworkConfig.CONN_POOL_SIZE,
                    NetworkConfig.CONN_KEEPALIVE_MIN,
                    TimeUnit.MINUTES,
                ),
            )
            .retryOnConnectionFailure(false)
            .build()
    }

    private val noVerifyClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        verifyingClient.newBuilder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun forSite(sslVerify: Boolean): OkHttpClient =
        if (sslVerify) verifyingClient else noVerifyClient
}
