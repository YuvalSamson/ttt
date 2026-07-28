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
        b.address.setText(prefs.getString("address", ""))

        requestPerms()

        b.connectBtn.setOnClickListener {
            val addr = b.address.text.toString().trim()
            if (addr.isEmpty()) {
                b.status.text = getString(R.string.enter_address)
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                b.status.text = getString(R.string.need_mic)
                requestPerms()
                return@setOnClickListener
            }
            prefs.edit().putString("address", addr).apply()
            val i = Intent(this, TttService::class.java).apply {
                action = TttService.ACTION_START
                putExtra(TttService.EXTRA_ADDRESS, addr)
            }
            ContextCompat.startForegroundService(this, i)
            b.status.text = getString(R.string.connecting)
        }

        b.stopBtn.setOnClickListener {
            val i = Intent(this, TttService::class.java).apply { action = TttService.ACTION_STOP }
            startService(i)
            b.status.text = getString(R.string.stopped)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TttService.ACTION_STATUS)
        ContextCompat.registerReceiver(
            this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
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
