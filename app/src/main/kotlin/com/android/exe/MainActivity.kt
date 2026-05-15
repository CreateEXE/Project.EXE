package com.android.exe

import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
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
            // Initialize view binding
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            Log.d(TAG, "MainActivity created successfully")
            
            // Set initial text
            if (::binding.isInitialized) {
                binding.textView.text = "Android.EXE Ready"
                binding.tvPetName.text = petName
            }
            
            // Register file pickers
            setupFilePickers()
            
            // Initialize UI listeners
            setupListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            e.printStackTrace()
        }
    }

    private fun setupFilePickers() {
        // Avatar file picker
        avatarFilePicker = FilePickerUtils.createAvatarFilePicker(this) { uri ->
            if (uri != null) {
                selectedAvatarUri = uri.toString()
                val fileName = FilePickerUtils.getUriFileName(this, uri)
                binding.tvAvatarPath.text = fileName
                Log.d(TAG, "Avatar selected: $fileName")
            }
        }

        // Model file picker
        modelFilePicker = FilePickerUtils.createModelFilePicker(this) { uri ->
            if (uri != null) {
                selectedModelUri = uri.toString()
                val fileName = FilePickerUtils.getUriFileName(this, uri)
                binding.tvModelPath.text = fileName
                Log.d(TAG, "Model selected: $fileName")
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
                binding.textView.text = "Pet service started!"
                binding.tvOverlayStatus.text = "Status: Running"
                startPetService()
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
                    Log.d(TAG, "Pet name changed to: $newName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up listeners", e)
        }
    }

    private fun startPetService() {
        try {
            Log.d(TAG, "Starting pet service with avatar=$selectedAvatarUri, model=$selectedModelUri")
            
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
