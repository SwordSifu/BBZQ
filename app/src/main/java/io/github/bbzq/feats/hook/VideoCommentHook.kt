package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.methodsNamed

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
                if (!ModuleSettings.isCommentDisableEnabled(prefs)) return@hookBefore
                val listIndex = param.args.indexOfFirst { it is List<*> }
                if (listIndex < 0) return@hookBefore
                val items = param.args[listIndex] as? List<*> ?: return@hookBefore
                param.args[listIndex] = items.filterNot { item ->
                    val name = item?.javaClass?.name.orEmpty()
                    name.contains("CommentTabPageProvider") || name.contains("CommentTab")
                }
            }
        }
        return constructors.size
    }

    private fun hookQuickReply(): Int {
        val viewModelClass = COMMENT_VIEW_MODEL_CLASSES.firstNotNullOfOrNull { it.from(classLoader) } ?: return 0
        val candidates = viewModelClass.methodsNamed(null).filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterCount == 1 &&
                !method.name.contains("lambda", ignoreCase = true)
        }.toList()

        candidates.forEach { method ->
            env.hookBefore(method) { param ->
                if (!ModuleSettings.isCommentNoQuickReplyEnabled(prefs)) return@hookBefore
                val action = param.args.firstOrNull()?.toString() ?: return@hookBefore
                if (!action.startsWith("ShowPublishDialog")) return@hookBefore

                val allowed = QUICK_REPLY_ALLOW_STACK.any { marker ->
                    Throwable().stackTrace.any { stack -> "${stack.className}.${stack.methodName}".contains(marker) }
                }
                if (!allowed) {
                    param.result = null
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
                        if (ModuleSettings.isCommentNoVoteEnabled(prefs)) {
                            param.result = null
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
                        if (ModuleSettings.isCommentNoFollowEnabled(prefs)) {
                            param.result = null
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
                        if (!ModuleSettings.isCommentNoFollowEnabled(prefs)) return@hookBefore
                        @Suppress("UNCHECKED_CAST")
                        val items = param.args[0] as? List<Any?> ?: return@hookBefore
                        items.forEach { item ->
                            item?.javaClass?.declaredFields?.forEach { field ->
                                field.isAccessible = true
                                val value = runCatching { field.get(item) }.getOrNull() ?: return@forEach
                                if (value.toString().startsWith("Follow(")) {
                                    runCatching { field.set(item, null) }
                                }
                            }
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
            if (!ModuleSettings.isCommentNoSearchEnabled(prefs)) return@hookAfter
            val urls = param.result as? MutableMap<*, *> ?: return@hookAfter
            urls.entries.removeIf { (_, value) ->
                value != null && value.javaClass.declaredFields.any { field ->
                    field.isAccessible = true
                    runCatching { field.get(value)?.toString()?.startsWith("bilibili://search") == true }
                        .getOrDefault(false)
                }
            }
        }
        return 1
    }

    private fun hookEmptyPage(): Int {
        var count = 0

        COMMENT_SUBJECT_CONTROL_CLASSES.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            val defaultInstance = type.classLoader
                .loadClass(type.name.replace("SubjectControl", "EmptyPage"))
                .declaredFields
                .firstOrNull { it.name == "DEFAULT_INSTANCE" }
                ?.apply { isAccessible = true }
                ?.get(null)
            val method = type.methodsNamed("getEmptyPage").firstOrNull { it.parameterCount == 0 } ?: return@forEach
            if (defaultInstance != null) {
                env.hookAfter(method) { param ->
                    if (ModuleSettings.isCommentNoEmptyPageEnabled(prefs)) {
                        param.result = defaultInstance
                    }
                }
                count += 1
            }
        }

        COMMENT_SUBJECT_DESC_CLASSES.forEach { className ->
            val type = className.from(classLoader) ?: return@forEach
            val emptyPageClassName = type.name.replace("SubjectDescriptionReply", "EmptyPage")
            val defaultInstance = runCatching {
                type.classLoader.loadClass(emptyPageClassName)
                    .declaredFields
                    .firstOrNull { it.name == "DEFAULT_INSTANCE" }
                    ?.apply { isAccessible = true }
                    ?.get(null)
            }.getOrNull()
            val method = type.methodsNamed("getEmptyPage").firstOrNull { it.parameterCount == 0 } ?: return@forEach
            if (defaultInstance != null) {
                env.hookAfter(method) { param ->
                    if (ModuleSettings.isCommentNoEmptyPageEnabled(prefs)) {
                        param.result = defaultInstance
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
                val reply = param.args.firstOrNull() ?: return@hookBefore
                if (ModuleSettings.isCommentNoQoeEnabled(prefs)) {
                    reply.javaClass.methodsNamed(null)
                        .firstOrNull { it.name.contains("clearQoe", ignoreCase = true) && it.parameterCount == 0 }
                        ?.let { runCatching { it.invoke(reply) } }
                }
                if (ModuleSettings.isCommentNoOperationEnabled(prefs)) {
                    reply.javaClass.methodsNamed(null)
                        .filter { it.name.contains("clearOperation", ignoreCase = true) && it.parameterCount == 0 }
                        .forEach { runCatching { it.invoke(reply) } }
                }
            }
            count += 1
        }
        return count
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

        private val QUICK_REPLY_ALLOW_STACK = arrayOf(
            "CommentViewModel\$dispatchAction\$1",
            "CommentActionBar",
            "CommentMainLayer",
            "CommentDetailLayer",
            "CommentContentHolder",
            "CommentMoreMenu",
        )
    }
}
