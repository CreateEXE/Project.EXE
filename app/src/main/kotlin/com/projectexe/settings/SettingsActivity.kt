package com.projectexe.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.projectexe.ProjectEXEApplication
import com.projectexe.R
import com.projectexe.util.UserPreferenceManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: UserPreferenceManager

    private val pickGguf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            prefs.ggufUri = uri.toString()
            prefs.ggufName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "model.gguf"
            findViewById<TextView>(R.id.text_gguf_status).text = prefs.ggufName
        }
    }

    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { _ -> rebuildPermsList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = (application as ProjectEXEApplication).userPrefs

        // Engine mode
        val rg = findViewById<RadioGroup>(R.id.group_engine_mode)
        when (prefs.engineMode) {
            UserPreferenceManager.MODE_ONLINE  -> rg.check(R.id.rb_mode_online)
            UserPreferenceManager.MODE_OFFLINE -> rg.check(R.id.rb_mode_offline)
            else                               -> rg.check(R.id.rb_mode_auto)
        }

        // Online
        findViewById<TextInputEditText>(R.id.edit_api_key).setText(prefs.apiKeyOverride)
        findViewById<TextInputEditText>(R.id.edit_model).setText(prefs.modelOverride)
        findViewById<SwitchMaterial>(R.id.switch_tools).isChecked = prefs.toolsEnabled

        // Offline
        val ggufStatus = findViewById<TextView>(R.id.text_gguf_status)
        ggufStatus.text = if (prefs.ggufUri.isNotEmpty()) prefs.ggufName.ifEmpty { "Model selected" }
                          else getString(R.string.settings_gguf_none)
        findViewById<Button>(R.id.btn_pick_gguf).setOnClickListener {
            try { pickGguf.launch(arrayOf("*/*")) }
            catch (e: Exception) { Toast.makeText(this, "No file picker available.", Toast.LENGTH_SHORT).show() }
        }
        val seek = findViewById<SeekBar>(R.id.seek_ctx)
        val ctxText = findViewById<TextView>(R.id.text_ctx_value)
        seek.progress = (prefs.ggufContextSize - 512).coerceAtLeast(0)
        ctxText.text = prefs.ggufContextSize.toString()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                val v = ((value + 512) / 256) * 256   // snap to multiples of 256
                ctxText.text = v.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Permissions
        rebuildPermsList()

        // User
        findViewById<TextInputEditText>(R.id.edit_user_name).setText(prefs.userName)
        val rg2 = findViewById<RadioGroup>(R.id.group_resp_len)
        when (prefs.responseLengthPref) {
            UserPreferenceManager.LEN_CONCISE  -> rg2.check(R.id.rb_len_concise)
            UserPreferenceManager.LEN_DETAILED -> rg2.check(R.id.rb_len_detailed)
            else                               -> rg2.check(R.id.rb_len_balanced)
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun save() {
        prefs.engineMode = when (findViewById<RadioGroup>(R.id.group_engine_mode).checkedRadioButtonId) {
            R.id.rb_mode_online  -> UserPreferenceManager.MODE_ONLINE
            R.id.rb_mode_offline -> UserPreferenceManager.MODE_OFFLINE
            else                 -> UserPreferenceManager.MODE_AUTO
        }
        prefs.apiKeyOverride = findViewById<TextInputEditText>(R.id.edit_api_key).text?.toString().orEmpty()
        prefs.modelOverride  = findViewById<TextInputEditText>(R.id.edit_model).text?.toString().orEmpty()
        prefs.toolsEnabled   = findViewById<SwitchMaterial>(R.id.switch_tools).isChecked
        prefs.ggufContextSize = findViewById<TextView>(R.id.text_ctx_value).text.toString().toIntOrNull() ?: 2048
        prefs.userName        = findViewById<TextInputEditText>(R.id.edit_user_name).text?.toString().orEmpty()
        prefs.responseLengthPref = when (findViewById<RadioGroup>(R.id.group_resp_len).checkedRadioButtonId) {
            R.id.rb_len_concise  -> UserPreferenceManager.LEN_CONCISE
            R.id.rb_len_detailed -> UserPreferenceManager.LEN_DETAILED
            else                 -> UserPreferenceManager.LEN_BALANCED
        }
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun rebuildPermsList() {
        val container = findViewById<LinearLayout>(R.id.perms_container)
        container.removeAllViews()
        addPermRow(container, getString(R.string.settings_perm_notifications),
            granted = if (Build.VERSION.SDK_INT >= 33)
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true) {
            if (Build.VERSION.SDK_INT >= 33) requestPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        addPermRow(container, getString(R.string.settings_perm_calendar),
            granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            requestPerm.launch(Manifest.permission.READ_CALENDAR)
        }
        addPermRow(container, getString(R.string.settings_perm_location),
            granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        addPermRow(container, getString(R.string.settings_perm_write_settings),
            granted = Settings.System.canWrite(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "Can't open settings page.", Toast.LENGTH_SHORT).show()
            }
        }
        addPermRow(container, getString(R.string.settings_perm_alarms),
            granted = if (Build.VERSION.SDK_INT >= 31) {
                val am = getSystemService(AlarmManager::class.java); am?.canScheduleExactAlarms() == true
            } else true) {
            if (Build.VERSION.SDK_INT >= 31) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"))) }
                catch (_: Exception) {}
            }
        }
    }

    private fun addPermRow(parent: LinearLayout, label: String, granted: Boolean, onGrant: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 12, 0, 12)
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(this).apply {
            text = if (granted) getString(R.string.settings_perm_granted)
                   else getString(R.string.settings_perm_request)
            isEnabled = !granted
            setOnClickListener { onGrant() }
        }
        row.addView(tv); row.addView(btn); parent.addView(row)
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }
}
