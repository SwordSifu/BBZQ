# bzzq

bzzq 是一个基于 libxposed API 101 的 Android Xposed 模块项目。

## 特性

- 使用 libxposed API 101
- 使用静态作用域声明目标包名
- 仅在 Xposed 框架名称为 `NPatch` 时启用模块逻辑
- 使用 JDK 21 编译
- Gradle Wrapper 对齐 NPatch 当前使用的 Gradle 8.14.4

## 目标包

```text
tv.danmaku.bili
com.bilibili.app.in
tv.danmaku.bilibilihd
com.bilibili.app.blue
```

## 构建

```powershell
.\gradlew.bat assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 兼容性说明

bzzq 会在模块加载时读取 Xposed 框架名称。只有框架名称等于 `NPatch` 时，模块才会继续处理目标应用包；其他框架环境下会保持禁用状态。

## 授权

本项目使用木兰公共许可证，第 2 版（Mulan PubL v2）授权。

许可证全文见 [LICENSE](LICENSE)，官方文本以木兰开源社区发布版本为准：

<http://license.coscl.org.cn/MulanPubL-2.0>
