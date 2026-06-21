package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.methodsNamed
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class VideoCommentHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return

        var count = 0
        count += hookDisableComment()
        count += hookQuickReply()
        count += hookVoteWidgets()
        count += hookFollowWidgets()
        count += hookSearchUrls()
        count += hookEmptyPage()
        count += hookMainListCleanup()
        log("startHook: VideoComment, methods=$count")
    }

    private fun hookDisableComment(): Int {
        val tabPagerService = THESEUS_TAB_PAGER_SERVICE.firstNotNullOfOrNull { it.from(classLoader) } ?: return 0
        val constructors = tabPagerService.declaredConstructors
        constructors.forEach { ctor ->
            ctor.isAccessible = true
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
        return constructors.size
    }

    private fun hookQuickReply(): Int {
        val viewModelClass = COMMENT_VIEW_MODEL_CLASSES.firstNotNullOfOrNull { it.from(classLoader) } ?: return 0
        val candidates = viewModelClass.methodsNamed(null)
            .filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterCount == 1 &&
                    !method.name.contains("lambda", ignoreCase = true) &&
                    method.name in QUICK_REPLY_METHOD_NAMES &&
                    method.parameterTypes.firstOrNull()?.isCommentActionType() == true
            }
            .distinctBy(Method::toGenericString)
            .toList()

        candidates.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    if (!ModuleSettings.isCommentNoQuickReplyEnabled(prefs)) return@runCatching
                    val action = param.args.firstOrNull() ?: return@runCatching
                    if (!action.isPublishDialogAction()) return@runCatching
                    param.result = null
                }.onFailure {
                    log("VideoComment quick reply hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }
        return candidates.size
    }

    private fun hookVoteWidgets(): Int {
        var count = 0
        val classes = CMT_VOTE_WIDGET_CLASSES + CMT_MOUNT_WIDGET_CLASSES + COMMENT_VOTE_VIEW_CLASSES
        classes.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            type.methodsNamed(null)
                .filter { it.returnType == Void.TYPE && it.parameterCount >= 1 }
                .forEach { method ->
                    env.hookBefore(method) { param ->
                        runCatching {
                            if (ModuleSettings.isCommentNoVoteEnabled(prefs)) {
                                param.result = null
                            }
                        }.onFailure {
                            log("VideoComment vote hook failed at ${method.declaringClass.name}.${method.name}", it)
                        }
                    }
                    count += 1
                }
        }
        return count
    }

    private fun hookFollowWidgets(): Int {
        var count = 0

        COMMENT_FOLLOW_WIDGET_CLASSES.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            type.methodsNamed(null)
                .filter { it.returnType == Void.TYPE && it.parameterCount >= 1 }
                .forEach { method ->
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
        }

        COMMENT_HEADER_DECORATIVE_VIEW_CLASSES.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            type.methodsNamed(null)
                .filter { it.parameterCount >= 1 && List::class.java.isAssignableFrom(it.parameterTypes[0]) }
                .forEach { method ->
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
        }

        return count
    }

    private fun hookSearchUrls(): Int {
        val contentClass = COMMENT_CONTENT_CLASSES.firstNotNullOfOrNull { it.from(classLoader) } ?: return 0
        val method = contentClass.methodsNamed("internalGetUrls").firstOrNull { it.parameterCount == 0 } ?: return 0

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

    private fun hookEmptyPage(): Int {
        var count = 0

        COMMENT_SUBJECT_CONTROL_CLASSES.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            val hostClassLoader = type.classLoader ?: return@forEach
            val defaultInstance = hostClassLoader
                .loadClass(type.name.replace("SubjectControl", "EmptyPage"))
                .declaredFields
                .firstOrNull { it.name == "DEFAULT_INSTANCE" }
                ?.apply { isAccessible = true }
                ?.get(null)
            val method = type.methodsNamed("getEmptyPage").firstOrNull { it.parameterCount == 0 } ?: return@forEach
            if (defaultInstance != null) {
                env.hookAfter(method) { param ->
                    runCatching {
                        if (ModuleSettings.isCommentNoEmptyPageEnabled(prefs)) {
                            param.result = defaultInstance
                        }
                    }.onFailure {
                        log("VideoComment empty page hook failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                count += 1
            }
        }

        COMMENT_SUBJECT_DESC_CLASSES.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            val emptyPageClassName = type.name.replace("SubjectDescriptionReply", "EmptyPage")
            val hostClassLoader = type.classLoader ?: return@forEach
            val defaultInstance = runCatching {
                hostClassLoader.loadClass(emptyPageClassName)
                    .declaredFields
                    .firstOrNull { it.name == "DEFAULT_INSTANCE" }
                    ?.apply { isAccessible = true }
                    ?.get(null)
            }.getOrNull()
            val method = type.methodsNamed("getEmptyPage").firstOrNull { it.parameterCount == 0 } ?: return@forEach
            if (defaultInstance != null) {
                env.hookAfter(method) { param ->
                    runCatching {
                        if (ModuleSettings.isCommentNoEmptyPageEnabled(prefs)) {
                            param.result = defaultInstance
                        }
                    }.onFailure {
                        log("VideoComment subject desc empty page hook failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                count += 1
            }
        }

        return count
    }

    private fun hookMainListCleanup(): Int {
        var count = 0
        COMMENT_MAIN_LIST_OBSERVERS.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            val onNext = type.methodsNamed("onNext").firstOrNull { it.parameterCount == 1 } ?: return@forEach
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
            count += 1
        }
        return count
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
            clearQoe = type.methodsNamed(null)
                .firstOrNull { it.name == "clearQoe" && it.parameterCount == 0 },
            clearOperations = type.methodsNamed(null)
                .filter { it.name in CLEAR_OPERATION_METHOD_NAMES && it.parameterCount == 0 }
                .distinctBy(Method::toGenericString)
                .toList(),
            getRepliesList = type.methodsNamed("getRepliesList")
                .firstOrNull { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) },
        )

    private fun Class<*>.isCommentActionType(): Boolean {
        val simpleName = simpleName
        val className = name
        return className.contains(".comment3.", ignoreCase = true) &&
            (simpleName.contains("Action", ignoreCase = true) ||
                simpleName.contains("Intent", ignoreCase = true))
    }

    private fun Any.isPublishDialogAction(): Boolean {
        val simpleName = javaClass.simpleName
        val className = javaClass.name
        return simpleName.contains("ShowPublishDialog", ignoreCase = true) ||
            simpleName.contains("PublishDialog", ignoreCase = true) ||
            className.contains("ShowPublishDialog", ignoreCase = true) ||
            className.contains("PublishDialogIntent", ignoreCase = true)
    }

    private companion object {
        private val THESEUS_TAB_PAGER_SERVICE = arrayOf(
            "com.bilibili.ship.theseus.united.page.tab.TheseusTabPagerService",
            "com.bilibili.p5797ship.theseus.united.p5850page.p5861tab.TheseusTabPagerService",
        )

        private val COMMENT_VIEW_MODEL_CLASSES = arrayOf(
            "com.bilibili.app.comment3.viewmodel.CommentViewModel",
            "com.bilibili.p4439app.comment3.viewmodel.CommentViewModel",
        )

        private val CMT_VOTE_WIDGET_CLASSES = arrayOf(
            "com.bilibili.app.comment.ext.widgets.CmtVoteWidget",
            "com.bilibili.p4439app.comment.p4511ext.widgets.CmtVoteWidget",
        )

        private val CMT_MOUNT_WIDGET_CLASSES = arrayOf(
            "com.bilibili.app.comment.ext.widgets.CmtMountWidget",
            "com.bilibili.p4439app.comment.p4511ext.widgets.CmtMountWidget",
        )

        private val COMMENT_VOTE_VIEW_CLASSES = arrayOf(
            "com.bilibili.app.comment3.ui.widget.CommentVoteView",
            "com.bilibili.p4439app.comment3.p4518ui.widget.CommentVoteView",
        )

        private val COMMENT_FOLLOW_WIDGET_CLASSES = arrayOf(
            "com.bilibili.app.comm.comment2.phoenix.view.CommentFollowWidget",
            "com.bilibili.p4439app.p4450comm.comment2.phoenix.p4467view.CommentFollowWidget",
        )

        private val COMMENT_HEADER_DECORATIVE_VIEW_CLASSES = arrayOf(
            "com.bilibili.app.comment3.ui.widget.CommentHeaderDecorativeView",
            "com.bilibili.p4439app.comment3.p4518ui.widget.CommentHeaderDecorativeView",
        )

        private val COMMENT_CONTENT_CLASSES = arrayOf(
            "com.bapis.bilibili.main.community.reply.v1.Content",
            "com.bapis.bilibili.p4311main.community.reply.p4312v1.Content",
        )

        private val COMMENT_SUBJECT_CONTROL_CLASSES = arrayOf(
            "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
            "com.bapis.bilibili.p4311main.community.reply.p4312v1.SubjectControl",
        )

        private val COMMENT_SUBJECT_DESC_CLASSES = arrayOf(
            "com.bapis.bilibili.main.community.reply.v2.SubjectDescriptionReply",
            "com.bapis.bilibili.p4311main.community.reply.p4313v2.SubjectDescriptionReply",
        )

        private val COMMENT_MAIN_LIST_OBSERVERS = arrayOf(
            "com.bapis.bilibili.main.community.reply.v1.ReplyMossKtxKt\$suspendMainList\$\$inlined\$suspendCall\$1",
            "com.bapis.bilibili.main.community.reply.v1.KReplyMoss\$mainList\$\$inlined\$suspendCall\$2",
            "com.bapis.bilibili.p4311main.community.reply.p4312v1.ReplyMossKtxKt\$suspendMainList\$\$inlined\$suspendCall\$1",
            "com.bapis.bilibili.p4311main.community.reply.p4312v1.KReplyMoss\$mainList\$\$inlined\$suspendCall\$2",
        )

        private val QUICK_REPLY_METHOD_NAMES = setOf("dispatchAction", "onAction", "submitAction")
        private val CLEAR_OPERATION_METHOD_NAMES = setOf("clearOperation", "clearOperationV2")
        private val replyCleanupMethods = ConcurrentHashMap<Class<*>, ReplyCleanupMethods>()
    }
}

private data class ReplyCleanupMethods(
    val clearQoe: Method?,
    val clearOperations: List<Method>,
    val getRepliesList: Method?,
)
