package io.github.bbzq

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通过 LSPosed 模块仓库的 GitHub Release API 检查模组是否有新版本。
 *
 * 先按发布通道过滤（默认仅正式版；接收测试版时纳入 prerelease），再取最新 Release
 * 与当前版本比较，判定优先级：
 * 1. versionCode（两端均可解析时）：最新版 > 当前版 → 有更新；
 * 2. 版本号（versionName）：最新版版本号更高 → 有更新；
 * 3. 其余情况（版本号相同或更低）→ 已是最新。
 *
 * versionCode 取自 fork tag_name 的末尾数字（如 v1.0.3-133 → 133），同时兼容旧式
 * 133-v1.0.3-133；versionName 优先取 tag_name（剥离 versionCode 前后缀，name 字段作为备选），
 * 更新日志取 body，安装包大小取 APK 资产的 size。
 *
 * 结果回调统一切回主线程，便于直接更新 UI。
 */
object UpdateChecker {

    /** 一次检查更新的结果。 */
    data class Result(
        /** 检查结果状态：已最新 / 有更新 / 失败 */
        val status: Status,
        /** 最新 Release 的版本名（已规范化，如 1.0.3），失败时为 null */
        val latestVersion: String? = null,
        /** 本地当前版本名（BuildConfig.RELEASE_NAME），便于回显 */
        val currentVersion: String? = null,
        /** 最新 Release 的下载/详情页地址，无则为 null */
        val releaseUrl: String? = null,
        /** 最新 Release 的更新日志（release body），无内容时为 null */
        val releaseNotes: String? = null,
        /** 最新 Release 的 versionCode，无法解析时为 0 */
        val latestVersionCode: Int = 0,
        /** 最新 Release 的 APK 安装包大小（字节），无则为 0 */
        val apkSizeBytes: Long = 0,
    )

    enum class Status {
        /** 已是最新版本 */
        UP_TO_DATE,
        /** 发现新版本 */
        UPDATE_AVAILABLE,
        /** 网络或解析失败 */
        FAILED,
    }

    /** 单个 Release 的精简信息。 */
    private data class ReleaseInfo(
        /** 版本名（优先取自 tag_name，name 字段为备选，如 v1.0.3） */
        val versionName: String,
        /** Release 详情页地址（来自 html_url） */
        val htmlUrl: String,
        /** 更新日志原文（来自 body） */
        val body: String,
        /** 从 tag_name 开头数字解析出的 versionCode，无则为 0 */
        val versionCode: Int,
        /** APK 安装包大小（字节），无则为 0 */
        val apkSizeBytes: Long,
        /** 是否为预发布（测试）版本 */
        val prerelease: Boolean,
    )

