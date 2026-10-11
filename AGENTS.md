本 fork 只维护 Android。不要再添加 iOS 工程、Xcode 构建或 macOS 产品说明，也不要发布 App Store 包或 `.ipa`。

复盘记录：

- 界面与字符串以 Android 的 `app` 为准。共享层 `shared/src/commonMain` 仍会被 Android 编译；改完后用 `./gradlew :app:assembleDebug` 验证，不能只看共享代码就认为已经生效。
- Compose 中使用 `Box` 叠加阴影时，`matchParentSize()` 子项不参与父容器尺寸测量。父容器必须至少有一个正常参与测量的内容子项（例如固定高度的 `Row`），否则胶囊、按钮等控件可能宽度变为 0 而完全消失。
- 安装完成后，必须用实际连接设备核对包名和安装结果，不能只看构建成功。
- 搜索文件或代码时，默认始终排除缓存目录、第三方源码目录、构建目录及其他生成文件目录，避免被无用信息淹没；除非明确知道自己需要搜索这些目录。

真机安装流程：

- 先用 `adb devices -l` 确认目标 serial，再执行 `./gradlew :app:assembleDebug --no-daemon` 和 `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` 直接覆盖安装正式 APK。默认禁止构建或安装 `app-debug-androidTest.apk` 等测试包，除非用户明确要求。安装后必须执行 `adb -s <serial> shell pm path com.baimoqilin.aether`，并用 `dumpsys package` 核对版本信息。多设备环境禁止使用不带 `-s` 的 `adb install`。
