package com.pibrain.chat

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class PiBrainWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                applicationContext,
                "pibrain_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val pat = prefs.getString("github_pat", null) ?: return@withContext Result.success()
            
            // Just a lightweight check if new files exist. In V1, we track locally.
            // If we find files we haven't seen, we could fire a notification.
            // But per instructions: "Not required for V1: push notifications".
            // So background polling just keeps the PAT alive or prepares cache? 
            // The prompt says "When backgrounded, use a Periodic WorkManager job at 15-minute intervals."
            // We'll just fetch the list to fulfill the requirement.
            
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://api.github.com/repos/physicistmanoj/pibrain/contents/chat/outbox?ref=feature/anr-tier0-fix")
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                if (body.startsWith("[")) {
                    val jsonArray = JSONArray(body)
                    for (i in 0 until jsonArray.length()) {
                        val fileObj = jsonArray.getJSONObject(i)
                        val name = fileObj.getString("name")
                        val isSeen = prefs.getBoolean("seen_$name", false)
                        if (!isSeen) {
                            // In the future: trigger notification
                            Log.d("PiBrainWorker", "Found unseen outbox file: $name")
                        }
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
