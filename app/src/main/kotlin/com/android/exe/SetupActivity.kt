package com.android.exe

import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.Manifest
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.android.exe.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val TAG = "SetupActivity"
    
    private var overlayPermissionGranted = false
    private var notificationPermissionGranted = false
    
    private lateinit var avatarFilePicker: ActivityResultLauncher<Intent>
    private lateinit var modelFilePicker: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    
    private var selectedAvatarUri: String = ""
    private var selectedModelUri: String = ""
    private var tvSelectedAvatar: TextView? = null
    private var tvSelectedModel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        Log.d(TAG, "SetupActivity created")
        
        setupFilePickers()
        setupPermissionLaunchers()
        showPermissionsScreen()
    }

    private fun setupFilePickers() {
        avatarFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    selectedAvatarUri = uri.toString()
                    val fileName = FilePickerUtils.getUriFileName(this, uri)
                    tvSelectedAvatar?.text = "Selected: $fileName"
                    PreferencesManager.saveDefaultAvatar(this, selectedAvatarUri, fileName)
                    Log.d(TAG, "Avatar selected: $fileName")
                }
            }
        }

        modelFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    selectedModelUri = uri.toString()
                    val fileName = FilePickerUtils.getUriFileName(this, uri)
                    tvSelectedModel?.text = "Selected: $fileName"
                    PreferencesManager.saveDefaultModel(this, selectedModelUri, fileName)
                    Log.d(TAG, "Model selected: $fileName")
                }
            }
        }
    }

    private fun setupPermissionLaunchers() {
        overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "Returned from overlay settings")
            showPermissionsScreen()
        }

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Notification permission result: $granted")
            showPermissionsScreen()
        }
    }

    private fun showPermissionsScreen() {
        Log.d(TAG, "Showing permissions screen")
        binding.screenContainer.removeAllViews()
        
        val layout = layoutInflater.inflate(R.layout.setup_screen_permissions, binding.screenContainer, false)
        binding.screenContainer.addView(layout)
        
        val tvOverlayStatus = layout.findViewById<TextView>(R.id.tvOverlayStatus)
        val tvNotificationStatus = layout.findViewById<TextView>(R.id.tvNotificationStatus)
        val btnRequestOverlay = layout.findViewById<Button>(R.id.btnRequestOverlay)
        val btnRequestNotification = layout.findViewById<Button>(R.id.btnRequestNotification)
        val btnNext = layout.findViewById<Button>(R.id.btnNext)
        
        updatePermissionStatus(tvOverlayStatus, tvNotificationStatus)
        
        btnRequestOverlay.setOnClickListener {
            requestOverlayPermission()
        }
        
        btnRequestNotification.setOnClickListener {
            requestNotificationPermission()
        }
        
        btnNext.setOnClickListener {
            if (overlayPermissionGranted) {
                showConfigurationScreen()
            } else {
                binding.tvError.text = "Please enable all required permissions first"
            }
        }
    }

    private fun updatePermissionStatus(tvOverlay: TextView, tvNotification: TextView) {
        overlayPermissionGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.SYSTEM_ALERT_WINDOW) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        notificationPermissionGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        tvOverlay.text = if (overlayPermissionGranted) "✓ Display over other apps: ENABLED" else "✗ Display over other apps: DISABLED"
        tvOverlay.setTextColor(if (overlayPermissionGranted) android.graphics.Color.GREEN else android.graphics.Color.RED)
        
        tvNotification.text = if (notificationPermissionGranted) "✓ Notifications: ENABLED" else "✗ Notifications: DISABLED"
        tvNotification.setTextColor(if (notificationPermissionGranted) android.graphics.Color.GREEN else android.graphics.Color.RED)
    }

    private fun requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showConfigurationScreen() {
        Log.d(TAG, "Showing configuration screen")
        binding.screenContainer.removeAllViews()
        
        val layout = layoutInflater.inflate(R.layout.setup_screen_config, binding.screenContainer, false)
        binding.screenContainer.addView(layout)
        
        tvSelectedAvatar = layout.findViewById(R.id.tvSelectedAvatar)
        tvSelectedModel = layout.findViewById(R.id.tvSelectedModel)
        val btnPickAvatar = layout.findViewById<Button>(R.id.btnPickAvatar)
        val btnPickModel = layout.findViewById<Button>(R.id.btnPickModel)
        val btnFinish = layout.findViewById<Button>(R.id.btnFinish)
        
        btnPickAvatar.setOnClickListener {
            avatarFilePicker.launch(FilePickerUtils.getAvatarPickerIntent())
        }
        
        btnPickModel.setOnClickListener {
            modelFilePicker.launch(FilePickerUtils.getModelPickerIntent())
        }
        
        btnFinish.setOnClickListener {
            if (selectedAvatarUri.isEmpty() || selectedModelUri.isEmpty()) {
                binding.tvError.text = "Please select both avatar and model"
            } else {
                completeSetup()
            }
        }
    }

    private fun completeSetup() {
        Log.d(TAG, "Setup complete, starting MainActivity")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