    /** 用于把异步结果切回主线程的主线程 Handler。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 检查更新专用的 OkHttp 客户端，懒加载，连接/读取均限时 10 秒。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param currentVersion 本地版本号（BuildConfig.RELEASE_NAME）
     * @param currentVersionCode 本地 versionCode（BuildConfig.VERSION_CODE），0 或负值表示不参与比较
     * @param acceptPrerelease 是否接收预发布（测试）版本更新
     * @return 本次请求的 [Call]，调用方可在界面销毁时 cancel 以释放回调、避免泄漏；构造失败时为 null
     */
    fun check(
        currentVersion: String,
        currentVersionCode: Int,
        acceptPrerelease: Boolean,
        onResult: (Result) -> Unit,
    ): Call? {
        // 同步兜底：构造请求或入队若抛出异常（如客户端初始化失败、调度器拒绝），
        // 也要回调一次失败，避免调用方状态永久卡住。
        return try {
            val request = Request.Builder()
                .url(RELEASE_API_URL)
                .header("accept", "application/vnd.github+json")
                .header("user-agent", USER_AGENT)
                .build()

            val call = httpClient.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    postResult(onResult, Result(Status.FAILED, currentVersion = currentVersion))
                }

                override fun onResponse(call: Call, response: Response) {
                    // 整体兜底：读取 body 或解析期间的任何异常都不应使回调丢失，
                    // 否则调用方的 updateChecking 状态会永久卡住。
                    val result = try {
                        response.use { parseResponse(it, currentVersion, currentVersionCode, acceptPrerelease) }
                    } catch (_: Throwable) {
                        Result(Status.FAILED, currentVersion = currentVersion)
                    }
                    postResult(onResult, result)
                }
            })
            call
        } catch (_: Throwable) {
            postResult(onResult, Result(Status.FAILED, currentVersion = currentVersion))
            null
        }
    }

    private fun parseResponse(
        response: Response,
        currentVersion: String,
        currentVersionCode: Int,
        acceptPrerelease: Boolean,
    ): Result {
        if (!response.isSuccessful) {
            return Result(Status.FAILED, currentVersion = currentVersion)
        }
        val body = response.body.string()
        if (body.isBlank()) {
            return Result(Status.FAILED, currentVersion = currentVersion)
        }
        val allReleases = parseReleases(body)
        if (allReleases.isEmpty()) {
            return Result(Status.FAILED, currentVersion = currentVersion)
        }
        // 发布通道过滤：未接收测试版时仅保留正式版。
        val releases = if (acceptPrerelease) allReleases else allReleases.filterNot { it.prerelease }
        if (releases.isEmpty()) {
            return Result(Status.UP_TO_DATE, currentVersion = currentVersion)
        }

        // 优先按 versionCode、其次按版本号选出最新 Release。
        val latest = releases.maxWithOrNull { left, right -> compareRelease(left, right) }
            ?: return Result(Status.FAILED, currentVersion = currentVersion)

        val hasUpdate = UpdateVersionParser.isUpdateAvailable(
            remote = ParsedUpdateTag(latest.versionName, latest.versionCode),
            localVersion = currentVersion,
            localVersionCode = currentVersionCode,
        )
        val status = if (hasUpdate) Status.UPDATE_AVAILABLE else Status.UP_TO_DATE
        return Result(
            status = status,
            latestVersion = UpdateVersionParser.normalizeVersion(latest.versionName),
            currentVersion = currentVersion,
            releaseUrl = latest.htmlUrl,
            releaseNotes = latest.body.trim().takeIf { it.isNotEmpty() },
            latestVersionCode = latest.versionCode,
            apkSizeBytes = latest.apkSizeBytes,
        )
    }

    /** 列表内排序用：先比 versionCode，缺失则比版本号。 */
    private fun compareRelease(left: ReleaseInfo, right: ReleaseInfo): Int {
        if (left.versionCode > 0 && right.versionCode > 0) {
            return left.versionCode.compareTo(right.versionCode)
        }
        return UpdateVersionParser.compareVersions(left.versionName, right.versionName)
    }

    /** 解析 Release 列表，丢弃 draft 与无版本名的条目。 */
    private fun parseReleases(body: String): List<ReleaseInfo> {
        val releaseArray = JSONArray(body)
        val releases = ArrayList<ReleaseInfo>(releaseArray.length())
        for (index in 0 until releaseArray.length()) {
            val releaseObject = releaseArray.optJSONObject(index) ?: continue
            if (releaseObject.optBoolean("draft", false)) continue
            val tagName = jsonString(releaseObject, "tag_name")
            val parsedTag = UpdateVersionParser.parseTag(tagName)
            val versionCode = parsedTag?.versionCode ?: 0
            // 版本名优先取 tag_name（兼容 fork 与旧式 tag），无法得出时退回 name 字段。
            val versionName = parsedTag?.versionName.orEmpty().ifBlank { jsonString(releaseObject, "name") }
            if (versionName.isBlank()) continue
            val htmlUrl = jsonString(releaseObject, "html_url").takeIf { it.isNotBlank() } ?: RELEASE_PAGE_URL
            releases += ReleaseInfo(
                versionName = versionName,
                htmlUrl = htmlUrl,
                body = jsonString(releaseObject, "body"),
                versionCode = versionCode,
                apkSizeBytes = parseApkSize(releaseObject),
                prerelease = releaseObject.optBoolean("prerelease", false),
            )
        }
        return releases
    }

    /** 读取字符串字段，JSON null 视为空串（org.json 的 optString 对 null 会返回字面 "null"）。 */
    private fun jsonString(jsonObject: JSONObject, key: String): String =
        if (jsonObject.isNull(key)) "" else jsonObject.optString(key)

    /** 取 Release 中 APK 资产的大小（字节），多个时取最大值，无则返回 0。 */
    private fun parseApkSize(release: JSONObject): Long {
        val assets = release.optJSONArray("assets") ?: return 0
        var maxSize = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val assetSize = asset.optLong("size", 0)
            if (assetSize > maxSize) maxSize = assetSize
        }
        return maxSize
    }

    private fun postResult(onResult: (Result) -> Unit, result: Result) {
        mainHandler.post { onResult(result) }
    }

    /** LSPosed 模块仓库的 Release 列表 API，单次最多取 30 条。 */
    private const val RELEASE_API_URL =
        "https://api.github.com/repos/SwordSifu/BBZQ/releases?per_page=30"

    /** Release 列表为空或缺少链接时，跳转的兜底 Release 页地址。 */
    private const val RELEASE_PAGE_URL =
        "https://github.com/SwordSifu/BBZQ/releases/latest"

    /** 请求头 User-Agent，GitHub API 要求携带。 */
    private const val USER_AGENT = "BBZQ-UpdateChecker"

    /** 建立连接的超时时间（秒）。 */
    private const val CONNECT_TIMEOUT_SECONDS = 10L

    /** 读取响应的超时时间（秒）。 */
    private const val READ_TIMEOUT_SECONDS = 10L
}
