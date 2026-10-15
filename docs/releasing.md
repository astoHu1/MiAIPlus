# 发布

本项目不绑定服务商。默认 API 地址、密钥和模型名均为空；不要把维护者个人端点放进默认值或示例安装配置。

1. 完成单元测试、Lint、debug 设备测试及实际支持的小爱版本回归。
2. 构建 `:app:assembleRelease`，用 `scripts/package_release.py` 生成干净源码包和未签名 APK。
3. 使用自己的长期私有 keystore 签名。不要提交 keystore、密码、真实端点配置或测试设备数据。

```sh
# apksigner 在 Android SDK build-tools 目录中；使用交互式密码提示
apksigner sign --ks /你的私有目录/release.jks --out MiAIPlus-signed.apk dist/MiAIPlus-0.2.3-unsigned.apk
apksigner verify --verbose MiAIPlus-signed.apk
```

开发签名的 preview APK 仅供试用，不是正式发布签名。正式签名无法直接覆盖开发签名版，先导出所需设置并卸载测试版；不自动迁移或发布任何个人密钥。

仅上传 `dist/` 中经检查的产物；不要上传整个工作目录。源码包包括项目自身代码、构建文件、许可、测试与说明，排除 `work/`、`recon/`、`.tools/`、`local.properties`、设备日志、调试签名等。
APK 内会包含兼容性 Hook 所需的小爱包名/类名，不包含小爱 APK 或反编译源码。

发布说明应注明精确支持版本及已验证范围。默认模式无需外部 API，不等于官方模型离线或具备 AutoGLM 权重；全局替换指 OSBot 推理入口，语音和独立系统云服务仍走原链路。
