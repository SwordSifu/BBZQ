package io.github.bbzq.feats

import android.widget.Toast
import io.github.bbzq.BuildConfig
import io.github.bbzq.ModuleSettings
import io.github.bbzq.R
import io.github.bbzq.UpdateChecker

internal object HookUpdateChecker {
    private val automaticCheckThrottle = AutomaticCheckThrottle(
        intervalMillis = AUTOMATIC_CHECK_INTERVAL_MILLIS,
    )

    fun check(env: RoamingEnv) {
        if (!automaticCheckThrottle.tryAcquire(
                readLastStartedAt = {
                    env.prefs.getLong(
                        ModuleSettings.KEY_HOOK_UPDATE_LAST_STARTED_AT,
                        NO_TIMESTAMP,
                    ).takeUnless { it == NO_TIMESTAMP }
                },
                writeLastStartedAt = { timestamp ->
                    env.prefs.edit()
                        .putLong(ModuleSettings.KEY_HOOK_UPDATE_LAST_STARTED_AT, timestamp)
                        .apply()
                },
                nowMillis = { System.currentTimeMillis() },
            )
        ) {
            env.log("Hook update check skipped (automatic check throttled)")
            return
        }

        val acceptPrerelease = ModuleSettings.isAcceptPrereleaseUpdateEnabled(env.prefs)
        UpdateChecker.check(
            currentVersion = BuildConfig.RELEASE_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            acceptPrerelease = acceptPrerelease,
        ) { result ->
            when (result.status) {
                UpdateChecker.Status.UPDATE_AVAILABLE -> {
                    val version = result.latestVersion.orEmpty()
                    val message = runCatching {
                        (env.moduleContext ?: env.hostContext).getString(
                            R.string.hook_update_available_toast,
                            version,
                        )
                    }.getOrElse {
                        "BBZQ $version is available"
                    }
                    Toast.makeText(env.hostContext, message, Toast.LENGTH_LONG).show()
                    env.log("Hook update check found version $version")
                }

                UpdateChecker.Status.UP_TO_DATE ->
                    env.log("Hook update check: already up to date")

                UpdateChecker.Status.FAILED ->
                    env.log("Hook update check failed")
            }
        }
    }

    private const val AUTOMATIC_CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
    private const val NO_TIMESTAMP = Long.MIN_VALUE
}
