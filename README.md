# HyperOS Mod

一个面向 **HyperOS 3 / Android 16 (SDK 36)** 的轻量级 LSPosed 模块，使用 **libxposed API 102**（LSPosed API 102）。

本模块从 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) 中提取了锁屏通知与主题色功能，参考 [MiFitnessAdAway](https://github.com/hao1196561270/MiFitnessAdAway) 恢复了小米运动健康的勿扰同步，并新增了基于 Android 系统安装器的应用更新保护。

## 功能

### 系统界面 · 锁屏

| 功能 | 说明 |
| --- | --- |
| 解除通知限制 | 强制 `ExpandedNotification#canShowOnKeyguard()` 返回 `true`，所有通知都可以在锁屏显示。 |
| 解锁后保留通知 | 在 `KeyguardNotificationVisibilityProviderImpl#shouldHideNotification()` 执行前清除 `mHasShownAfterUnlock`，解锁后通知不会自动消失。 |

### 系统界面 · 主题

| 功能 | 说明 |
| --- | --- |
| 自定义主题色 | 拦截 `ThemeOverlayController#createOverlays(int)`，用所选颜色替换壁纸取色，生成 Monet 主题。 |

> 主题色修改后，模块会通知 SystemUI 重新计算主题，通常立即生效；如果没有生效，可以点击应用内的“重启系统界面”按钮（需要 Root），或手动重启 SystemUI。

### 应用更新保护

在应用内选择需要保护的应用后，模块会在 `system_server` 的 `InstallPackageHelper` 准备安装阶段拒绝这些应用的**更新**：

- 所有安装器都走系统安装流程，因此 HyperOS 应用商店、Play 商店、APK 文件安装器都会被拦截；
- 只拦截“替换已安装应用”的安装，**首次安装不受影响**；
- 被拦截时安装器会收到 `INSTALL_FAILED_ABORTED`，应用商店通常会显示安装失败或中止；
- 再次进入选择页时，已选择的应用会置顶显示。

### 小米运动健康 · 勿扰同步

小米运动健康 3.59.x 在 Android 15+ 上把 `ZenUtils.isSupportZenMode()` 写死为 `false`，导致手环与手机的勿扰同步整体失效。本模块参考 MiFitnessAdAway 恢复这条链路：

1. 放行 `ZenUtils.isSupportZenMode()`，让官方同步逻辑重新工作；
2. 强制 `DeviceSettingsPreference.isZenModeOpen()` 读取为 `true`，并吞掉关闭写入；
3. 在 `FitnessApp.onCreate()` 后延时 10 秒重新注册 `zen_mode` 观察者；
4. 启动时主动调用一次 `getDeviceZenRules(Context)`，补齐“手环 → 手机”方向。

> 需要在系统设置中给「小米运动健康」授予**勿扰访问权限**。该权限由系统校验，模块无法代授；应用内提供了跳转入口。手环 → 手机方向在每次启动运动健康后同步一次，手机 → 手环是即时的。

### 界面适配

- 应用使用透明状态栏/导航栏，并根据浅色/深色主题自动切换状态栏图标颜色；
- 启动时检测应用列表权限，缺失时弹窗并提供系统设置跳转；
- 开启勿扰同步时检测小米运动健康的勿扰访问权限，并提供授权入口。

## 作用域

模块只声明**静态作用域**，安装后无需手动添加：

```text
system
com.android.systemui
com.mi.health
com.xiaomi.wearable
```

- `system`：system_server，用于应用更新保护；
- `com.android.systemui`：锁屏通知与主题色；
- `com.mi.health` / `com.xiaomi.wearable`：小米运动健康勿扰同步。

## 环境要求

- HyperOS 3 / Android 16（SDK 36）；
- 支持 **libxposed API 102** 的 LSPosed；
- Root（仅“重启系统界面”按钮需要）。

## 使用

1. 安装 APK；
2. 在 LSPosed 中启用 **HyperOS Mod**，确认静态作用域已生效；
3. 重启设备（首次启用必须重启，让 system_server、SystemUI 和运动健康加载模块）；
4. 打开应用，按需开启功能；
5. 开启“恢复勿扰模式同步”后，按提示给小米运动健康授予勿扰访问权限；
6. 主题色修改后，点“重启系统界面”或手动重启 SystemUI（通常无需）。

## 构建

### 本地构建

需要 JDK 21、Android SDK Platform 37.0、Build-Tools 37.0.0（libxposed service 102 要求 compileSdk 37）：

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

如果没有 `keystore.jks`，Release 会自动使用 debug 签名，方便直接安装测试。

### GitHub Actions

推送代码或手动触发 `Build` workflow 即可。CI 会：

1. 安装 JDK 21 与 Android SDK Platform 37.0 / Build-Tools 37.0.0；
2. 从 Secrets 读取签名密钥（未配置时回退 debug 签名）；
3. 构建 Release APK 并上传为 artifact；
4. 运行 Robolectric 布局冒烟测试；
5. **Push 到 `main` / `master` 时，自动创建或更新滚动预发布 `main-latest`，APK 会作为 Release 资产上传**；
6. 推送 `v*` tag 时自动创建带版本号的 GitHub Release。

可选 Secrets（不配置则使用 debug 签名）：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 Base64 内容 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

> 不配置签名 Secrets 时，CI 会使用 debug 签名，并缓存 `~/.android/debug.keystore` 以尽量保持多次构建的签名一致。若缓存被清除，新 APK 的签名会变化，覆盖安装会失败；长期使用建议配置上面的 Secrets。

## 代码结构

```text
app/src/main/
├── java/com/hyperosmod/
│   ├── App.java                     # 绑定 LSPosed service，同步远程配置
│   ├── Keys.java                    # 配置键（App 与 Hook 共用）
│   ├── data/Prefs.java              # 本地配置 + 远程配置镜像
│   ├── ui/
│   │   ├── MainActivity.java        # 设置主页 + 权限检测
│   │   ├── BlockedAppsActivity.java # 应用选择（已选置顶）
│   │   ├── ColorPickerDialog.java   # HSV 取色器
│   │   ├── PermissionHelper.java    # 应用列表 / 勿扰权限检查与跳转
│   │   └── UiUtils.java             # 透明状态栏与图标明暗
│   └── xposed/
│       ├── ModuleEntry.java         # libxposed API 102 入口
│       ├── HookPrefs.java           # Hook 进程读取远程配置
│       ├── SystemUiHooks.java       # 锁屏通知 + 主题色
│       ├── UpdateGuard.java         # 应用更新保护
│       └── MiFitnessHooks.java      # 小米运动健康勿扰同步
└── resources/META-INF/xposed/
    ├── module.prop                  # API 102 / 静态作用域
    ├── java_init.list               # 模块入口
    └── scope.list                   # system + SystemUI + 运动健康
```

## 实现说明

### 配置同步

LSPosed API 102 提供远程 SharedPreferences。应用进程把配置写入自己的私有 SharedPreferences，并在 LSPosed service 连接后镜像到 `hyperosmod_prefs` 组；Hook 进程通过 `XposedInterface#getRemotePreferences()` 读取。

### 更新保护为什么放在 `InstallPackageHelper`

`PackageInstallerService#createSession()` 阶段通常还拿不到 APK 的真实包名。`InstallPackageHelper#preparePackage()` 位于 APK 解析之后、真正写入系统之前，此时 `InstallRequest#getName()` 已经是 APK 内的包名，可以准确判断是否是受保护应用的更新，并抛出 Android 原生的 `PrepareFailure` 中止安装。

### 勿扰同步为什么要重新注册观察者

Android 15+ 上官方在 `isSupportZenMode()` 处直接返回 `false`，而 `FitnessApp.onCreate()` 中的 `registerZenListener()` 在模块放行之前就已经被挡掉。因此只 Hook 判断还不够，必须在启动后重新注册一次 `zen_mode` ContentObserver，并主动拉取一次手环规则。

## 已知限制

- 仅针对 HyperOS 3 / Android 16 的类名和方法签名；其他版本未做适配。
- 被保护应用仍可通过“先卸载再安装”的方式绕过（这属于全新安装）。
- 主题色使用 SystemUI 的私有方法；如果 HyperOS 后续修改该方法签名，需要同步调整。
- 更新保护通过系统安装器实现，不会阻止应用商店下载 APK，只会在安装阶段拒绝。
- 勿扰同步依赖小米运动健康自身的类；运动健康版本变化后可能需要更新 Hook。
- 勿扰访问权限由系统校验，模块只能提示和跳转，不能代授。

## 致谢与许可

锁屏通知限制、解锁后保留通知、自定义主题色的实现思路来自 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)；勿扰同步实现参考 [MiFitnessAdAway](https://github.com/hao1196561270/MiFitnessAdAway)。两者均为 AGPL-3.0。本仓库同样以 **AGPL-3.0** 发布，详见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

本项目与小米、HyperOS、LSPosed 官方无隶属关系。使用风险自负。

