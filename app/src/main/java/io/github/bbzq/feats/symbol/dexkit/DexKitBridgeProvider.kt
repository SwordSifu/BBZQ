package io.github.bbzq.feats.symbol.dexkit

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.zip.ZipFile

class DexKitScanBridge(
    val sourcePath: String,
    val bridge: DexKitBridge,
) : AutoCloseable {
    override fun close() {
        bridge.close()
    }
}

object DexKitBridgeProvider {
    private const val DEXKIT_LIBRARY_FILE_NAME = "libdexkit.so"
    private const val EXTRACTED_LIBRARY_DIR = "bbzq_native/dexkit"

    @Volatile
    private var nativeLibraryLoaded = false

    @Volatile
    private var nativeLibraryLoadError: String? = null

    private val nativeLibraryLoaderKey: String =
        Integer.toHexString(System.identityHashCode(DexKitBridgeProvider::class.java.classLoader))

    fun openFirstAvailable(
        hostContext: Context,
        moduleContext: Context?,
        sourcePaths: List<String>,
        recordError: (String) -> Unit,
        log: (String) -> Unit,
    ): DexKitScanBridge? {
        ensureNativeLibraryLoaded(hostContext, moduleContext, recordError, log) ?: return null
        var failed = 0
        var firstError: String? = null
        for (sourcePath in sourcePaths.distinct()) {
            if (!File(sourcePath).isFile) continue
            try {
                return DexKitScanBridge(sourcePath, DexKitBridge.create(sourcePath))
            } catch (t: Throwable) {
                failed++
                if (firstError == null) firstError = t.scanMessage()
            }
        }
        if (failed > 0) {
            recordError("DexKitBridge open failed count=$failed first=$firstError")
        }
        return null
    }

    private fun ensureNativeLibraryLoaded(
        hostContext: Context,
        moduleContext: Context?,
        recordError: (String) -> Unit,
        log: (String) -> Unit,
    ): Boolean? {
        if (nativeLibraryLoaded) return true
        nativeLibraryLoadError?.let {
            recordError("DexKit native unavailable: $it")
            return null
        }
        return synchronized(this) {
            if (nativeLibraryLoaded) return@synchronized true
            nativeLibraryLoadError?.let {
                recordError("DexKit native unavailable: $it")
                return@synchronized null
            }
            val loadError = tryLoadNativeLibrary(hostContext, moduleContext, log)
            if (loadError == null) {
                nativeLibraryLoaded = true
                true
            } else {
                nativeLibraryLoadError = loadError
                recordError("DexKit native unavailable: $loadError")
                null
            }
        }
    }

    private fun tryLoadNativeLibrary(
        hostContext: Context,
        moduleContext: Context?,
        log: (String) -> Unit,
    ): String? {
        val moduleApk = moduleContext?.applicationInfo?.sourceDir
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?: return "module apk unavailable"
        val target = extractLibrary(hostContext, moduleApk)
            ?: return "libdexkit.so not found in module apk"
        return try {
            System.load(target.absolutePath)
            log("DexKitBridge: loaded native library extracted to ${target.parent}")
            null
        } catch (t: Throwable) {
            "extracted=${target.absolutePath} ${t.scanMessage()}"
        }
    }

    private fun extractLibrary(hostContext: Context, moduleApk: File): File? {
        val fingerprint = "${moduleApk.length()}_${moduleApk.lastModified()}".filter {
            it.isLetterOrDigit() || it == '_'
        }
        val baseDir = File(hostContext.codeCacheDir, "$EXTRACTED_LIBRARY_DIR/$fingerprint/$nativeLibraryLoaderKey")
        ZipFile(moduleApk).use { zip ->
            for (abi in android.os.Build.SUPPORTED_ABIS.toList()) {
                val entry = zip.getEntry("lib/$abi/$DEXKIT_LIBRARY_FILE_NAME") ?: continue
                val abiDir = File(baseDir, abi)
                val target = File(abiDir, DEXKIT_LIBRARY_FILE_NAME)
                if (target.isFile && target.length() == entry.size) return target
                if (!abiDir.exists() && !abiDir.mkdirs()) return null
                val temp = File(abiDir, "$DEXKIT_LIBRARY_FILE_NAME.tmp")
                zip.getInputStream(entry).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (entry.size >= 0 && temp.length() != entry.size) {
                    temp.delete()
                    continue
                }
                if (target.exists() && !target.delete()) {
                    temp.delete()
                    return null
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
                target.setReadable(true, true)
                target.setExecutable(true, true)
                return target
            }
        }
        return null
    }
}

internal fun Throwable.scanMessage(): String =
    "${javaClass.name}: ${message.orEmpty()}".replace('\n', ' ').take(420)
