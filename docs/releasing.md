# 发布流程

发布版本使用 `app/build.gradle` 的 `versionName` 与单调递增的 `versionCode`。tag 必须为 `v<versionName>`，且 tag 的提交已进入 `origin/main`。

## 验证与提交

1. 核对工作树、版本、变更与 [验证记录](validation.md)，保留其他人的未提交编辑。只提交本次已审阅的文件；执行 `git diff --cached --check` 并检查凭证、设备数据和签名材料。
2. 串行完成构建检查：

   ```sh
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
     :app:assembleDebug :app:assembleRelease :app:assemblePreview \
     :app:assembleDebugAndroidTest
   ```

3. 按实际授权和变更范围执行设备验收，区分确定性 fixture 与真实模型结果。版本元数据重建不等于重跑整套实机任务。
4. 提交已审阅改动，普通快进推送 `main`，创建并推送匹配版本的 tag；不重写旧版本 tag。

## 产物与源码边界

`scripts/package_release.py` 读取执行目录所在项目的文件系统。它的递归目录可能包含未提交或被忽略的文件，因此本地发版应先用 `git archive` 将最终提交导出到新的忽略目录，再把该提交对应构建的 Release／Preview APK 复制到导出目录的相同构建路径，最后从导出目录执行打包脚本。不要为了打包恢复、清理或提交其他人的工作树。

宣传素材 `docs/promo/` 已被 Git 忽略，打包脚本也显式排除该目录。

核验源码 ZIP 的每个文件均对应发布提交，不包含 `.DS_Store`、本机配置、凭证、签名密钥、`work/`、`recon/` 或 `.tools/`。仅分发当前版本的已审阅文件，校验 SHA256 与 APK 包名、版本、签名。Preview 使用开发签名，不能作为正式签名升级包发布。

## GitHub 签名与预发布

推送版本 tag 后，`.github/workflows/release.yml` 重新执行测试、双变体 Lint 和构建；它在 `release` environment 中读取 GitHub Secrets 签名，生成签名 APK、源码包、签名证书报告和校验文件，创建或更新草稿 Release。不要从 runner 导出私钥或密码。

等待签名工作流成功，检查产物与提交对应，并设置清晰的版本说明。预览版本必须显式设置 GitHub `prerelease=true`；工作流默认只创建草稿，不会自动发布。只有用户已授权发布时才将草稿改为公开预发布，否则保持草稿供审阅。旧版本默认保留；移除或改写旧 Release／tag 需要明确指示。
