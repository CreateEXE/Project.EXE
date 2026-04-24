package com.projectexe.memory

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class MemoryMaintenanceWorker(private val dao: MemoryDao, private val scope: CoroutineScope) {
    private var count = 0
    fun onMemoryInserted() { if (++count >= 50) { count = 0; run() } }
    fun run() = scope.launch(Dispatchers.IO) {
        try {
            val cutoff = Instant.now().minusSeconds(TimeUnit.DAYS.toSeconds(30)).epochSecond
            val n = dao.pruneOld(cutoff)
            if (n > 0) Log.i("EXE.Maint", "Pruned $n memories")
        } catch (e: Exception) { Log.e("EXE.Maint", "Failed", e) }
    }
}
