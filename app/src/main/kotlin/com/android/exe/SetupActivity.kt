package com.android.exe

import android.os.Build
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
    private var accessibilityPermissionGranted = false
    
    private lateinit var avatarFilePicker: ActivityResultLauncher<Intent>
    private lateinit var modelFilePicker: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    
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
                    tvSelectedAvatar?.text = "✓ $fileName"
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
                    tvSelectedModel?.text = "✓ $fileName"
                    PreferencesManager.saveDefaultModel(this, selectedModelUri, fileName)
                    Log.d(TAG, "Model selected: $fileName")
                }
            }
        }
    }

    private fun setupPermissionLaunchers() {
        overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "Returned from overlay settings")
            Thread.sleep(300)
            showPermissionsScreen()
        }

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Notification permission result: $granted")
            showPermissionsScreen()
        }

        accessibilitySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "Returned from accessibility settings")
            Thread.sleep(500)
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
        val tvAccessibilityStatus = layout.findViewById<TextView>(R.id.tvAccessibilityStatus)
        val btnRequestOverlay = layout.findViewById<Button>(R.id.btnRequestOverlay)
        val btnRequestNotification = layout.findViewById<Button>(R.id.btnRequestNotification)
        val btnRequestAccessibility = layout.findViewById<Button>(R.id.btnRequestAccessibility)
        val btnNext = layout.findViewById<Button>(R.id.btnNext)
        
        updatePermissionStatus(tvOverlayStatus, tvNotificationStatus, tvAccessibilityStatus)
        
        btnRequestOverlay.setOnClickListener {
            Log.d(TAG, "Requesting overlay permission...")
            requestOverlayPermission()
        }
        
        btnRequestNotification.setOnClickListener {
            Log.d(TAG, "Requesting notification permission...")
            requestNotificationPermission()
        }

        btnRequestAccessibility.setOnClickListener {
            Log.d(TAG, "Requesting accessibility permission...")
            requestAccessibilityPermission()
        }
        
        btnNext.setOnClickListener {
            Log.d(TAG, "Next clicked - overlay: $overlayPermissionGranted")
            if (overlayPermissionGranted) {
                showConfigurationScreen()
            } else {
                binding.tvError.text = "Please enable 'Display over other apps' to continue"
            }
        }
    }

    private fun updatePermissionStatus(tvOverlay: TextView, tvNotification: TextView, tvAccessibility: TextView) {
        overlayPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        
        notificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        accessibilityPermissionGranted = isAccessibilityServiceEnabled()
        
        Log.d(TAG, "Overlay: $overlayPermissionGranted, Notification: $notificationPermissionGranted, Accessibility: $accessibilityPermissionGranted")
        
        tvOverlay.text = if (overlayPermissionGranted) "✓ Display over other apps: ENABLED" else "✗ Display over other apps: DISABLED"
        tvOverlay.setTextColor(if (overlayPermissionGranted) android.graphics.Color.GREEN else android.graphics.Color.RED)
        
        tvNotification.text = if (notificationPermissionGranted) "✓ Notifications: ENABLED" else "✗ Notifications: DISABLED"
        tvNotification.setTextColor(if (notificationPermissionGranted) android.graphics.Color.GREEN else android.graphics.Color.RED)

        tvAccessibility.text = if (accessibilityPermissionGranted) "✓ Accessibility Service: ENABLED" else "✗ Accessibility Service: DISABLED"
        tvAccessibility.setTextColor(if (accessibilityPermissionGranted) android.graphics.Color.GREEN else android.graphics.Color.RED)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            Log.d(TAG, "Enabled accessibility services: $enabledServices")
            
            // Check if our accessibility service is in the list
            val serviceComponentName = "com.android.exe/com.android.exe.AccessibilityService"
            val hasService = enabledServices.contains(serviceComponentName, ignoreCase = false)
            
            Log.d(TAG, "Checking for: $serviceComponentName, Found: $hasService")
            
            hasService
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service", e)
            false
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
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
        val btnStart = layout.findViewById<Button>(R.id.btnStart)
        
        btnPickAvatar.setOnClickListener {
            avatarFilePicker.launch(FilePickerUtils.getAvatarPickerIntent())
        }
        
        btnPickModel.setOnClickListener {
            modelFilePicker.launch(FilePickerUtils.getModelPickerIntent())
        }
        
        btnStart.setOnClickListener {
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
