package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredVideoCommentSymbols
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.Unit

class VideoCommentHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return

        val symbols = env.symbols?.videoComment?.restore(classLoader)
        if (symbols == null) {
            log("startHook: VideoComment skipped because symbols are unavailable")
            return
        }

        var count = 0
        count += hookDisableComment(symbols)
        count += hookQuickReply(symbols)
        count += hookVoteWidgets(symbols)
        count += hookFollowWidgets(symbols)
        count += hookSearchUrls(symbols)
        count += hookEmptyPage(symbols)
        count += hookMainListCleanup(symbols)
        log("startHook: VideoComment, methods=$count")
    }

    private fun hookDisableComment(symbols: RestoredVideoCommentSymbols): Int {
        symbols.disableCommentConstructors.forEach { ctor ->
            env.hookBefore(ctor) { param ->
                runCatching {
                    if (!ModuleSettings.isCommentDisableEnabled(prefs)) return@runCatching
                    val listIndex = param.args.indexOfFirst { it is List<*> }
                    if (listIndex < 0) return@runCatching
                    val items = param.args[listIndex] as? List<*> ?: return@runCatching
                    param.args[listIndex] = items.filterNot { item ->
                        val name = item?.javaClass?.name.orEmpty()
                        name.contains("CommentTabPageProvider") || name.contains("CommentTab")
                    }
                }.onFailure {
                    log("VideoComment disable comment hook failed", it)
                }
            }
        }
        return symbols.disableCommentConstructors.size
    }

    private fun hookQuickReply(symbols: RestoredVideoCommentSymbols): Int {
        val viewModelCount = hookQuickReplyByViewModel(symbols.quickReplyViewModelMethods)
        val dialogCount = hookQuickReplyDialogFlow(symbols.quickReplyDialogMethods)
        return viewModelCount + dialogCount
    }

    private fun hookQuickReplyByViewModel(methods: List<Method>): Int {
        methods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    if (!ModuleSettings.isCommentNoQuickReplyEnabled(prefs)) return@runCatching
                    val action = if (param.args.size >= 2 && param.args[1] == false) {
                        param.thisObject
                    } else {
                        param.args.firstOrNull()
                    } ?: return@runCatching
                    if (!action.shouldBlockPublishDialogAction()) return@runCatching
                    param.result = null
                }.onFailure {
                    log("VideoComment quick reply hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }
        return methods.size
    }

    private fun hookQuickReplyDialogFlow(methods: List<Method>): Int {
        methods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    if (!ModuleSettings.isCommentNoQuickReplyEnabled(prefs)) return@runCatching
                    val intent = param.args.firstOrNull() ?: return@runCatching
                    if (!intent.shouldBlockQuickReplyDialog()) return@runCatching
                    param.result = Unit
                }.onFailure {
                    log("VideoComment quick reply dialog hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }
        return methods.size
    }

    private fun hookVoteWidgets(symbols: RestoredVideoCommentSymbols): Int {
        symbols.voteWidgetMethods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    if (ModuleSettings.isCommentNoVoteEnabled(prefs)) {
                        param.result = null
                    }
                }.onFailure {
                    log("VideoComment vote hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }
        return symbols.voteWidgetMethods.size
    }

    private fun hookFollowWidgets(symbols: RestoredVideoCommentSymbols): Int {
        var count = 0

        symbols.followWidgetMethods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    if (ModuleSettings.isCommentNoFollowEnabled(prefs)) {
                        param.result = null
                    }
                }.onFailure {
                    log("VideoComment follow widget hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
            count += 1
        }

        symbols.headerDecorativeMethods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    if (!ModuleSettings.isCommentNoFollowEnabled(prefs)) return@runCatching
                    @Suppress("UNCHECKED_CAST")
                    val items = param.args[0] as? List<Any?> ?: return@runCatching
                    items.forEach { item ->
                        item?.javaClass?.declaredFields?.forEach { field ->
                            field.isAccessible = true
                            val value = runCatching { field.get(item) }.getOrNull() ?: return@forEach
                            if (value.javaClass.simpleName.contains("Follow", ignoreCase = true)) {
                                runCatching { field.set(item, null) }
                            }
                        }
                    }
                }.onFailure {
                    log("VideoComment decorative follow hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
            count += 1
        }

        return count
    }

    private fun hookSearchUrls(symbols: RestoredVideoCommentSymbols): Int {
        val method = symbols.searchUrlsMethod ?: return 0

        env.hookAfter(method) { param ->
            runCatching {
                if (!ModuleSettings.isCommentNoSearchEnabled(prefs)) return@runCatching
                val urls = param.result as? MutableMap<*, *> ?: return@runCatching
                urls.entries.removeIf { (_, value) ->
                    value != null && value.javaClass.declaredFields.any { field ->
                        field.isAccessible = true
                        runCatching {
                            (field.get(value) as? CharSequence)
                                ?.startsWith("bilibili://search") == true
                        }.getOrDefault(false)
                    }
                }
            }.onFailure {
                log("VideoComment search url hook failed", it)
            }
        }
        return 1
    }

    private fun hookEmptyPage(symbols: RestoredVideoCommentSymbols): Int {
        symbols.emptyPageHooks.forEach { hook ->
            env.hookAfter(hook.getEmptyPage) { param ->
                runCatching {
                    if (ModuleSettings.isCommentNoEmptyPageEnabled(prefs)) {
                        param.result = hook.defaultInstance
                    }
                }.onFailure {
                    log("VideoComment empty page hook failed at ${hook.getEmptyPage.declaringClass.name}.${hook.getEmptyPage.name}", it)
                }
            }
        }
        return symbols.emptyPageHooks.size
    }

    private fun hookMainListCleanup(symbols: RestoredVideoCommentSymbols): Int {
        symbols.mainListOnNextMethods.forEach { onNext ->
            env.hookBefore(onNext) { param ->
                runCatching {
                    val reply = param.args.firstOrNull() ?: return@runCatching
                    val removeQoe = ModuleSettings.isCommentNoQoeEnabled(prefs)
                    val removeOperation = ModuleSettings.isCommentNoOperationEnabled(prefs)
                    if (!removeQoe && !removeOperation) return@runCatching
                    cleanupReplyPayload(reply, removeQoe, removeOperation)
                }.onFailure {
                    log("VideoComment main list cleanup failed at ${onNext.declaringClass.name}.${onNext.name}", it)
                }
            }
        }
        return symbols.mainListOnNextMethods.size
    }

    private fun cleanupReplyPayload(reply: Any, removeQoe: Boolean, removeOperation: Boolean) {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>()
        pending += reply

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue

            val methods = replyCleanupMethods.getOrPut(current.javaClass) {
                resolveReplyCleanupMethods(current.javaClass)
            }
            if (removeQoe) {
                methods.clearQoe?.let { runCatching { it.invoke(current) } }
            }
            if (removeOperation) {
                methods.clearOperations.forEach { method ->
                    runCatching { method.invoke(current) }
                }
            }

            val children = methods.getRepliesList
                ?.let { runCatching { it.invoke(current) as? List<*> }.getOrNull() }
                .orEmpty()
            children.filterNotNullTo(pending)
        }
    }

    private fun resolveReplyCleanupMethods(type: Class<*>): ReplyCleanupMethods =
        ReplyCleanupMethods(
            clearQoe = type.declaredMethods
                .firstOrNull { it.name == "clearQoe" && it.parameterCount == 0 },
            clearOperations = type.declaredMethods
                .filter { it.name in CLEAR_OPERATION_METHOD_NAMES && it.parameterCount == 0 }
                .distinctBy(Method::toGenericString)
                .toList(),
            getRepliesList = type.declaredMethods
                .firstOrNull { it.name == "getRepliesList" && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) },
        )

    private fun Class<*>.isCommentActionType(): Boolean {
        val simpleName = simpleName
        val className = name
        return className.contains(".comment3.", ignoreCase = true) &&
            (simpleName.contains("Action", ignoreCase = true) ||
                simpleName.contains("Intent", ignoreCase = true))
    }

    private fun Any.shouldBlockPublishDialogAction(): Boolean =
        resolvePublishDialogIntent()?.shouldBlockQuickReplyDialog() == true

    private fun Any.shouldBlockQuickReplyDialog(): Boolean {
        if (!isQuickReplyDialogIntentType()) return false

        val fields = publishDialogIntentFields.getOrPut(javaClass) { resolvePublishDialogIntentFields(javaClass) }
        val isReply = runCatching { getObjectField("f184778b") as? Boolean }.getOrNull()
            ?: runCatching { fields.isReply?.get(this) as? Boolean }.getOrNull()
            ?: false
        if (!isReply) return false

        val posName = runCatching { getObjectField("f184787k")?.toString().orEmpty() }.getOrDefault("")
            .ifBlank {
                runCatching { fields.pos?.get(this)?.toString().orEmpty() }.getOrDefault("")
            }
        if (posName.isBlank()) return true

        return when {
            posName.contains("REPLY_BUTTON", ignoreCase = true) -> false
            posName.contains("BAR", ignoreCase = true) -> false
            posName.contains("MORE_MENU", ignoreCase = true) -> false
            posName.contains("INPUT", ignoreCase = true) -> false
            posName.contains("CARD", ignoreCase = true) -> true
            posName.contains("ITEM", ignoreCase = true) -> true
            posName.contains("TEXT", ignoreCase = true) -> true
            posName.contains("REPLY", ignoreCase = true) -> true
            else -> true
        }
    }

    private fun Any.isQuickReplyDialogIntentType(): Boolean {
        val className = javaClass.name
        return className.endsWith("PublishDialogIntent", ignoreCase = true) ||
            className.contains("PublishDialogIntent", ignoreCase = true) ||
            (
                className.contains(".comment3.", ignoreCase = true) &&
                    javaClass.simpleName.contains("DialogIntent", ignoreCase = true) &&
                    javaClass.declaredFields.any { field ->
                        field.type.isEnum || field.type == Boolean::class.javaPrimitiveType
                    }
            )
    }

    private fun Any.resolvePublishDialogIntent(): Any? {
        if (isQuickReplyDialogIntentType()) return this
        val field = publishDialogActionIntentFields.getOrPut(javaClass) {
            FieldLookup(
                javaClass.declaredFields.firstOrNull { field ->
                    field.type.isQuickReplyDialogIntentType()
                }?.apply { isAccessible = true },
            )
        }.field ?: return null
        return runCatching { field.get(this) }.getOrNull()
    }

    private fun Class<*>.isQuickReplyDialogIntentType(): Boolean {
        val className = name
        return className.endsWith("PublishDialogIntent", ignoreCase = true) ||
            className.contains("PublishDialogIntent", ignoreCase = true) ||
            (
                className.contains(".comment3.", ignoreCase = true) &&
                    simpleName.contains("DialogIntent", ignoreCase = true) &&
                    declaredFields.any { field ->
                        field.type.isEnum || field.type == Boolean::class.javaPrimitiveType
                    }
            )
    }

    private fun resolvePublishDialogIntentFields(type: Class<*>): PublishDialogIntentFields {
        val fields = type.declaredFields.onEach { it.isAccessible = true }
        val booleanFields = fields.filter { it.type == Boolean::class.javaPrimitiveType }
        return PublishDialogIntentFields(
            isReply = booleanFields.getOrNull(1),
            pos = fields.firstOrNull { it.type.isEnum && it.type.simpleName == "Pos" },
        )
    }

    private companion object {
        private val CLEAR_OPERATION_METHOD_NAMES = setOf("clearOperation", "clearOperationV2")
        private val replyCleanupMethods = ConcurrentHashMap<Class<*>, ReplyCleanupMethods>()
        private val publishDialogActionIntentFields = ConcurrentHashMap<Class<*>, FieldLookup>()
        private val publishDialogIntentFields = ConcurrentHashMap<Class<*>, PublishDialogIntentFields>()
    }
}

private data class FieldLookup(val field: Field?)

private data class PublishDialogIntentFields(
    val isReply: Field?,
    val pos: Field?,
)

private data class ReplyCleanupMethods(
    val clearQoe: Method?,
    val clearOperations: List<Method>,
    val getRepliesList: Method?,
)
