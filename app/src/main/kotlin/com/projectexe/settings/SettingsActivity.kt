package com.projectexe.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
import com.projectexe.util.UserPreferenceManager.Role

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: UserPreferenceManager
    private lateinit var personaBlock: View
    private lateinit var factualBlock: View

    /** Currently-active role for the GGUF picker (set just before launching). */
    private var pickingFor: Role = Role.PERSONA

    private val pickGguf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "model.gguf"
            prefs.setGguf(pickingFor, uri.toString(), name)
            blockFor(pickingFor).findViewById<TextView>(R.id.role_block_gguf_status).text = name
        }
    }

    private val pickCard = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "card.json"
            prefs.characterCardUri  = uri.toString()
            prefs.characterCardName = name
            findViewById<TextView>(R.id.text_card_status).text = name
        }
    }

    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { _ -> rebuildPermsList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = (application as ProjectEXEApplication).userPrefs

        personaBlock = findViewById(R.id.persona_block)
        factualBlock = findViewById(R.id.factual_block)

        // Pipeline / tools toggles
        findViewById<SwitchMaterial>(R.id.switch_dual).isChecked  = prefs.useDualPipeline
        findViewById<SwitchMaterial>(R.id.switch_tools).isChecked = prefs.toolsEnabled

        // Character card
        val cardStatus = findViewById<TextView>(R.id.text_card_status)
        cardStatus.text = if (prefs.characterCardUri.isNotEmpty())
            prefs.characterCardName.ifEmpty { "Custom card" }
        else getString(R.string.settings_card_none)
        findViewById<Button>(R.id.btn_pick_card).setOnClickListener {
            try { pickCard.launch(arrayOf("application/json", "*/*")) }
            catch (e: Exception) { Toast.makeText(this, "No file picker available.", Toast.LENGTH_SHORT).show() }
        }
        findViewById<Button>(R.id.btn_clear_card).setOnClickListener {
            prefs.characterCardUri = ""; prefs.characterCardName = ""
            cardStatus.text = getString(R.string.settings_card_none)
        }

        // Shared API key
        findViewById<TextInputEditText>(R.id.edit_api_key).setText(prefs.apiKeyOverride)

        // Per-role blocks
        bindRoleBlock(personaBlock, Role.PERSONA)
        bindRoleBlock(factualBlock, Role.FACTUAL)

        // Permissions
        rebuildPermsList()

        // User
        findViewById<TextInputEditText>(R.id.edit_user_name).setText(prefs.userName)
        val rg = findViewById<RadioGroup>(R.id.group_resp_len)
        when (prefs.responseLengthPref) {
            UserPreferenceManager.LEN_CONCISE  -> rg.check(R.id.rb_len_concise)
            UserPreferenceManager.LEN_DETAILED -> rg.check(R.id.rb_len_detailed)
            else                               -> rg.check(R.id.rb_len_balanced)
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun blockFor(role: Role) = if (role == Role.PERSONA) personaBlock else factualBlock

    private fun bindRoleBlock(root: View, role: Role) {
        // Mode
        val rg = root.findViewById<RadioGroup>(R.id.role_block_mode)
        when (prefs.engineMode(role)) {
            UserPreferenceManager.MODE_ONLINE  -> rg.check(R.id.role_block_mode_online)
            UserPreferenceManager.MODE_OFFLINE -> rg.check(R.id.role_block_mode_offline)
            else                               -> rg.check(R.id.role_block_mode_auto)
        }
        // Model id
        root.findViewById<TextInputEditText>(R.id.role_block_model).setText(prefs.modelOverride(role))
        // GGUF picker
        val ggufStatus = root.findViewById<TextView>(R.id.role_block_gguf_status)
        ggufStatus.text = if (prefs.ggufUri(role).isNotEmpty())
            prefs.ggufName(role).ifEmpty { "Model selected" }
        else getString(R.string.settings_role_gguf_none)
        root.findViewById<Button>(R.id.role_block_pick_gguf).setOnClickListener {
            pickingFor = role
            try { pickGguf.launch(arrayOf("*/*")) }
            catch (_: Exception) { Toast.makeText(this, "No file picker available.", Toast.LENGTH_SHORT).show() }
        }
        // Context window
        val seek = root.findViewById<SeekBar>(R.id.role_block_seek_ctx)
        val ctxText = root.findViewById<TextView>(R.id.role_block_ctx_value)
        seek.progress = (prefs.ggufContextSize(role) - 512).coerceAtLeast(0)
        ctxText.text  = prefs.ggufContextSize(role).toString()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                val v = ((value + 512) / 256) * 256
                ctxText.text = v.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun saveRoleBlock(root: View, role: Role) {
        val mode = when (root.findViewById<RadioGroup>(R.id.role_block_mode).checkedRadioButtonId) {
            R.id.role_block_mode_online  -> UserPreferenceManager.MODE_ONLINE
            R.id.role_block_mode_offline -> UserPreferenceManager.MODE_OFFLINE
            else                         -> UserPreferenceManager.MODE_AUTO
        }
        prefs.setEngineMode(role, mode)
        prefs.setModelOverride(role, root.findViewById<TextInputEditText>(R.id.role_block_model).text?.toString().orEmpty())
        val ctx = root.findViewById<TextView>(R.id.role_block_ctx_value).text.toString().toIntOrNull() ?: 2048
        prefs.setGgufContextSize(role, ctx)
    }

    private fun save() {
        prefs.useDualPipeline = findViewById<SwitchMaterial>(R.id.switch_dual).isChecked
        prefs.toolsEnabled    = findViewById<SwitchMaterial>(R.id.switch_tools).isChecked
        prefs.apiKeyOverride  = findViewById<TextInputEditText>(R.id.edit_api_key).text?.toString().orEmpty()

        saveRoleBlock(personaBlock, Role.PERSONA)
        saveRoleBlock(factualBlock, Role.FACTUAL)

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
