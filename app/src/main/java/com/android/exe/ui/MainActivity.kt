package com.android.exe.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.exe.R
import com.android.exe.data.PetDatabase
import com.android.exe.data.entities.PetProfile
import com.android.exe.databinding.ActivityMainBinding
import com.android.exe.service.PetForegroundService
import com.android.exe.util.PetFileManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { PetDatabase.getInstance(this) }

    // ── File pickers ──────────────────────────────────────────────────────────

    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { handleAvatarPicked(uri) }
    }

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { handleModelPicked(uri) }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
        if (Settings.canDrawOverlays(this)) {
            startPetService()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        ensureDefaultProfile()
        observeProfile()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnPickAvatar.setOnClickListener {
            pickAvatar.launch("*/*")   // accept vrm, glb, gltf
        }

        binding.btnPickModel.setOnClickListener {
            pickModel.launch("*/*")    // .gguf
        }

        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning()) {
                stopPetService()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else {
                    startPetService()
                }
            }
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnPetName.setOnClickListener {
            val dialog = PetNameDialog()
            dialog.show(supportFragmentManager, "pet_name")
        }
    }

    private fun ensureDefaultProfile() {
        lifecycleScope.launch {
            if (db.petProfileDao().getActive() == null) {
                db.petProfileDao().insert(PetProfile(petName = "Exe"))
            }
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            db.petProfileDao().observeActive().collectLatest { profile ->
                profile ?: return@collectLatest
                binding.tvPetName.text    = profile.petName
                binding.tvAvatarPath.text = profile.avatarPath ?: "No avatar loaded"
                binding.tvModelPath.text  = profile.llmModelPath ?: "No model loaded"
            }
        }
    }

    // ── Permission & status ────────────────────────────────────────────────────

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text   = if (hasOverlay) "✓ Overlay permission granted" else "✗ Overlay permission needed"
        binding.btnStartStop.text      = if (isServiceRunning()) "Stop Pet" else "Start Pet"
        binding.btnStartStop.isEnabled = hasOverlay
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(intent)
    }

    // ── Service control ────────────────────────────────────────────────────────

    private fun startPetService() {
        val intent = Intent(this, PetForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        refreshStatus()
    }

    private fun stopPetService() {
        val intent = Intent(this, PetForegroundService::class.java).apply {
            action = PetForegroundService.ACTION_STOP
        }
        startService(intent)
        refreshStatus()
    }

    private fun isServiceRunning(): Boolean {
        // Simple heuristic: check if overlay permission is granted and service intent exists.
        // A proper check would use ActivityManager but is deprecated; foreground notification
        // presence is the canonical indicator.
        return false  // TODO: track via bound service or shared pref
    }

    // ── File handling ──────────────────────────────────────────────────────────

    private suspend fun handleAvatarPicked(uri: Uri) {
        binding.tvAvatarPath.text = "Copying…"
        val path = PetFileManager.importFile(this, uri, "avatar.vrm")
        if (path == null) {
            Toast.makeText(this, "Failed to copy avatar file", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = db.petProfileDao().getActive() ?: return
        db.petProfileDao().setAvatarPath(profile.id, path)

        // Signal running service to reload the avatar
        startService(Intent(this, PetForegroundService::class.java).apply {
            action = PetForegroundService.ACTION_RELOAD
        })

        Toast.makeText(this, "Avatar loaded!", Toast.LENGTH_SHORT).show()
    }

    private suspend fun handleModelPicked(uri: Uri) {
        binding.tvModelPath.text = "Copying… (this may take a while for large models)"
        val path = PetFileManager.importFile(this, uri, "model.gguf")
        if (path == null) {
            Toast.makeText(this, "Failed to copy model file", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = db.petProfileDao().getActive() ?: return
        db.petProfileDao().setModelPath(profile.id, path)

        Toast.makeText(this, "Model saved. Restart the pet service to apply.", Toast.LENGTH_LONG).show()
    }
}
