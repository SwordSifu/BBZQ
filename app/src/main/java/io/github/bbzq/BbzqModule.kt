package io.github.bbzq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.bbzq.feats.RoamingRuntime
import io.github.bbzq.feats.symbol.BiliSymbolResolver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.atomic.AtomicBoolean

class BbzqModule : XposedModule() {
    private var packageName: String = ""
    private var processName: String = ""
    private val attachHookInstalled = AtomicBoolean(false)
    private val runtimeStarted = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.getProcessName()
        verifyFrameworkEnvironment()
        log(
            Log.INFO,
            LOG_TAG,
            "Loaded in $processName on $frameworkName($frameworkVersionCode), api=$apiVersion",
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.getPackageName()
        if (packageName !in TARGET_PACKAGES) return
        if (!RoamingRuntime.isProcessSupported(packageName, processName)) {
            log(
                Log.INFO,
                LOG_TAG,
                "Skip unsupported process $processName for $packageName",
            )
            return
        }
        this.packageName = packageName

        if (attachHookInstalled.compareAndSet(false, true).not()) {
            maybeStartRuntime(packageName, processName, param.getDefaultClassLoader())
            return
        }

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                chain.proceed()
                val application = chain.getThisObject() as? Application ?: return@intercept null
                startRuntimeOnce(
                    packageName = packageName,
                    processName = processName,
                    application = application,
                    classLoader = application.javaClass.classLoader ?: param.getDefaultClassLoader(),
                )
            }

        maybeStartRuntime(packageName, processName, param.getDefaultClassLoader())
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val forceSymbolRescan = param.getExtras()
            ?.getBoolean(SymbolCacheRefreshRequest.EXTRA_FORCE_SYMBOL_RESCAN, false) == true
        param.setSavedInstanceState(
            Bundle().apply {
                putString("packageName", packageName.takeIf { it.isNotBlank() })
                putBoolean("forceSymbolRescan", forceSymbolRescan)
            },
        )
        log(Log.INFO, LOG_TAG, "Hot reloading requested for ${packageName.ifBlank { processName }}")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        processName = param.getProcessName()
        val application = resolveCurrentApplication()
        val savedState = param.getSavedInstanceState()
        val resolvedPackageName = application?.packageName
            ?: (savedState as? Bundle)?.getString("packageName")
            ?: (savedState as? String)
            ?: packageName

        if (resolvedPackageName !in TARGET_PACKAGES) {
            log(Log.WARN, LOG_TAG, "Skip hot reload outside target packages: $resolvedPackageName")
            super.onHotReloaded(param)
            return
        }
        if (!RoamingRuntime.isProcessSupported(resolvedPackageName, processName)) {
            log(
                Log.INFO,
                LOG_TAG,
                "Skip hot reload in unsupported process $processName for $resolvedPackageName",
            )
            super.onHotReloaded(param)
            return
        }

        val classLoader = application?.javaClass?.classLoader
        if (application == null || classLoader == null) {
            log(Log.WARN, LOG_TAG, "Hot reload skipped because current application is unavailable")
            super.onHotReloaded(param)
            return
        }

        packageName = resolvedPackageName
        if (param.getExtras()?.getBoolean(SymbolCacheRefreshRequest.EXTRA_FORCE_SYMBOL_RESCAN, false) == true) {
            forceRefreshSymbolsForHotReload(application, classLoader)
        }
        startRuntimeOnce(
            packageName = resolvedPackageName,
            processName = processName,
            application = application,
            classLoader = classLoader,
        )
        super.onHotReloaded(param)
    }

    private fun forceRefreshSymbolsForHotReload(
        application: Context,
        classLoader: ClassLoader,
    ) {
        ModuleSettingsBridge.attach(application, this)
        val moduleContext = runCatching {
            application.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure {
            log(Log.WARN, LOG_TAG, "Hot reload symbol refresh cannot create module context", it)
        }.getOrNull()
        runCatching {
            BiliSymbolResolver.forceRefresh(
                hostContext = application,
                moduleContext = moduleContext,
                classLoader = classLoader,
            ) { message, throwable ->
                if (throwable == null) {
                    log(Log.INFO, LOG_TAG, message)
                } else {
                    log(Log.WARN, LOG_TAG, message, throwable)
                }
            }
        }.onFailure {
            log(Log.WARN, LOG_TAG, "Hot reload symbol refresh failed", it)
        }
    }

    private fun startRuntimeOnce(
        packageName: String,
        processName: String,
        application: Context,
        classLoader: ClassLoader,
    ) {
        if (runtimeStarted.compareAndSet(false, true).not()) return
        startRuntime(
            packageName = packageName,
            processName = processName,
            application = application,
            classLoader = classLoader,
        )
    }

    private fun startRuntime(
        packageName: String,
        processName: String,
        application: Context,
        classLoader: ClassLoader,
    ) {
        RoamingRuntime.start(
            xposed = this,
            packageName = packageName,
            processName = processName,
            application = application,
            classLoader = classLoader,
        ) { message, throwable ->
            if (throwable == null) {
                log(Log.INFO, LOG_TAG, message)
            } else {
                log(Log.WARN, LOG_TAG, message, throwable)
            }
        }
    }

    private fun maybeStartRuntime(
        packageName: String,
        processName: String,
        classLoader: ClassLoader,
    ) {
        val application = resolveCurrentApplication() ?: return
        val resolvedClassLoader = application.javaClass.classLoader ?: classLoader
        startRuntimeOnce(
            packageName = packageName,
            processName = processName,
            application = application,
            classLoader = resolvedClassLoader,
        )
    }

    private fun resolveCurrentApplication(): Application? {
        return runCatching {
            currentApplicationMethod.invoke(null) as? Application
        }.getOrNull()
    }

    private fun verifyFrameworkEnvironment() {
        if (frameworkVersionCode.toString() != "7777") return
        if (frameworkVersion == "2.1.0-it") return
        error(
            "Environment abnormal: frameworkVersionCode=$frameworkVersionCode, frameworkVersion=$frameworkVersion",
        )
    }

    private companion object {
        private const val LOG_TAG = "BBZQ"
        private const val MODULE_PACKAGE = "io.github.bbzq"

        private val TARGET_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.blue",
        )

        private val currentApplicationMethod: java.lang.reflect.Method by lazy(LazyThreadSafetyMode.NONE) {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
        }
    }
}
