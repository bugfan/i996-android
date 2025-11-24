package com.i996.nat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun authenticate(token: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.i996.me/sys-auth")
                .header("ClothoVersion", "v2")
                .header("Authorization", token)
                .post(RequestBody.create("text/plain".toMediaType(), ""))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful || body.isEmpty()) {
                return@withContext null
            }

            // 解析响应: ClothoBroadcast[公网域名]|[内网地址]
            if (body.contains("ClothoBroadcast")) {
                val info = body.substringAfter("ClothoBroadcast")
                val parts = info.split("|")
                val publicHost = parts.getOrNull(0) ?: ""
                val privateHost = parts.getOrNull(1) ?: ""
                return@withContext Pair(publicHost, privateHost)
            }

            null
        } catch (e: IOException) {
            null
        }
    }
}