# BBZQ
> 使用 `libxposed API 102`、由 Kotlin 全量编写的哔哩哔哩增强 Xposed 模组

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![API](https://img.shields.io/badge/libxposed-API%20102-orange)
![License](https://img.shields.io/badge/license-Mulan%20PubL%20v2-blue)

---

## 简介
BBZQ 是一款适配 libxposed API 102 的哔哩哔哩功能增强模组，采用 Kotlin 完整实现实作，旨在去除不必要内容、优化核心体验，同时提供各类实功能。
模组通过 `META-INF/xposed/java_init.list` 完成入口注册，核心逻辑入口为 `io.github.bbzq.BbzqModule`；配套设置页面内嵌于哔哩哔哩宿主应用内，方便快捷开关各项功能、调整个性化参数，整体架构便于后续迭代维护与功能扩展。

---

## 支持目标

| 包名 | 说明 |
|------|------|
| `tv.danmaku.bili` | 哔哩哔哩 |
| `com.bilibili.app.in` | BiliBili 旧版 |
| `tv.danmaku.bilibilihd` | HD 版 |
| `com.bilibili.app.blue` | 概念版 |

---

## 功能

### 分享与链接

- **净化分享** — 将 `b23.tv` / `bili2233.cn` 短链还原为普通链接，并保留 `p`、`t` 等定位参数
- **普通链接分享** — 关闭小程序式分享（QQ / 微信），复制分享链接时尽量转换为 av 号形式

### 复制增强

- **去除长按复制** — 禁用应用内各场景里长按后直接复制到剪贴板的行为，减少误触
- **长按自由复制** — 需先开启「去除长按复制」；拦截到复制动作时弹出可自由选择文本的窗口

### 启动净化

- **跳过开屏广告** — 清理启动时的开屏广告响应，减少进入目标应用时的等待（默认开启）
- **关闭青少年模式弹窗**：检测到青少年模式提醒弹窗时自动关闭

### 下载功能

- **自定义下载线程**：在下载设置里显示「自定义下载线程」，并可单独调整同时缓存数
- **自定义同时缓存数**：支援自定义同时缓存的数量。

### 首页推荐净化

- **移除首页推荐广告** — 过滤推荐流中的大横幅、信息流广告及广告推广视频
- **移除首页推荐图文** — 过滤推荐流中的图文动态卡片
- **移除首页推荐游戏推广**：过滤推荐流中的游戏推广卡片
- **阻止首页推荐自动刷新**：阻止冷启动或从其他页面返回时自动刷新推荐流，保留手动刷新
- **隐藏整个首页内容** — 将首页容器下的子页面内容隐藏

### 界面定制

- **自定义底栏** — 隐藏底部导航栏中不需要的入口；首次使用需重启 B 站并打开首页加载底栏数据

### 播放净化

- **屏蔽视频下方横幅广告** — 阻止视频详情页播放器下方横幅广告创建
- **跳过视频激励广告** — 自动尝试完成视频激励页（RewardAdActivity）
- **自动点赞视频** — 进入视频详情页时自动点击点赞按钮

### 空降助手

基于 [BilibiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock) 社区数据库，在视频播放时按片段时间轴自动跳过选定分类的内容。功能默认关闭，入口默认隐藏

支持的跳过分类：

| 分类 | 说明 |
|------|------|
| `sponsor` | 赞助商广告，付费推广、推荐和直接广告 |
| `selfpromo` | 自我推广，UP 主引流、关注提醒、推广其他内容 |
| `interaction` | 互动提醒，点赞、投币、评论等互动号召 |
| `intro` | 片头，与正文关系不大的固定开场 |
| `outro` | 片尾，结束卡、鸣谢和结尾引导 |
| `preview` | 预览回顾，下集预告、前情提要和重复回顾 |
| `music_offtopic` | 离题音乐，与内容无关的纯音乐或演奏片段 |
| `poi_highlight` | 精彩片段高光，值得直接空降或重点标记的内容 |
| `filler` | 填充内容，与主线关系较弱的灌水片段 |
| `exclusive_access` | 独家访问抢先体验，用于整段视频标签 |

片段数据按视频 BV 号的 SHA-256 前缀向 `bsbsb.top` API 查询，结果缓存 5 分钟，仅匹配当前分 P 的 cid。

### 竖屏视频净化

- **净化竖屏视频广告** — 按标签过滤竖屏视频流中的广告、购物和推广内容，并统计累计拦截条数

可选过滤标签：广告、短剧、购物、电视剧、纪录片、娱乐、电影、音乐、话题

---

## 使用方式

1. 安装模组 APK
2. 在支持 `libxposed API 102` 的框架（如 LSPosed）中启用模组
3. 将哔哩哔哩加入作用域
4. 重启目标应用
5. 进入 `我的 → 设置 → 高级设置`
6. 启用需要的功能

> 桌面图标是模组自身介绍页，不是独立的调试工具。  
> 双击 版本 有隐藏的秘密。

---

## 构建

**环境要求**

| 项目 | 版本 |
|------|------|
| JDK | 21 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.4.0 |
| Gradle Wrapper | 9.4.1 |

**Debug 构建**

```bash
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

**Release 构建**

```bash
./gradlew assembleRelease
# 输出： app/build/outputs/apk/release/bbzq_v<releaseName>-<commitCount>.apk
```

Release 构建需要提前配置签名参数。

---

## 项目结构

```text
app/src/main/java/io/github/bbzq/
├── BbzqModule.kt                  # libxposed API 102 模组入口
├── ModuleSettings.kt              # 所有配置键与默认值
├── ModuleSettingsBridge.kt        # 模组与宿主进程之间的设置桥接
├── ModuleSettingsProvider.kt      # ContentProvider 跨进程读取设置
├── SettingsActivity.kt            # 模组设置页 Activity
├── SettingsContentFactory.kt      # 设置页内容构建
├── RuntimeEnvironmentInfo.kt      # 运行时环境信息
└── feats/
    ├── RoamingRuntime.kt          # Hook 调度与进程分发
    ├── BilibiliSponsorBlock.kt    # 空降助手 API 客户端与缓存
    ├── Reflect.kt                 # 反射与 DexKit 辅助
    ├── Api102Hook.kt              # API 102 Hook 基类
    └── hook/                      # 各功能独立 Hook 实现
```

### 已知限制

- 设置入口依赖宿主设置页结构，大版本更新后可能不会及时适配
- 自定义底栏需重启 B 站并打开首页后才能读取到底栏项目数据
- 空降助手与 AccessKey 入口需要在版本号处双击才会显示
- 暂不计划支持地区解锁功能

---

## 许可证

本项目使用木兰公共许可证，第 2 版（Mulan PubL v2）。 
完整授权见 [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0)。
