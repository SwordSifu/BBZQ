package io.github.bbzq.feats.hook

import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.isAssignableFromBoxed
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.LazyThreadSafetyMode

class AccessKeyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val cachedClassNames by lazy(LazyThreadSafetyMode.NONE) {
        dexClassNames().toList()
    }
    private val biliPackageAccess by lazy(LazyThreadSafetyMode.NONE) {
        BiliPackageAccess()
    }

    override fun startHook() {
        if (env.processName != env.packageName) return

        AccessKeyRepository.register {
            runCatching { getAccessKey() }
                .onFailure { log("AccessKey read failed", it) }
                .getOrNull()
        }
        log("AccessKeyHook installed")
    }

    private fun getAccessKey(): String? {
        val direct = biliPackageAccess.accessKey
        if (AccessKeyRepository.looksLikeAccessKey(direct.orEmpty())) return direct

        val account = biliPackageAccess.biliAccounts ?: return null
        return account.findAccessKeyValue()
    }

    private fun Any.findAccessKeyValue(): String? {
        KNOWN_ACCESS_KEY_METHODS.forEach { methodName ->
            val value = callMethod(methodName) as? String
            if (AccessKeyRepository.looksLikeAccessKey(value.orEmpty())) return value
        }

        javaClass.allMethods()
            .filter { method ->
                method.parameterCount == 0 &&
                    method.returnType == String::class.java &&
                    method.name !in KNOWN_NOISE_METHODS
            }
            .sortedBy(Method::getName)
            .forEach { method ->
                val value = runCatching { method.invoke(this) as? String }.getOrNull()
                if (AccessKeyRepository.looksLikeAccessKey(value.orEmpty())) {
                    log("AccessKey found via method ${javaClass.name}.${method.name}()")
                    return value
                }
            }

        return findAccessKeyInObjectGraph(this, depth = 0, visited = hashSetOf())
    }

    private fun findAccessKeyInObjectGraph(target: Any?, depth: Int, visited: MutableSet<Int>): String? {
        if (target == null || depth > MAX_FIELD_DEPTH) return null
        val identity = System.identityHashCode(target)
        if (!visited.add(identity)) return null

        if (target is String && AccessKeyRepository.looksLikeAccessKey(target)) {
            return target
        }

        target.javaClass.allFields().forEach { field ->
            val value = runCatching { field.get(target) }.getOrNull() ?: return@forEach
            when {
                value is String && AccessKeyRepository.looksLikeAccessKey(value) -> {
                    log("AccessKey found via field ${target.javaClass.name}.${field.name}")
                    return value
                }
                shouldTraverseField(value) -> {
                    findAccessKeyInObjectGraph(value, depth + 1, visited)?.let { return it }
                }
            }
        }
        return null
    }

    private fun shouldTraverseField(value: Any): Boolean {
        if (value.javaClass.isPrimitive) return false
        if (value is CharSequence || value is Number || value is Boolean || value is Enum<*>) return false
        val name = value.javaClass.name
        return !name.startsWith("java.") &&
            !name.startsWith("kotlin.") &&
            !name.startsWith("android.")
    }

    private fun looksLikeAccountObject(value: Any): Boolean {
        val type = value.javaClass
        val name = type.name.lowercase(Locale.US)
        return "account" in name ||
            type.allMethods().any { method ->
                method.parameterCount == 0 &&
                    method.returnType == String::class.java &&
                    method.name.lowercase(Locale.US).contains("access")
            } ||
            type.allFields().any { field ->
                field.type == String::class.java &&
                    field.name.lowercase(Locale.US).contains("access")
            }
    }

    private fun mightBeAccountClassName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return ("account" in lower || "passport" in lower) &&
            !lower.contains("service") &&
            !lower.contains("listener") &&
            !lower.contains("activity") &&
            !lower.contains("fragment")
    }

    private fun isViableAccountType(type: Class<*>): Boolean {
        if (type.isInterface || Modifier.isAbstract(type.modifiers)) return false
        return type.allMethods().any { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterCount in 0..1 &&
                method.returnType != Void.TYPE
        }
    }

    private fun Class<*>.callStaticNoArg(name: String): Any? {
        val method = allMethods().firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.name == name &&
                it.parameterCount == 0
        } ?: return null
        return runCatching { method.invoke(null) }.getOrNull()
    }

    private fun Class<*>.callStaticWithContext(name: String, context: Any): Any? {
        val method = allMethods().firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.name == name &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFromBoxed(context)
        } ?: return null
        return runCatching { method.invoke(null, context) }.getOrNull()
    }

    private inner class BiliPackageAccess {
        private val binding by lazy(LazyThreadSafetyMode.NONE) { resolveBinding() }

        val biliAccounts: Any?
            get() {
                val current = binding ?: return null
                return runCatching {
                    val args = if (current.getMethod.parameterCount == 0) {
                        emptyArray()
                    } else {
                        arrayOf(env.hostContext)
                    }
                    current.getMethod.invoke(null, *args)
                }.onFailure {
                    log("AccessKey invoke biliAccounts failed: ${current.accountsClass.name}.${current.getMethod.name}", it)
                }.getOrNull()
            }

        val accessKey: String?
            get() {
                val current = binding ?: return null
                val account = biliAccounts ?: return null
                return runCatching {
                    current.accessKeyMethod.invoke(account) as? String
                }.onFailure {
                    log("AccessKey invoke getAccessKey failed: ${account.javaClass.name}.${current.accessKeyMethod.name}", it)
                }.getOrNull()
            }

        private fun resolveBinding(): AccountBinding? {
            knownBiliAccountsClasses().forEach { type ->
                resolveBinding(type)?.let { return it }
            }

            findCandidateAccountClasses().forEach { type ->
                resolveBinding(type)?.let {
                    log("AccessKey account binding fallback hit: ${type.name}")
                    return it
                }
            }
            return null
        }

        private fun resolveBinding(type: Class<*>): AccountBinding? {
            val getMethod = resolveGetMethod(type) ?: return null
            val accessKeyMethod = resolveAccessKeyMethod(type) ?: return null
            return AccountBinding(type, getMethod, accessKeyMethod)
        }

        private fun resolveGetMethod(type: Class<*>): Method? {
            KNOWN_ACCOUNT_FACTORY_METHODS.firstNotNullOfOrNull { methodName ->
                type.allMethods().firstOrNull {
                    Modifier.isStatic(it.modifiers) &&
                        it.name == methodName &&
                        it.returnType == type &&
                        (
                            it.parameterCount == 0 ||
                                (it.parameterCount == 1 && it.parameterTypes[0].isAssignableFromBoxed(env.hostContext))
                            )
                }
            }?.let { return it }

            return type.allMethods()
                .filter {
                    Modifier.isStatic(it.modifiers) &&
                        it.returnType == type &&
                        (
                            it.parameterCount == 0 ||
                                (it.parameterCount == 1 && it.parameterTypes[0].isAssignableFromBoxed(env.hostContext))
                            )
                }
                .sortedWith(compareBy<Method> { it.parameterCount }.thenBy { it.name })
                .firstOrNull()
        }

        private fun resolveAccessKeyMethod(type: Class<*>): Method? {
            KNOWN_ACCESS_KEY_METHODS.firstNotNullOfOrNull { methodName ->
                type.allMethods().firstOrNull {
                    !Modifier.isStatic(it.modifiers) &&
                        it.name == methodName &&
                        it.parameterCount == 0 &&
                        it.returnType == String::class.java
                }
            }?.let { return it }

            return type.allMethods()
                .filter {
                    !Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 &&
                        it.returnType == String::class.java
                }
                .sortedBy(Method::getName)
                .firstOrNull { method ->
                    method.name.lowercase(Locale.US).contains("access") ||
                        method.name == "a" ||
                        method.name == "b"
                }
        }
    }

    private fun knownBiliAccountsClasses(): List<Class<*>> =
        KNOWN_ACCOUNT_CLASSES.mapNotNull { it.from(classLoader) }

    private fun findCandidateAccountClasses(): List<Class<*>> {
        return cachedClassNames.asSequence()
            .filter(::mightBeAccountClassName)
            .take(MAX_CLASS_SCAN_COUNT)
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            .filter(::isViableAccountType)
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

    private companion object {
        private const val MAX_CLASS_SCAN_COUNT = 160
        private const val MAX_FIELD_DEPTH = 3

        private val KNOWN_ACCOUNT_CLASSES = arrayOf(
            "com.bilibili.lib.accounts.BiliAccounts",
            "com.bilibili.app.accounts.BiliAccounts",
            "com.bilibili.p4439app.accounts.BiliAccounts",
        )

        private val KNOWN_ACCOUNT_FACTORY_METHODS = arrayOf(
            "get",
            "instance",
            "getInstance",
            "a",
            "b",
        )

        private val KNOWN_ACCESS_KEY_METHODS = arrayOf(
            "getAccessKey",
            "accessKey",
            "a",
            "b",
        )

        private val KNOWN_NOISE_METHODS = setOf(
            "toString",
            "hashCode",
            "getClass",
        )
    }

    private data class AccountBinding(
        val accountsClass: Class<*>,
        val getMethod: Method,
        val accessKeyMethod: Method,
    )
}
