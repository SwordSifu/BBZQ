# 自动签名发布配置

master 的代码或工作流更新会自动尝试发布；如果变更仅涉及 Markdown 文档，则不触发工作流。PR 只验证，不签名、不发布。手动运行仍可通过 `publish_release` 选择是否发布。

发布链路：计算版本 → 提前检查签名配置；单测通过且签名检查通过 → 四种 ABI 签名构建 → 包名、版本、ABI 和签名指纹验证 → 上传四个 APK 与 SHA256SUMS.txt。

## 当前阻塞的实际原因

2026-09-05 的手动发布运行 [33955750300](https://github.com/SwordSifu/BBZQ/actions/runs/33955750300) 在 `Check signed release prerequisites` 失败，下列五项全部为空。普通 APK 构建成功不代表签名发布成功。

旧版 `v1.3.0-261` 的 arm64 APK 已下载并用 Android apksig 8.7.3 验证：v2 签名有效，证书主题为 `C=US, O=Android, CN=Android Debug`。证书 SHA-256 为 `eb95aa4fa0a1fc76a300cb8405833609244d1efc1bc38a02eec5e70a6f23ba91`。因此旧包并非无签名；此前 Apksign 插件在 keystore 不存在时回退到 debug signingConfig。这个证书指纹是公开信息，不能用它还原私钥，也不能仅凭证书名称认定未来构建会使用同一密钥。

## 一次性配置

在 [Actions secrets](https://github.com/SwordSifu/BBZQ/settings/secrets/actions) 添加四个 Repository secrets：

| 名称 | 内容 |
| --- | --- |
| BBZQ_KEYSTORE_BASE64 | 发布用 keystore 文件的 Base64 编码（包含私钥，请仅存入 Secret） |
| BBZQ_KEYSTORE_PASSWORD | keystore 密码 |
| BBZQ_KEY_ALIAS | 发布签名的密钥别名 |
| BBZQ_KEY_PASSWORD | 对应私钥密码 |

在 [Actions variables](https://github.com/SwordSifu/BBZQ/settings/variables/actions) 添加 Repository variable `BBZQ_RELEASE_CERT_SHA256`，值为上述别名对应的签名证书 SHA-256 指纹。

应使用计划持续发布的原有签名密钥。如果没有原密钥，需要单独决定新的签名身份与安装迁移方案，不能通过移除校验或使用调试密钥冒充正式更新。

不要把 keystore、Base64 内容或密码提交进仓库、写入 Release 文案或粘贴到公开日志。

## 配置后的操作

如果本次自动发布因配置缺失失败，补齐后重新运行失败任务；也可在 CI 页面手动运行并勾选发布。后续 master 的适用更新会自动发布，无须每次手动勾选。

发布仍会检查签名、产物完整性及版本标签是否指向本次提交。只有 Release 页面出现通过验证的四个 APK 和 SHA256SUMS.txt，才算发布完成。
