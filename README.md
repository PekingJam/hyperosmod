# HyperOS Mod

一个面向 **HyperOS 3 / Android 16 (SDK 36)** 的 LSPosed 模块，使用 **libxposed API 102**（LSPosed API 102）。

功能来源：

- 锁屏通知与主题色：参考 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)
- 小米运动健康勿扰同步：参考 [MiFitnessAdAway](https://github.com/hao1196561270/MiFitnessAdAway)
- HyperOS Passkey 修复：整合自 [HyperPasskey](https://github.com/Howard20181/HyperPasskey)
- 应用更新保护、电话/SIM 信息伪装、设置界面与权限检测：本项目新增

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

> 需要在系统设置中给「小米运动健康」授予**勿扰访问权限**。该权限由系统校验，模块无法代授；应用内提供了跳转入口。若小米运动健康已开启「通知访问权限」，系统会强制启用勿扰访问，模块也会将其识别为已授权。手环 → 手机方向在每次启动运动健康后同步一次，手机 → 手环是即时的。

### HyperOS Passkey 修复

整合 HyperPasskey 的实现，修复 HyperOS 上 Passkey/凭据管理入口不显示、无法正常使用的问题：

- Hook `system_server` 的 `IntentFactory` / `RequestSession`；
- Hook `com.android.settings` 的凭据管理相关页面；
- Hook `com.miui.securitycenter` 与 `com.xiaomi.scanner` 的相关逻辑；
- 使用 DexKit 定位 HyperOS 混淆后的方法，需要设备为 **arm64-v8a**。

> 该功能通常还需要开启基础 Google 服务（GMS）。

### 电话 / SIM 信息伪装

为开发和测试需要读取设备信息的应用提供可配置的伪装值，支持按 SIM 卡分别设置：

| 字段 | 对应 API |
| --- | --- |
| 电话号码 | `getPhoneNumber()` / `getLine1Number()` |
| SIM 序列号 | `getSimSerialNumber()` |
| 网络国家码 | `getNetworkCountryIso()` |
| 网络运营商 | `getNetworkOperator()` |
| 网络运营商名称 | `getNetworkOperatorName()` |
| SIM 国家码 | `getSimCountryIso()` |
| SIM 运营商 | `getSimOperator()` |
| SIM 运营商名称 | `getSimOperatorName()` |

同时处理：

- 带 `subId` 的重载，通过 `SubscriptionManager.getSlotIndex()` 映射到 SIM 1 / SIM 2；
- `SubscriptionInfo#getNumber/getIccId/getCountryIso/getMcc/getMnc/getCarrierName` 等对象 getter；
- `getPhoneCount()` / `getActiveModemCount()` 返回 2，`getSimState()` 返回 READY，便于目标应用枚举双卡。

内置地区模板：

- 美国 / United States
- 新加坡 / Singapore
- 中国香港 / Hong Kong
- 中国台湾 / Taiwan
- 日本 / Japan

选择一个模板会同时填充 SIM 1 / SIM 2，可继续手动修改；也可以把 SIM 1 复制到 SIM 2。只对“目标应用”中勾选的应用生效。

### 界面适配

- 应用使用透明状态栏/导航栏，并根据浅色/深色主题自动切换状态栏图标颜色；
- 启动时检测应用列表权限，缺失时弹窗并提供系统设置跳转；
- 开启勿扰同步时检测小米运动健康的勿扰访问权限，并提供授权入口。

## 作用域（重要）

从 1.2.0 开始模块**不再使用静态作用域**，需要在 LSPosed 中手动勾选。`scope.list` 仍然提供推荐列表：

```text
system
com.android.systemui
com.mi.health
com.xiaomi.wearable
com.android.settings
com.miui.securitycenter
com.xiaomi.scanner
```

建议按需勾选：

- 使用锁屏/主题色：勾选 `com.android.systemui`；
- 使用应用更新保护：勾选 `system`；
- 使用运动健康勿扰同步：勾选 `com.mi.health`（旧版另加 `com.xiaomi.wearable`）；
- 使用 Passkey 修复：勾选 `system`、`com.android.settings`、`com.miui.securitycenter`、`com.xiaomi.scanner`；
- 使用电话/SIM 伪装：**必须额外勾选你要测试的目标应用**（例如你自己的 APK 或其包名），否则模块不会加载到该应用进程。

## 环境要求

- HyperOS 3 / Android 16（SDK 36）；
- 支持 **libxposed API 102** 的 LSPosed；
- **arm64-v8a** 设备（Passkey 修复依赖 DexKit 原生库）；
- Root（仅“重启系统界面”按钮需要）。

## 使用

1. 安装 APK；
2. 在 LSPosed 中启用 **HyperOS Mod**；
3. **手动勾选**需要的应用（见上方作用域说明）；
4. 重启设备（首次启用必须重启，让 system_server、SystemUI、运动健康等进程加载模块）；
5. 打开应用，按需开启功能；
6. 电话/SIM 伪装：先选择“目标应用”，再编辑 SIM 1 / SIM 2 信息并保存；
7. 开启“恢复勿扰模式同步”后，按提示给小米运动健康授予勿扰访问权限；
8. 主题色修改后，点“重启系统界面”或手动重启 SystemUI（通常无需）。

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
5. **Push 到 `main` / `master` 时，自动创建或更新滚动 Release `main-latest`，APK 会作为 Release 资产上传**；
6. 推送 `v*` tag 时自动创建带版本号的 GitHub Release。

Release 资产名统一使用模块的 `versionName`，例如 `HyperOSMod-1.2.0.apk`；主分支滚动构建会在版本号不变时覆盖同一资产，tag 构建会生成对应版本的资产。

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
│   │   ├── BlockedAppsActivity.java # 应用选择（更新保护 / 伪装目标，已选置顶）
│   │   ├── TelephonySettingsActivity.java # SIM 1/2 信息与地区模板
│   │   ├── TelephonyPreset.java     # 美国/新加坡/香港/台湾/日本模板
│   │   ├── ColorPickerDialog.java   # HSV 取色器
│   │   ├── PermissionHelper.java    # 应用列表 / 勿扰权限检查与跳转
│   │   └── UiUtils.java             # 透明状态栏与图标明暗
│   └── xposed/
│       ├── ModuleEntry.java         # libxposed API 102 入口
│       ├── HookPrefs.java           # Hook 进程读取远程配置
│       ├── SystemUiHooks.java       # 锁屏通知 + 主题色
│       ├── UpdateGuard.java         # 应用更新保护
│       ├── MiFitnessHooks.java      # 小米运动健康勿扰同步
│       └── TelephonyHooks.java      # 电话 / SIM 信息伪装
├── java/io/github/howard20181/hyperpasskey/ # Passkey 修复（来自 HyperPasskey）
└── resources/META-INF/xposed/
    ├── module.prop                  # API 102 / 手动作用域
    ├── java_init.list               # 两个入口：ModuleEntry + PasskeyHook
    └── scope.list                   # 推荐作用域
hiddenapi-stubs/                     # HyperPasskey 使用的隐藏 API 编译桩
```

## 实现说明

### 配置同步

LSPosed API 102 提供远程 SharedPreferences。应用进程把配置写入自己的私有 SharedPreferences，并在 LSPosed service 连接后镜像到 `hyperosmod_prefs` 组；Hook 进程通过 `XposedInterface#getRemotePreferences()` 读取。

### 更新保护为什么放在 `InstallPackageHelper`

`PackageInstallerService#createSession()` 阶段通常还拿不到 APK 的真实包名。`InstallPackageHelper#preparePackage()` 位于 APK 解析之后、真正写入系统之前，此时 `InstallRequest#getName()` 已经是 APK 内的包名，可以准确判断是否是受保护应用的更新，并抛出 Android 原生的 `PrepareFailure` 中止安装。

### 勿扰同步为什么要重新注册观察者

Android 15+ 上官方在 `isSupportZenMode()` 处直接返回 `false`，而 `FitnessApp.onCreate()` 中的 `registerZenListener()` 在模块放行之前就已经被挡掉。因此只 Hook 判断还不够，必须在启动后重新注册一次 `zen_mode` ContentObserver，并主动拉取一次手环规则。

### 电话/SIM 伪装如何覆盖所有卡

- 无参 getter 使用设置中的“默认卡”；
- 带 `subId` 的 getter 通过 `SubscriptionManager.getSlotIndex(subId)` 映射到 SIM 1 / SIM 2；
- `SubscriptionInfo` 对象 getter 通过 `getSimSlotIndex()` 选择对应配置；
- `getPhoneCount()` / `getActiveModemCount()` 返回 2，让目标应用按双卡枚举。

## 已知限制

- 仅针对 HyperOS 3 / Android 16 的类名和方法签名；其他版本未做适配。
- Passkey 修复依赖 DexKit 原生库，当前只打包 **arm64-v8a**。
- 电话/SIM 伪装只能 Hook 常见 `TelephonyManager` / `SubscriptionInfo` API；如果目标应用使用更底层的 Binder/订阅数据库接口，可能读不到伪装值。
- 目标应用必须同时在 LSPosed 作用域和模块的“目标应用”列表中，二者缺一不可。
- 被保护应用仍可通过“先卸载再安装”的方式绕过更新保护（这属于全新安装）。
- 主题色使用 SystemUI 的私有方法；如果 HyperOS 后续修改该方法签名，需要同步调整。
- 更新保护通过系统安装器实现，不会阻止应用商店下载 APK，只会在安装阶段拒绝。
- 勿扰同步依赖小米运动健康自身的类；运动健康版本变化后可能需要更新 Hook。
- 勿扰访问权限由系统校验，模块只能提示和跳转，不能代授。

## 致谢与许可

- 锁屏通知限制、解锁后保留通知、自定义主题色：来自 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)（AGPL-3.0）
- 勿扰同步：参考 [MiFitnessAdAway](https://github.com/hao1196561270/MiFitnessAdAway)（AGPL-3.0）
- Passkey 修复与 hiddenapi stubs：来自 [HyperPasskey](https://github.com/Howard20181/HyperPasskey)（GPL-3.0，AGPL-3.0 项目可兼容整合）

本仓库以 **AGPL-3.0** 发布，详见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

本项目与小米、HyperOS、LSPosed 官方无隶属关系。使用风险自负。

