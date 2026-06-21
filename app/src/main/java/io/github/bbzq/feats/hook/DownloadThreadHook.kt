package io.github.bbzq.feats.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.R
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.getObjectField
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.LazyThreadSafetyMode

class DownloadThreadHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val cachedClassNames by lazy(LazyThreadSafetyMode.NONE) {
        dexClassNames().toList()
    }

    override fun startHook() {
        if (!ModuleSettings.isCustomDownloadThreadEnabled(prefs)) return

        var count = 0
        findListenerTypes().forEach { type ->
            count += hookListener(type)
        }
        count += hookReportDownloadThread()

        if (count > 0) {
            log("startHook: DownloadThread, methods=$count")
        } else {
            log("startHook: DownloadThread, no matching hooks found")
        }
    }

    private fun hookListener(type: Class<*>): Int {
        var count = 0

        type.declaredConstructors
            .filter { ctor -> ctor.parameterTypes.any { it == TextView::class.java } }
            .forEach { ctor ->
                env.hookAfter(ctor) { param ->
                    val view = param.args.firstOrNull { it is TextView } as? TextView ?: return@hookAfter
                    view.post {
                        runCatching {
                            val parent = view.parent as? ViewGroup ?: return@runCatching
                            val custom = view.tag as? Int == 1
                            if (custom) {
                                val moduleContext = env.moduleContext ?: run {
                                    log("DownloadThread label skipped because module context is unavailable")
                                    return@runCatching
                                }
                                view.text = moduleContext.getString(R.string.custom_download_thread_label)
                            }
                            parent.getChildAt(1)?.visibility = if (custom) View.VISIBLE else View.INVISIBLE
                        }.onFailure { throwable ->
                            log("DownloadThread label update failed", throwable)
                        }
                    }
                }
                count++
            }

        val onClick = type.allMethods().firstOrNull {
            it.name == "onClick" &&
                it.parameterCount == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return count

        env.hookBefore(onClick) { param ->
            val view = param.thisObject.findFieldValue(TextView::class.java) ?: return@hookBefore
            if (view.tag as? Int != 1) return@hookBefore

            view.tag = ModuleSettings.getCustomDownloadConcurrency(prefs)
        }
        count++
        return count
    }

    private fun hookReportDownloadThread(): Int {
        val method = findReportDownloadThreadMethod() ?: return 0
        log("reportDownloadThread candidate: ${method.toGenericString()}")
        if (method.returnType.isPrimitive || method.returnType == Void.TYPE) return 0
        if (!CharSequence::class.java.isAssignableFrom(method.returnType)) {
            log("Skip reportDownloadThread hook: unsupported return type ${method.returnType.name}")
            return 0
        }
        env.hookBefore(method) { param ->
            param.result = ""
        }
        return 1
    }

    private fun Any?.findFieldValue(type: Class<out TextView>): TextView? {
        val target = this ?: return null
        return target.javaClass.allFields().firstNotNullOfOrNull { field ->
            if (!type.isAssignableFrom(field.type)) return@firstNotNullOfOrNull null
            runCatching { field.get(target) as? TextView }.getOrNull()
        }
    }

    private fun findListenerTypes(): List<Class<*>> {
        val scoped = findClasses(
            predicate = { name ->
                val lower = name.lowercase(Locale.US)
                lower.contains("download") || lower.contains("thread") || lower.contains("listener")
            },
        ).filter { it.isDownloadThreadListener() }
        if (scoped.isNotEmpty()) return scoped

        return findClasses(
            predicate = { name ->
                val lower = name.lowercase(Locale.US)
                lower.contains("download") || lower.contains("cache") || lower.contains("offline")
            },
            limit = 500,
        ).filter { it.isDownloadThreadListener() }
    }

    private fun findReportDownloadThreadMethod(): Method? {
        val scoped = findReportDownloadThreadMethod(false)
        return scoped ?: findReportDownloadThreadMethod(true)
    }

    private fun findReportDownloadThreadMethod(broad: Boolean): Method? {
        val predicate: (String) -> Boolean = if (broad) {
            { name ->
                val lower = name.lowercase(Locale.US)
                lower.contains("download") || lower.contains("cache") || lower.contains("offline")
            }
        } else {
            { name ->
                val lower = name.lowercase(Locale.US)
                lower.contains("download") || lower.contains("thread") || lower.contains("report")
            }
        }
        val classes = findClasses(
            predicate = predicate,
            limit = if (broad) 500 else Int.MAX_VALUE,
        )
        return classes.asSequence()
            .flatMap { type -> type.allMethods().asSequence() }
            .firstOrNull { method ->
                method.name == "reportDownloadThread" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == Context::class.java &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }
    }

    private fun findClasses(predicate: (String) -> Boolean, limit: Int = Int.MAX_VALUE): List<Class<*>> {
        return cachedClassNames.asSequence()
            .filter(predicate)
            .take(limit)
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            .distinctBy { it.name }
            .toList()
    }

    private fun dexClassNames(): Sequence<String> = sequence {
        val baseDexClassLoader = classLoader as? BaseDexClassLoader ?: return@sequence
        val pathList = baseDexClassLoader.getObjectField("pathList") ?: return@sequence
        val dexElements = pathList.getObjectField("dexElements") as? Array<*> ?: return@sequence
        dexElements.forEach { element ->
            val dexFile = element?.getObjectField("dexFile") as? DexFile ?: return@forEach
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                yield(entries.nextElement())
            }
        }
    }

    private fun Class<*>.isDownloadThreadListener(): Boolean {
        if (isInterface || Modifier.isAbstract(modifiers)) return false
        val hasTextViewConstructor = declaredConstructors.any { ctor ->
            ctor.parameterTypes.any { TextView::class.java.isAssignableFrom(it) }
        }
        val hasOnClick = allMethods().any { method ->
            method.name == "onClick" &&
                method.parameterCount == 1 &&
                View::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        return hasTextViewConstructor && hasOnClick
    }

}
