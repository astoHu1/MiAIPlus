# 发布

构建环境：JDK 17+、Android SDK platform 37、build-tools 37.0.0；运行时需要支持 libxposed API 102 的 LSPosed。

本项目不绑定服务商。默认 API 地址、密钥和模型名均为空；不要把维护者个人端点放进默认值或示例安装配置。

1. 完成单元测试、Lint、debug 设备测试及实际支持的小爱版本回归。
2. 构建 `:app:assembleRelease :app:assemblePreview`，用 `scripts/package_release.py` 生成干净源码包和未签名 APK。
3. 使用自己的长期私有 keystore 签名。不要提交 keystore、密码、真实端点配置或测试设备数据。

```sh
# apksigner 在 Android SDK build-tools 目录中；使用交互式密码提示
apksigner sign --ks /你的私有目录/release.jks --out MiAIPlus-signed.apk dist/MiAIPlus-0.3.3-unsigned.apk
apksigner verify --verbose MiAIPlus-signed.apk
```

Preview 使用优化的非调试构建与本机开发签名，不含 debug 测试页面及广播接收器。不要将 Debug APK 作为日常配置界面性能版本。

开发签名的 preview APK 仅供试用，不是正式发布签名。正式签名无法直接覆盖开发签名版，先导出所需设置并卸载测试版；不自动迁移或发布任何个人密钥。

仅上传 `dist/` 中经检查的产物；不要上传整个工作目录。源码包包括项目自身代码、构建文件、许可、测试与说明，排除 `work/`、`recon/`、`.tools/`、`local.properties`、设备日志、调试签名等。
APK 内会包含兼容性 Hook 所需的小爱包名/类名，不包含小爱 APK 或反编译源码。

发布说明应注明精确支持版本及已验证范围。默认模式无需外部 API，不等于官方模型离线或具备 AutoGLM 权重；全局替换指 OSBot 推理入口，语音和独立系统云服务仍走原链路。

## 首次创建正式签名

在自己的电脑上生成一次长期密钥；不要放进项目目录。下列命令会交互询问密码：

```sh
mkdir -p "$HOME/.android-signing"
keytool -genkeypair -v -keystore "$HOME/.android-signing/miaiplus-release.jks" \
  -alias miaiplus -keyalg RSA -keysize 4096 -validity 10000
```

离线备份 keystore、别名、keystore 密码和 key 密码。后续升级必须使用同一密钥，并递增 `versionCode`。遗失密钥后，已有安装无法直接升级；不要每次 CI 生成新密钥。

构建后在本机签名（`apksigner` 位于 Android SDK 的 `build-tools/37.0.0/`）：

```sh
./gradlew :app:assembleRelease
apksigner sign --ks "$HOME/.android-signing/miaiplus-release.jks" \
  --ks-key-alias miaiplus --out MiAIPlus-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs MiAIPlus-signed.apk
```

首次记录证书 SHA-256 指纹，后续发布核对一致。保留签名后的 APK；修改 APK 内容会使签名失效。

## GitHub Actions 与后续签名自动化

`.github/workflows/ci.yml` 在 main push、PR 和手动触发时执行 JVM/Robolectric 测试、完整 Debug/Release Lint、Debug/Release/Preview/AndroidTest 构建。成功上传 `dist/`，检查报告失败时也尽可能上传；保留 14 天。它不访问真机、不使用正式密钥、不创建 GitHub Release。Preview 使用 runner 的临时开发签名，不能依赖跨运行覆盖安装。

当前 workflow 不需要 Secrets。要以后启用正式签名，先在 GitHub Settings → Environments 新建 `release` 环境，限制 main 或发布标签并按需开启人工审批，再在该环境配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 keystore 的 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 例如 `miaiplus` |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

macOS 可用 `base64 -i "$HOME/.android-signing/miaiplus-release.jks" | pbcopy` 复制编码后直接粘贴到 Secret；Base64 不是加密，勿提交编码文件或贴到聊天。密钥无需提供给开发 agent。

签名应放进单独的受保护 release job/workflow，只允许可信提交或手动发布；不要让 PR（尤其 `pull_request_target` 检出 PR 代码）接触密钥。后续接入时从 Secret 解码到 runner 临时目录，用 `apksigner` 的 `env:` 密码参数签名、验证并清理临时密钥；仅在签名完成后生成最终校验值。上述 Secrets 目前仅为预留配置，现有 CI 不会读取它们。
