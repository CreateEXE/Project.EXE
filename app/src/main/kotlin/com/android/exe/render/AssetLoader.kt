package com.android.exe.render

import android.content.Context
import android.util.LruCache
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class AssetLoaderCache(
    private val context: Context,
    maxSizeMB: Int = 512
) {
    companion object {
        private const val TAG = "AssetLoaderCache"
    }

    private val cache = object : LruCache<String, ByteBuffer>(maxSizeMB * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteBuffer): Int = value.capacity()
    }

    suspend fun loadAsset(assetPath: String): ByteBuffer? {
        cache.get(assetPath)?.let {
            Log.d(TAG, "Cache hit: $assetPath")
            return it
        }
        val buffer = withContext(Dispatchers.IO) { loadFromAssets(assetPath) } ?: return null
        cache.put(assetPath, buffer)
        return buffer
    }

    private fun loadFromAssets(assetPath: String): ByteBuffer? {
        return try {
            val bytes = context.assets.open(assetPath).readBytes()
            Log.d(TAG, "Loaded: $assetPath (${bytes.size} bytes)")
            ByteBuffer.wrap(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading: $assetPath", e)
            null
        }
    }

    suspend fun preloadAssets(assetPaths: List<String>) {
        assetPaths.forEach { loadAsset(it) }
    }

    fun clear() {
        cache.evictAll()
    }
}
