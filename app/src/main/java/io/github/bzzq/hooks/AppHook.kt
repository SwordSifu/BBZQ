package io.github.bzzq.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

interface AppHook {
    val targetPackageName: String

    fun install(xposed: XposedInterface, packageReady: PackageReadyParam, log: (String, Throwable?) -> Unit)
}
