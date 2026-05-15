package com.android.exe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

object FilePickerUtils {

    fun createAvatarFilePicker(activity: AppCompatActivity, callback: (Uri?) -> Unit): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                callback(uri)
            } else {
                callback(null)
            }
        }
    }

    fun createModelFilePicker(activity: AppCompatActivity, callback: (Uri?) -> Unit): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                callback(uri)
            } else {
                callback(null)
            }
        }
    }

    fun getAvatarPickerIntent(): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "model/gltf+json",
                "model/gltf-binary",
                "application/octet-stream",
                "application/x-gltf+json",
                "application/x-gltf-binary"
            ))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
    }

    fun getModelPickerIntent(): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/x-gguf"
            ))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
    }

    fun getUriFileName(activity: Activity, uri: Uri): String {
        return try {
            val cursor = activity.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst()
                it.getString(nameIndex)
            } ?: "Unknown file"
        } catch (e: Exception) {
            "Unknown file"
        }
    }
}
