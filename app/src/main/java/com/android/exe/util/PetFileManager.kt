package com.android.exe.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PetFileManager {

    private const val TAG = "PetFileManager"

    /** Copy a content:// or file:// Uri into app-private files and return the path. */
    suspend fun importFile(context: Context, uri: Uri, destFileName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val dest = File(context.filesDir, destFileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Imported $uri → ${dest.absolutePath} (${dest.length()} bytes)")
                dest.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "importFile failed", e)
                null
            }
        }

    fun getAvatarFile(context: Context): File = File(context.filesDir, "avatar.vrm")
    fun getModelFile(context: Context): File  = File(context.filesDir, "model.gguf")
}
