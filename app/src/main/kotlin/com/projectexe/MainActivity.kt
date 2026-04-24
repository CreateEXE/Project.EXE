package com.projectexe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.projectexe.overlay.OverlayService
import com.projectexe.settings.SettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var permBtn: Button
    private lateinit var launchBtn: Button
    private lateinit var settingsBtn: Button

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OverlayService.isRunning) { finish(); return }
        setContentView(R.layout.activity_main)
        statusText  = findViewById(R.id.text_status)
        permBtn     = findViewById(R.id.btn_grant_permission)
        launchBtn   = findViewById(R.id.btn_launch_overlay)
        settingsBtn = findViewById(R.id.btn_settings)
        permBtn.setOnClickListener     { requestPerm() }
        launchBtn.setOnClickListener   { launchAndFinish() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    override fun onResume() { super.onResume(); refreshUI() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu); return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshUI() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = getString(R.string.status_permission_granted)
            permBtn.visibility   = View.GONE
            launchBtn.visibility = View.VISIBLE
        } else {
            statusText.text = getString(R.string.status_permission_required)
            permBtn.visibility   = View.VISIBLE
            launchBtn.visibility = View.GONE
        }
    }

    private fun requestPerm() {
        try {
            permLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open permission settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchAndFinish() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show(); return
        }
        launchBtn.isEnabled = false
        statusText.text = getString(R.string.status_launching)
        try {
            startForegroundService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            launchBtn.isEnabled = true
            statusText.text = getString(R.string.status_error)
            return
        }
        lifecycleScope.launch { delay(800); finish() }
    }
}
