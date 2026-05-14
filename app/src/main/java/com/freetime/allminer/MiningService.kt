package com.freetime.allminer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class MiningService : Service() {

    init {
        System.loadLibrary("allminer")
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var miningJobs = mutableListOf<Job>()
    
    var isMining = false
    var hashrate = 0.0
    var totalHashes = 0L
    var currentCoin = ""

    inner class LocalBinder : Binder() {
        fun getService(): MiningService = this@MiningService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mining_channel",
                "Mining Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "mining_channel")
            .setContentTitle("All Miner Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play) // Replace with app icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun startMining(coin: String, address: String, threads: Int, args: String) {
        if (isMining) return
        
        currentCoin = coin
        isMining = true
        
        startForeground(1, getNotification("Mining $coin..."))

        serviceScope.launch {
            // Native initialization (RandomX logic remains same)
            // Note: In a real implementation, ensure JNI is loaded
            
            repeat(threads) { threadId ->
                miningJobs.add(launch {
                    while (isActive && isMining) {
                        val startTime = System.currentTimeMillis()
                        // This calls the same native methods
                        // For simplicity, we assume the native lib is already loaded in the process
                        val batchHashes = performNativeHash(threadId, "$coin:$address".toByteArray())
                        
                        val endTime = System.currentTimeMillis()
                        val timeTakenSec = (endTime - startTime) / 1000.0
                        if (timeTakenSec > 0) {
                            hashrate = (hashrate * 0.9 + (batchHashes / timeTakenSec) * 0.1)
                        }
                        totalHashes += batchHashes
                        
                        if (totalHashes % 100 == 0L) {
                            updateNotification("Hashrate: ${String.format("%.2f", hashrate)} H/s")
                        }
                        delay(10)
                    }
                })
            }
        }
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, getNotification(content))
    }

    fun stopMining() {
        isMining = false
        miningJobs.forEach { it.cancel() }
        miningJobs.clear()
        hashrate = 0.0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Proxy to native method
    private external fun performNativeHash(threadId: Int, input: ByteArray): Long

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
