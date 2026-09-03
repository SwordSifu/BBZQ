package io.github.bbzq

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

object RootUtils {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    val CANDIDATE_PACKAGES = listOf(
        "tv.danmaku.bili",
    )

    fun isRootAvailable(): Boolean {
        val paths = System.getenv("PATH")?.split(":") ?: listOf(
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/system/sd/xbin",
            "/system/bin/failsafe",
            "/data/local/xbin",
            "/data/local/bin",
            "/data/local",
            "/system/xbin/su",
            "/system/bin/su",
        )
        return paths.any { dir -> File(dir, "su").exists() }
    }

    fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    fun resolveBilibiliPackage(context: Context, prefs: SharedPreferences?): String {
        val runtimePkg = prefs?.getString(ModuleSettings.KEY_RUNTIME_HOST_PACKAGE, null)
        if (!runtimePkg.isNullOrBlank()) {
            if (isPackageInstalled(context.packageManager, runtimePkg)) {
                return runtimePkg
            }
        }
        for (pkg in CANDIDATE_PACKAGES) {
            if (isPackageInstalled(context.packageManager, pkg)) {
                return pkg
            }
        }
        return runtimePkg?.takeIf { it.isNotBlank() } ?: "tv.danmaku.bili"
    }

    fun executeSuCommand(vararg commands: String): Result<Int> {
        return runCatching {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                for (cmd in commands) {
                    writer.write(cmd)
                    writer.newLine()
                }
                writer.write("exit")
                writer.newLine()
                writer.flush()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                exitCode
            } else {
                throw IllegalStateException("su exited with code $exitCode")
            }
        }
    }

    fun restartBilibili(
        context: Context,
        prefs: SharedPreferences?,
        callback: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        val targetPackage = resolveBilibiliPackage(context, prefs)
        executor.execute {
            val result = executeSuCommand("am force-stop $targetPackage")
            val isSuccess = result.isSuccess
            mainHandler.post {
                if (isSuccess) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(launchIntent) }
                    }
                    callback(true, null)
                } else {
                    callback(false, result.exceptionOrNull()?.message)
                }
            }
        }
    }

    fun showRestartBilibiliDialog(
        activity: Activity,
        prefs: SharedPreferences,
        onRestartSuccess: (() -> Unit)? = null,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.restart_dialog_title)
            .setMessage(R.string.restart_dialog_message)
            .setPositiveButton(R.string.restart_dialog_confirm) { _, _ ->
                Toast.makeText(activity, R.string.restart_in_progress, Toast.LENGTH_SHORT).show()
                restartBilibili(activity, prefs) { success, _ ->
                    if (success) {
                        Toast.makeText(activity, R.string.restart_success, Toast.LENGTH_SHORT).show()
                        onRestartSuccess?.invoke()
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.restart_dialog_title)
                            .setMessage(R.string.restart_failed_root_required)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
