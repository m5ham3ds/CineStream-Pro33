package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import com.example.MyApplication
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

object RetrofitClient {

    private const val BASE_URL = "https://api.themoviedb.org/3/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (com.example.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    private val cacheSize = (50 * 1024 * 1024).toLong() // 50 MB

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = com.example.di.AppContainer.application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo != null && activeNetworkInfo.isConnected
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .cache(Cache(File(com.example.di.AppContainer.application.cacheDir, "http_cache"), cacheSize))
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val originalUrl = originalRequest.url
                
                val currentLanguage = java.util.Locale.getDefault().language
                val tmdbLanguage = if (currentLanguage == "ar") "ar" else "en-US"
                
                val urlWithLanguage = originalUrl.newBuilder()
                    .addQueryParameter("language", tmdbLanguage)
                    .build()
                
                var request = originalRequest.newBuilder().url(urlWithLanguage).build()
                // Always try network first with short cache, unless explicitly offline
                if (!isNetworkAvailable()) {
                    request = request.newBuilder()
                        .header("Cache-Control", "public, only-if-cached, max-stale=" + 60 * 60 * 24 * 7)
                        .build()
                } else {
                    request = request.newBuilder()
                        .header("Cache-Control", "public, max-age=" + 60)
                        .build()
                }

                try {
                    chain.proceed(request)
                } catch (e: Exception) {
                    // Fallback to cache if network throws (e.g. no internet despite active connection)
                    val offlineRequest = request.newBuilder()
                        .header("Cache-Control", "public, only-if-cached, max-stale=" + 60 * 60 * 24 * 7)
                        .build()
                    chain.proceed(offlineRequest)
                }
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val cacheControl = response.header("Cache-Control")
                if (cacheControl == null || cacheControl.contains("no-store") || cacheControl.contains("no-cache") ||
                    cacheControl.contains("must-revalidate") || cacheControl.contains("max-age=0")) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=" + 60 * 60) // Cache for 1 hour
                        .build()
                } else {
                    response
                }
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val tmdbApi: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApiService::class.java)
    }
}