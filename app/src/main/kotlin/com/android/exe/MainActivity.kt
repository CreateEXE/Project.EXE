package com.android.exe

import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.android.exe.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

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
            }
            
            // Initialize UI listeners
            setupListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        try {
            binding.btnStartStop.setOnClickListener {
                Log.d(TAG, "Start/Stop clicked")
                binding.textView.text = "Pet service started!"
                binding.tvOverlayStatus.text = "Status: Running"
                startPetService()
            }

            binding.btnPickAvatar.setOnClickListener {
                Log.d(TAG, "Pick avatar clicked")
                binding.tvAvatarPath.text = "Avatar selection not yet implemented"
            }

            binding.btnPickModel.setOnClickListener {
                Log.d(TAG, "Pick model clicked")
                binding.tvModelPath.text = "Model selection not yet implemented"
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
                binding.tvPetName.text = "Pet Name Editor (not implemented)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up listeners", e)
        }
    }

    private fun startPetService() {
        try {
            // TODO: Start PetForegroundService or overlay manager
            Log.d(TAG, "Pet service would be started here")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pet service", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        super.onDestroy()
    }
}
