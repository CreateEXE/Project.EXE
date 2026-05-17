package com.android.exe

import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.android.exe.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"
    
    private lateinit var avatarFilePicker: ActivityResultLauncher<Intent>
    private lateinit var modelFilePicker: ActivityResultLauncher<Intent>
    
    private var selectedAvatarUri: String = ""
    private var selectedModelUri: String = ""
    private var petName: String = "Fluffy"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            Log.d(TAG, "MainActivity created")
            binding.textView.text = "Android.EXE Ready"
            
            loadSavedDefaults()
            setupFilePickers()
            setupListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            e.printStackTrace()
        }
    }

    private fun loadSavedDefaults() {
        val defaultAvatarUri = PreferencesManager.getDefaultAvatarUri(this)
        if (defaultAvatarUri != null) {
            selectedAvatarUri = defaultAvatarUri
            binding.tvAvatarPath.text = PreferencesManager.getDefaultAvatarName(this)
            Log.d(TAG, "Loaded default avatar: $selectedAvatarUri")
        }

        val defaultModelUri = PreferencesManager.getDefaultModelUri(this)
        if (defaultModelUri != null) {
            selectedModelUri = defaultModelUri
            binding.tvModelPath.text = PreferencesManager.getDefaultModelName(this)
            Log.d(TAG, "Loaded default model: $selectedModelUri")
        }

        petName = PreferencesManager.getPetName(this)
        binding.tvPetName.text = petName
    }

    private fun setupFilePickers() {
        avatarFilePicker = FilePickerUtils.createAvatarFilePicker(this) { uri ->
            if (uri != null) {
                selectedAvatarUri = uri.toString()
                val fileName = FilePickerUtils.getUriFileName(this, uri)
                binding.tvAvatarPath.text = fileName
                PreferencesManager.saveDefaultAvatar(this, selectedAvatarUri, fileName)
                Log.d(TAG, "Avatar selected and saved: $fileName")
            }
        }

        modelFilePicker = FilePickerUtils.createModelFilePicker(this) { uri ->
            if (uri != null) {
                selectedModelUri = uri.toString()
                val fileName = FilePickerUtils.getUriFileName(this, uri)
                binding.tvModelPath.text = fileName
                PreferencesManager.saveDefaultModel(this, selectedModelUri, fileName)
                Log.d(TAG, "Model selected and saved: $fileName")
            }
        }
    }

    private fun setupListeners() {
        try {
            binding.btnStartStop.setOnClickListener {
                Log.d(TAG, "Start/Stop clicked")
                if (selectedAvatarUri.isEmpty() || selectedModelUri.isEmpty()) {
                    binding.textView.text = "Please select avatar and model first"
                    return@setOnClickListener
                }
                
                // Check for SYSTEM_ALERT_WINDOW permission before starting service
                checkAndRequestOverlayPermission()
            }

            binding.btnPickAvatar.setOnClickListener {
                Log.d(TAG, "Pick avatar clicked")
                try {
                    avatarFilePicker.launch(FilePickerUtils.getAvatarPickerIntent())
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching avatar picker", e)
                    binding.tvAvatarPath.text = "Error opening file picker"
                }
            }

            binding.btnPickModel.setOnClickListener {
                Log.d(TAG, "Pick model clicked")
                try {
                    modelFilePicker.launch(FilePickerUtils.getModelPickerIntent())
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching model picker", e)
                    binding.tvModelPath.text = "Error opening file picker"
                }
            }

            binding.btnAccessibility.setOnClickListener {
                Log.d(TAG, "Accessibility settings clicked")
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open accessibility settings", e)
                }
            }

            binding.btnPetName.setOnClickListener {
                Log.d(TAG, "Edit pet name clicked")
                PetNameDialog.show(this, petName) { newName ->
                    petName = newName
                    binding.tvPetName.text = newName
                    PreferencesManager.savePetName(this, newName)
                    Log.d(TAG, "Pet name changed to: $newName")
                }
            }

            binding.textView.setOnLongClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up listeners", e)
        }
    }

    private fun checkAndRequestOverlayPermission() {
        Log.d(TAG, "Checking SYSTEM_ALERT_WINDOW permission...")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Android 6.0+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SYSTEM_ALERT_WINDOW)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "SYSTEM_ALERT_WINDOW permission already granted")
                startPetServiceNow()
            } else {
                Log.d(TAG, "SYSTEM_ALERT_WINDOW permission not granted, requesting...")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                binding.textView.text = "Please enable 'Display over other apps' in settings, then click START PET again"
            }
        } else {
            // Android 5.1 and below - permission is automatically granted
            Log.d(TAG, "Android 5.1 or below - starting service directly")
            startPetServiceNow()
        }
    }

    private fun startPetServiceNow() {
        Log.d(TAG, "Starting pet service")
        binding.textView.text = "Pet service started!"
        binding.tvOverlayStatus.text = "Status: Running"
        
        try {
            val serviceIntent = Intent(this, PetForegroundService::class.java).apply {
                action = PetForegroundService.ACTION_START
                putExtra(PetForegroundService.EXTRA_AVATAR_URI, selectedAvatarUri)
                putExtra(PetForegroundService.EXTRA_MODEL_URI, selectedModelUri)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            binding.textView.text = "Pet is running!\nAvatar: ${binding.tvAvatarPath.text}\nModel: ${binding.tvModelPath.text}"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pet service", e)
            binding.textView.text = "Error: ${e.message}"
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        super.onDestroy()
    }
}
