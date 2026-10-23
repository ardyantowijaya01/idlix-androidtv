package com.idlix.data.api

import android.content.Context
import com.idlix.data.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val PREF_KEY_SERVER_URL = "custom_backend_url"
    private const val DEFAULT_URL = BuildConfig.BACKEND_API_URL

    @Volatile
    var applicationContext: Context? = null
        internal set

    @Volatile
    private var userAgentHeader: String = "Idlix-Client/1.0"

    fun init(context: Context, userAgent: String? = null) {
        this.applicationContext = context.applicationContext
        if (!userAgent.isNullOrBlank()) {
            this.userAgentHeader = userAgent
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Volatile
    private var currentBaseUrl: String? = null

    fun getBaseUrl(): String {
        currentBaseUrl?.let { return it }
        val saved = try {
            val prefs = applicationContext?.getSharedPreferences("idlix_config", Context.MODE_PRIVATE)
            prefs?.getString(PREF_KEY_SERVER_URL, null)
        } catch (_: Exception) {
            null
        }

        val url = if (!saved.isNullOrBlank()) {
            if (saved.endsWith("/")) saved else "$saved/"
        } else {
            if (DEFAULT_URL.endsWith("/")) DEFAULT_URL else "$DEFAULT_URL/"
        }
        currentBaseUrl = url
        return url
    }

    fun setBaseUrl(newUrl: String) {
        val formatted = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        currentBaseUrl = formatted
        try {
            val prefs = applicationContext?.getSharedPreferences("idlix_config", Context.MODE_PRIVATE)
            prefs?.edit()?.putString(PREF_KEY_SERVER_URL, formatted)?.apply()
        } catch (_: Exception) {}

        synchronized(this) {
            _idlixApi = null
            _majorplayApi = null
        }
    }

    private val headerInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val modifiedRequest = originalRequest.newBuilder()
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", userAgentHeader)
            .build()
        chain.proceed(modifiedRequest)
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Volatile
    private var _idlixApi: IdlixApiService? = null
    val idlixApi: IdlixApiService
        get() = _idlixApi ?: synchronized(this) {
            _idlixApi ?: buildRetrofit(getBaseUrl()).create(IdlixApiService::class.java).also { _idlixApi = it }
        }

    @Volatile
    private var _majorplayApi: MajorplayApiService? = null
    val majorplayApi: MajorplayApiService
        get() = _majorplayApi ?: synchronized(this) {
            _majorplayApi ?: buildRetrofit(getBaseUrl()).create(MajorplayApiService::class.java).also { _majorplayApi = it }
        }
}
