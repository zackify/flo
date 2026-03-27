package com.flo.whisper.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.flo.whisper.R

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val OVERLAY_REQUEST_CODE = 101
    }

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var languageInput: EditText
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var enableAccessibilityButton: Button
    private lateinit var enableOverlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.host_input)
        portInput = findViewById(R.id.port_input)
        languageInput = findViewById(R.id.language_input)
        statusText = findViewById(R.id.status_text)
        saveButton = findViewById(R.id.save_button)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        enableOverlayButton = findViewById(R.id.enable_overlay_button)

        loadPrefs()

        saveButton.setOnClickListener { savePrefs() }

        enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        enableOverlayButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("flo_prefs", Context.MODE_PRIVATE)
        hostInput.setText(prefs.getString("host", "192.168.1.100"))
        portInput.setText(prefs.getInt("port", 10300).toString())
        languageInput.setText(prefs.getString("language", "en"))
    }

    private fun savePrefs() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 10300
        val language = languageInput.text.toString().trim().ifEmpty { "en" }

        getSharedPreferences("flo_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("host", host)
            putInt("port", port)
            putString("language", language)
            apply()
        }
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val checks = mutableListOf<String>()

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        checks.add(if (micOk) "✓ Microphone permission" else "✗ Microphone permission needed")

        val overlayOk = Settings.canDrawOverlays(this)
        checks.add(if (overlayOk) "✓ Overlay permission" else "✗ Overlay permission needed")

        val accessibilityOk = isAccessibilityServiceEnabled()
        checks.add(if (accessibilityOk) "✓ Accessibility service enabled" else "✗ Accessibility service not enabled")

        val allOk = micOk && overlayOk && accessibilityOk
        checks.add("")
        checks.add(if (allOk) "Ready! Open any app and tap a text field." else "Please enable all permissions above.")

        statusText.text = checks.joinToString("\n")

        enableAccessibilityButton.isEnabled = !accessibilityOk
        enableOverlayButton.isEnabled = !overlayOk
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == "com.flo.whisper.service.FloAccessibilityService"
        }
    }
}
