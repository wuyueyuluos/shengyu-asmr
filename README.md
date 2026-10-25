# 声屿 ASMR（原生 Android 项目起步版）

这是一个原生 Android Studio 项目源码，不是已经编译好的 APK。

## 当前实现
- 原生 Android 界面与作品页
- 通过 Android 文件夹授权选择作品文件夹
- 应用内扫描子文件夹，识别常见音频、图片与 TXT/LRC/SRT 台本
- 文件勾选导入
- 本地音频播放、上一首/下一首、自动下一首、音量调节
- 插画预览；点击切换台本页（基础版）

## 尚未实现/限制
- 目前只把所选文件保存在本次运行内存中，退出后需要重新导入；下一步应加入 Room/持久化保存作品库。
- 台本暂时显示整份文本，尚未实现按音频时间戳同步高亮滚动。
- PDF 台本、图片左右滑动手势、后台播放/通知栏控制、完善的多作品管理尚未实现。
- Android 系统会限制部分受保护目录的访问；请优先选择自己有权限的普通文件夹。
- 此版本尚未在真实设备上编译安装测试。

## 如何生成 APK
1. 在电脑安装 Android Studio（含 Android SDK）。
2. 解压项目并在 Android Studio 中选择“Open”，打开 `shengyu_asmr_android` 文件夹。
3. 等待 Gradle Sync 完成；如提示升级 Gradle/插件，先保持项目建议版本。
4. 选择 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
5. 构建成功后，Android Studio 会显示 APK 所在路径。

建议下一步先把工程编译跑通，再添加作品库持久化、PDF 台本和逐句同步功能。

## GitHub 云端构建（手机操作）
项目已附带 `.github/workflows/android.yml`。把项目文件上传到 GitHub 仓库的 `main` 分支后，打开仓库的 Actions 页面，运行 `Build Android APK` 工作流。构建成功后进入该次运行页面，在 Artifacts 区域下载 `shengyu-asmr-debug-apk`，解压即可得到 `app-debug.apk`。
