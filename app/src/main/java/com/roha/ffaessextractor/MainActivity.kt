package com.roha.ffaessextractor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.roha.ffaessextractor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val keyStore = KeyStore()

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startService()
        else {
            binding.tvStatus.text = "VPN permission denied — still trying without it"
            startService() // force start even without VPN
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestBatteryExemption()
        requestNotificationPermission()
        loadSavedKeys()
        checkRootStatus()

        binding.btnStart.setOnClickListener {
            val pkg = binding.etPackage.text.toString().trim()
            val name = binding.etGameName.text.toString().trim().ifEmpty { "Game" }
            if (pkg.isEmpty()) {
                Toast.makeText(this, "Package name required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CryptoHookManager.targetPackage = pkg
            CryptoHookManager.targetName = name
            binding.tvStatus.text = "Preparing root + hooks..."
            prepareAndStart()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ExtractorVpnService::class.java))
            binding.tvStatus.text = "Stopped"
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }

        binding.btnClear.setOnClickListener {
            keyStore.clear()
            binding.tvKeys.text = "No keys yet"
        }

        binding.btnRefresh.setOnClickListener {
            loadSavedKeys()
            checkRootStatus()
        }

        binding.btnForceRoot.setOnClickListener {
            lifecycleScope.launch {
                binding.tvStatus.text = "Requesting root..."
                val ok = withContext(Dispatchers.IO) {
                    CryptoHookManager.requestRootAndSetup(this@MainActivity)
                }
                binding.tvStatus.text = if (ok) "Root OK — ready" else "Root failed. Grant Superuser to this app in Magisk/KernelSU"
            }
        }
    }

    private fun prepareAndStart() {
        lifecycleScope.launch {
            binding.tvStatus.text = "Checking root..."
            val rootOk = withContext(Dispatchers.IO) {
                CryptoHookManager.requestRootAndSetup(this@MainActivity)
            }
            if (!rootOk) {
                binding.tvStatus.text = "Root not granted. Open Magisk → Superuser → allow this app, then retry"
                Toast.makeText(this@MainActivity, "Grant Superuser first", Toast.LENGTH_LONG).show()
                return@launch
            }
            binding.tvStatus.text = "Root OK. Starting service..."
            val intent = VpnService.prepare(this@MainActivity)
            if (intent != null) {
                vpnPermission.launch(intent)
            } else {
                startService()
            }
        }
    }

    private fun startService() {
        val i = Intent(this, ExtractorVpnService::class.java).apply {
            action = ExtractorVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, i)
        binding.tvStatus.text = "Running in background. Open game → Login"
        Toast.makeText(this, "Extractor live", Toast.LENGTH_SHORT).show()
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) { }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadSavedKeys() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { keyStore.readAll() }
            binding.tvKeys.text = text.ifEmpty { "No keys yet" }
        }
    }

    private fun checkRootStatus() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { CryptoHookManager.suOk() }
            binding.tvRootStatus.text = if (ok) "Root: GRANTED ✓" else "Root: NOT GRANTED ✗"
        }
    }
}
