package com.yuvalsamson.ttt

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yuvalsamson.ttt.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val defaultAddress = "192.168.7.36:8765"

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = intent?.getStringExtra(TttService.EXTRA_STATUS) ?: return
            b.status.text = s
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = getSharedPreferences("ttt", Context.MODE_PRIVATE)
        b.address.setText(prefs.getString("address", defaultAddress))

        requestPerms()

        b.connectBtn.setOnClickListener { startConnection() }

        b.stopBtn.setOnClickListener {
            val i = Intent(this, TttService::class.java).apply { action = TttService.ACTION_STOP }
            startService(i)
            b.status.text = getString(R.string.stopped)
        }

        // Auto-connect on open: if we already have an address and mic permission,
        // start the background service immediately - no button press needed.
        if (hasMic() && b.address.text.toString().trim().isNotEmpty()) {
            startConnection()
        }
    }

    private fun startConnection() {
        val addr = b.address.text.toString().trim()
        if (addr.isEmpty()) {
            b.status.text = getString(R.string.enter_address)
            return
        }
        if (!hasMic()) {
            b.status.text = getString(R.string.need_mic)
            requestPerms()
            return
        }
        prefs.edit().putString("address", addr).apply()
        val i = Intent(this, TttService::class.java).apply {
            action = TttService.ACTION_START
            putExtra(TttService.EXTRA_ADDRESS, addr)
        }
        ContextCompat.startForegroundService(this, i)
        b.status.text = getString(R.string.connecting)
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TttService.ACTION_STATUS)
        ContextCompat.registerReceiver(
            this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Bringing the app to the foreground forces a fresh reconnect. This is what
        // makes it "just work" in the morning after the phone has been asleep.
        if (hasMic() && b.address.text.toString().trim().isNotEmpty()) {
            startConnection()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Once mic is granted, connect automatically.
        if (hasMic() && b.address.text.toString().trim().isNotEmpty()) {
            startConnection()
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toAsk = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toAsk.toTypedArray(), 1)
        }
    }
}
