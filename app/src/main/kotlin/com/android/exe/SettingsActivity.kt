package com.android.exe

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"
    
    private lateinit var tvCurrentAvatar: TextView
    private lateinit var tvCurrentModel: TextView
    private lateinit var btnChangeAvatar: Button
    private lateinit var btnChangeModel: Button
    private lateinit var btnClearAvatar: Button
    private lateinit var btnClearModel: Button
    private lateinit var btnBack: Button
    
    private lateinit var avatarFilePicker: ActivityResultLauncher<Intent>
    private lateinit var modelFilePicker: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        initViews()
        setupFilePickers()
        updateDisplays()
        setupListeners()
    }

    private fun initViews() {
        tvCurrentAvatar = findViewById(R.id.tvCurrentAvatar)
        tvCurrentModel = findViewById(R.id.tvCurrentModel)
        btnChangeAvatar = findViewById(R.id.btnChangeAvatar)
        btnChangeModel = findViewById(R.id.btnChangeModel)
        btnClearAvatar = findViewById(R.id.btnClearAvatar)
        btnClearModel = findViewById(R.id.btnClearModel)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun setupFilePickers() {
        avatarFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val fileName = getFileName(uri)
                    PreferencesManager.saveDefaultAvatar(this, uri.toString(), fileName)
                    updateDisplays()
                    Log.d(TAG, "Avatar saved as default: $fileName")
                }
            }
        }

        modelFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val fileName = getFileName(uri)
                    PreferencesManager.saveDefaultModel(this, uri.toString(), fileName)
                    updateDisplays()
                    Log.d(TAG, "Model saved as default: $fileName")
                }
            }
        }
    }

    private fun setupListeners() {
        btnChangeAvatar.setOnClickListener {
            Log.d(TAG, "Change avatar clicked")
            avatarFilePicker.launch(FilePickerUtils.getAvatarPickerIntent())
        }

        btnChangeModel.setOnClickListener {
            Log.d(TAG, "Change model clicked")
            modelFilePicker.launch(FilePickerUtils.getModelPickerIntent())
        }

        btnClearAvatar.setOnClickListener {
            Log.d(TAG, "Clear avatar clicked")
            PreferencesManager.clearDefaultAvatar(this)
            updateDisplays()
        }

        btnClearModel.setOnClickListener {
            Log.d(TAG, "Clear model clicked")
            PreferencesManager.clearDefaultModel(this)
            updateDisplays()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun updateDisplays() {
        tvCurrentAvatar.text = "Current: ${PreferencesManager.getDefaultAvatarName(this)}"
        tvCurrentModel.text = "Current: ${PreferencesManager.getDefaultModelName(this)}"
    }

    private fun getFileName(uri: Uri): String {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
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
