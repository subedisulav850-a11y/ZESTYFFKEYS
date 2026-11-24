package com.roha.ffaessextractor

import android.content.Context
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Fixed root + crypto extractor.
 * Does NOT require pre-installed frida-server binary.
 * Uses su to inject a lightweight JS hook via any available frida or falls back to
 * process monitoring + Java crypto observation.
 */
object CryptoHookManager {

    var targetPackage: String = "com.garena.game.ffi"
    var targetName: String = "Free Fire"

    private const val FRIDA_SERVER_PATHS = arrayOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/fs",
        "/data/adb/frida-server",
        "/system/bin/frida-server",
        "/system/xbin/frida-server"
    )

    fun suOk(): Boolean {
        return try {
            val result = su("id")
            result.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request root and prepare environment.
     * Magisk / KernelSU will show popup if not yet allowed.
     */
    fun requestRootAndSetup(ctx: Context): Boolean {
        // Force a su call so the Superuser popup appears
        val id = su("id")
        if (!id.contains("uid=0")) return false

        // Make sure we can write to /data/local/tmp
        su("mkdir -p /data/local/tmp")
        su("chmod 777 /data/local/tmp")

        // Kill any stale frida-server
        su("pkill -f frida-server 2>/dev/null || true")

        // Try to locate any existing frida-server
        val existing = findFridaServer()
        if (existing != null) {
            su("chmod 755 $existing")
            su("$existing -D &")
            Thread.sleep(1200)
            if (serverRunning()) return true
        }

        // No frida-server found — still return true because we can use pure su methods
        // and the user can later push one. Root is the critical part.
        return true
    }

    private fun findFridaServer(): String? {
        for (path in FRIDA_SERVER_PATHS) {
            val check = su("test -f $path && echo EXISTS || echo NO")
            if (check.contains("EXISTS")) return path
        }
        return null
    }

    private fun serverRunning(): Boolean {
        val out = su("pgrep -f frida-server || true")
        return out.trim().isNotEmpty()
    }

    fun runExtractor(
        context: Context,
        onKey: (type: String, hex: String, str: String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        onStatus("Root active. Preparing hook script...")

        val scriptFile = File(context.filesDir, "ff_hook.js")
        scriptFile.writeText(HookScript.content)

        // Method 1: try frida if available
        val fridaPaths = listOf("frida", "/data/local/tmp/frida", "/system/bin/frida")
        var usedFrida = false

        for (fridaBin in fridaPaths) {
            val hasFrida = su("command -v $fridaBin >/dev/null 2>&1 && echo YES || echo NO")
            if (hasFrida.contains("YES") || File(fridaBin).exists()) {
                onStatus("Using $fridaBin — attaching to $targetPackage")
                usedFrida = true
                runWithFrida(fridaBin, scriptFile.absolutePath, onKey, onStatus)
                break
            }
        }

        if (!usedFrida) {
            onStatus("No frida binary. Using su process monitor + crypto dump")
            runFallbackMonitor(onKey, onStatus)
        }
    }

    private fun runWithFrida(
        fridaBin: String,
        scriptPath: String,
        onKey: (String, String, String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        // First make sure target is running
        su("am force-stop $targetPackage 2>/dev/null || true")
        Thread.sleep(400)
        su("monkey -p $targetPackage -c android.intent.category.LAUNCHER 1 2>/dev/null || am start -n \$(pm dump $targetPackage | grep -A1 MAIN | head -2 | tail -1 | awk '{print \$3}') 2>/dev/null || true")
        Thread.sleep(1500)

        val cmd = "$fridaBin -U -n $targetPackage -l $scriptPath --runtime=v8 2>&1"
        onStatus("Frida attached. Open game and tap Login")

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                parseHookLine(l, onKey)
                if (l.contains("AES KEY FOUND") || l.contains("AES IV FOUND")) {
                    onStatus("Key material captured")
                }
            }
            process.waitFor(45, TimeUnit.MINUTES)
        } catch (e: Exception) {
            onStatus("Frida attach failed: ${e.message}")
        }
    }

    /**
     * Fallback when no frida binary exists.
     * We still force the game to start and monitor logcat for crypto related strings
     * + periodically dump process maps looking for AES patterns.
     * This is weaker but works on pure temporary root without extra binaries.
     */
    private fun runFallbackMonitor(
        onKey: (String, String, String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        onStatus("Fallback mode — monitoring logcat + memory for AES")

        // Clear logcat
        su("logcat -c")

        // Launch target
        su("monkey -p $targetPackage -c android.intent.category.LAUNCHER 1 2>/dev/null || true")

        // Continuous logcat filter for crypto
        val logCmd = "logcat -v brief | grep -iE 'aes|secretkey|iv|cipher|encrypt|decrypt|garena|dts' 2>/dev/null"
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", logCmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var count = 0
            while (reader.readLine().also { line = it } != null && count < 5000) {
                val l = line ?: continue
                count++
                // Heuristic: look for long hex strings that look like keys
                val hexMatch = Regex("[0-9a-fA-F]{32,64}").find(l)
                if (hexMatch != null) {
                    val hex = hexMatch.value.lowercase()
                    onKey("HEURISTIC_KEY", hex, "")
                    onStatus("Possible key found in log")
                }
            }
        } catch (e: Exception) {
            onStatus("Monitor error: ${e.message}")
        }
    }

    private fun parseHookLine(line: String, onKey: (String, String, String) -> Unit) {
        when {
            line.contains("HEX :") -> {
                val hex = line.substringAfter("HEX :")
                    .trim()
                    .substringBefore("║")
                    .trim()
                    .lowercase()
                if (hex.length >= 16) {
                    val type = when {
                        line.contains("KEY") -> "KEY"
                        line.contains("IV") -> "IV"
                        else -> "KEY"
                    }
                    onKey(type, hex, "")
                }
            }
            line.contains("[NATIVE KEY]") -> {
                val hex = line.substringAfter("[NATIVE KEY]").trim().removePrefix(":").trim()
                if (hex.isNotEmpty()) onKey("NATIVE_KEY", hex, "")
            }
            line.contains("[NATIVE IV]") -> {
                val hex = line.substringAfter("[NATIVE IV]").trim().removePrefix(":").trim()
                if (hex.isNotEmpty()) onKey("NATIVE_IV", hex, "")
            }
        }
    }

    /** Run a command as root and return stdout */
    fun su(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }
            while (errReader.readLine().also { line = it } != null) {
                // ignore stderr for now
            }
            process.waitFor(8, TimeUnit.SECONDS)
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
