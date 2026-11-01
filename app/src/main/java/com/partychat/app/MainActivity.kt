package com.partychat.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * One button: turns the headset helper on/off.
 *
 * The helper forces your Bluetooth earbuds into "call" mode (their mic + audio) so the
 * official Xbox app's party chat uses them, then opens the Xbox app.
 */
class MainActivity : ComponentActivity() {

    private lateinit var button: Button
    private lateinit var status: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) startHelper()
            else Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
        }
        button = Button(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            isAllCaps = false
            minHeight = (120 * dp).toInt()
            setOnClickListener { toggle() }
        }
        val title = TextView(this).apply {
            text = "Party Chat"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101010.toInt())
            val pad = (32 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
            addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (40 * dp).toInt() })
        })

        PartyService.routeListener = { desc -> runOnUiThread { refresh(desc) } }
        PartyService.stopRequestedListener = { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        PartyService.routeListener = null
        PartyService.stopRequestedListener = null
        super.onDestroy()
    }

    private fun refresh(route: String? = null) {
        val on = PartyService.running
        button.text = if (on) "Stop headset helper" else "Start headset helper"
        button.background = GradientDrawable().apply {
            cornerRadius = 24 * resources.displayMetrics.density
            setColor(if (on) 0xFF8B1E1E.toInt() else 0xFF107C10.toInt())
        }
        status.text = when {
            on && route != null -> "On — audio: $route\nJoin your party in the Xbox app."
            on -> "On — join your party in the Xbox app."
            else -> "Connect your earbuds, then tap Start."
        }
    }

    private fun toggle() {
        if (PartyService.running) {
            PartyService.stop(this)
            button.postDelayed({ refresh() }, 300)
        } else {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += Manifest.permission.BLUETOOTH_CONNECT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
            val missing = wanted.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startHelper() else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startHelper() {
        try {
            PartyService.startHelper(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        refresh()
        val launch = packageManager.getLaunchIntentForPackage(XBOX_APP)
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "Xbox app not found — open it yourself", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val XBOX_APP = "com.microsoft.xboxone.smartglass"
    }
}
