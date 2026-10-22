# DirTunnel 需求实现映射（REQ/TECH → 代码）

本文档逐条对应 `docs/REQ.md` 与 `docs/TECH.md`，给出实现位置，用于验收核对。
约定：路径为模块内 `src/main/java/com/dirtunnel/` 下的相对路径（省略公共前缀）。

---

## 一、技术栈选型（TECH 一）

| 条目 | 实现 |
|---|---|
| Kotlin 为主 | 全部源码为 Kotlin（`*.kt`），Gradle KTS |
| minSdk 26 / targetSdk 35 / compileSdk 35 | 各模块 `build.gradle.kts` `minSdk = 26`、`targetSdk = 35`、`compileSdk = 35` |
| Gradle Kotlin DSL | `settings.gradle.kts`、`build.gradle.kts`、各模块 KTS，版本统一在 `gradle/libs.versions.toml` |
| Jetpack Compose + 自研 iOS 组件、**放弃 Material3** | 无任何 `androidx.compose.material3` 依赖；组件全部自研于 `core/ui/components/`；仅使用 material 的**图标字形**（`material-icons-core/extended`） |
| MVVM + Clean 分层 | 每个 feature：Screen(UI) → ViewModel → Repository/RuntimeController → core 底层服务；无 ViewModel 直接操作 UI |
| Hilt | `DirTunnelApp` `@HiltAndroidApp`、`MainActivity` `@AndroidEntryPoint`、全部注入点 `@Inject`、DI：`core/storage/di/StorageModule.kt` |
| Coroutine + Flow | 状态全部为 `StateFlow`（采样/聚合），见各 ViewModel 与 runtime controller |
| DataStore | `core/storage/data/SettingsStore.kt`（设置/主题/端点等） |
| Room | `core/storage/data/AppDatabase.kt` + `dao/*`（Directory/Service/Tunnel/Log） |
| SAF + MediaStore + File | `core/storage/saf/SaFDocumentTree.kt`（树选择、持久化授权、tree→真实路径解析供子进程挂载） |

## 二、底层协议引擎（TECH 二）

| 条目 | 实现 |
|---|---|
| 静态编译引擎 + app 内托管 | `core/engine/process/BinaryManager.kt`：jniLibs（`native: true` 条目）与 assets 解包（V1.1/V1.2 条目）双通道、`binaries.json` 清单、**md5 校验防篡改**（TECH 六.6） |
| 进程内托管（V1.0 协议）+ 进程管理（V1.1+） | `core/engine/process/NativeEngine.kt`（JNI 加载 `libdirtunneld.so`，StartServer/StopServer 句柄式）+ `ProcessSupervisor.kt`（`ProcessSpec.execNative` 分支：状态机、日志、健康监视、自动重启复用；进程形态保留 ProcessBuilder/pid/stdout 捕获） |
| 配置组装（各协议） | `core/engine/config/EngineConfigBuilder.kt`：FTP/FTPS(ftpd.conf+accounts)、SFTP(subsystem sftp)、HTTP/WebDAV(minihttpd)、SMB、Rsync、NFS；TLS cert/key 引用（V1.1 自定义证书） |
| 端口监听检测 | `core/engine/process/PortChecker.kt`：0.0.0.0 与 127.0.0.1 双视角绑定检测、`conflict()` 带解决方案、`findFreePort()` |
| FRP 官方二进制、图形化包装 | `feature/tunnel/runtime/FrpcConfigBuilder.kt`（ini 生成）+ `TunnelRuntimeController.kt`（启停、外网地址、二维码） |
| ABI arm64-v8a 主力 / armeabi-v7a 兼容 | `binaries.json` 按 abi 组织；gradle `ndk.abiFilters` 规划 |

**运行时架构（实现决策）**：引擎只监听 `127.0.0.1:internalPort`；对外端口由 `core/engine/proxy/TcpProxyServer.kt` 绑定，统一执行白名单过滤 → 连接数限制 → 令牌桶限速 → 双向转发 → 流量记账，供仪表盘与日志读取。

> **TECH 二 偏差记录（2026-10-22）——子进程 → 进程内**：
> Android 10+（API 29，SELinux W^X）禁止 `untrusted_app` exec 应用数据目录
> 中的文件，ProcessBuilder 形态在真机报 `Cannot run program ... error=13`，
> 「静态编译 + 托管」在现代 Android 上只能进程内落地。V1.0 因此将 dirtunneld
> 编译为 Go c-shared（`jniLibs/arm64-v8a/libdirtunneld.so`，gomobile/Tailscale
> 同款架构），`-tags cshared -buildmode=c-shared`；启动/停止经 JNI 句柄，
> 其余全部不变：0600 配置经路径传入、只绑 127.0.0.1、对外过代理层、md5 校验、
> 状态机/健康监视/自动重启复用 `ProcessSupervisor`。独立二进制形态保留
> （`-tags !cshared`）供容器内回归冒烟。V1.1+ 的 smbd/rsync/nfsd/frpc 为外部
> 项目二进制，落地时按同一 W^X 约束选形态。

> **V1.1 偏差记录（2026-10-22）——frpc 子进程 → 进程内（同一 W^X 约束）**：
> frpc 与 dirtunneld 面对完全相同的 SELinux W^X 限制，子进程形态在 Android 10+
> 必然 `error=13`，故将 frp **官方 client 库**（`github.com/fatedier/frp v0.61.0`）
> 作为 Go 依赖编入 `libdirtunneld.so`，新增引擎模式 `frpc`
> （`tools/dirtunneld/frp.go`，`config.LoadClientConfig` 直读 Kotlin 生成的
> frpc TOML，零格式转换）。`binaries.json` 中 frpc 条目改为 native（同一 .so）。
> 连带决策：①frp v0.61 客户端日志已不再打印服务器分配端口，隧道远端端口改经
> frp 本地 admin API（`webServer`，仅绑 127.0.0.1，App 每次启动扫描全新空闲口）
> `/api/status` 轮询 `remote_addr` 发现；②frp 上游 `Service.Run` 停止时不关闭
> webServer 监听（进程退出才回收）——进程内形态下每次启停泄漏一个监听口，
> 以 2 万口扫描范围（17300..37299）稀释，后续可改为经 `StatusExporter()`
> 进程内直查、彻底不开端口；③代价：.so 由 6.9MB 增至 15.1MB（含 quic-go/
> kcp-go 等传输依赖），后续可裁剪未用传输层瘦身。
> 容器 E2E：frps + frpc 模式 + 动态端口全链路 401/200、dlopen 双轮重启全过
> （`dtest/test_c2.sh`、`dtest/test_d.sh`）。

## 三、权限清单（TECH 三）

全部声明于 `app/src/main/AndroidManifest.xml`：
`READ/WRITE_EXTERNAL_STORAGE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`、`FOREGROUND_SERVICE_TYPE_DATA_SYNC`、`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`POST_NOTIFICATIONS`；SAF 文档树授权经 `ACTION_OPEN_DOCUMENT_TREE` 运行时请求（`feature/directory/DirectoryListScreen.kt`）。前台服务常驻通知：`app/service/DirTunnelForegroundService.kt`（含 WakeLock 开关）。

## 四、UI 视觉规范（TECH 四）

| 条目 | 实现 |
|---|---|
| iOS 圆角按钮 12px / 主填充次描边 | `core/ui/components/AppleButton.kt` |
| 分组列表 Grouped List（浅灰标题、细分割线、圆角单元格） | `AppleGroupList.kt`（`AppleSection` + `AppleCell`） |
| iOS UISwitch 复刻 | `AppleSwitch.kt` |
| iOS 圆角弹窗 + 变暗蒙版 | `AppleAlertDialog.kt`、`AppleInputDialog.kt`、`AppleOptionSheet.kt` |
| 平缓低阻尼动画 | 各组件 `tween/spring` 低阻尼参数 |
| **悬浮毛玻璃导航（1~7 全条）** | `AppleFloatingTopBar.kt` + `Glassmorphism.kt`（`appleGlass`）：距顶/左右留边、16dp 圆角、Haze 毛玻璃、滚动置顶与模糊强度变化（`scrollOffset`）、层级高于内容低于弹窗、深浅色自动切换；**不使用 TopAppBar**。TECH 七.6 低版本降级：Haze 不支持时回落 `floatingBarBg` 半透明纯色（`Glassmorphism.kt` 注释与 `Theme.kt` 中 `LocalHazeStyle fallbackTint`） |
| 三主题模式 | `SettingsStore` 存 `SYSTEM/LIGHT/DARK`；`MainActivity` 直接观察并传入 `AppleTheme`；`core/ui/theme/Theme.kt` + `ThemeMode.kt` |
| 精确色值 | `core/ui/theme/Color.kt`：浅色 `#007AFF/#F2F2F7/#FFFFFF/#000000/#8E8E93/#C6C6C8/#F8F8F8CC/#D1D1D6/#FF3B30`；深色 `#0A84FF/#000000/#1C1C1E/#FFFFFF/#8E8E93/#38383A/#2C2C2ECC/#48484A/#FF453A`（与 TECH 表格逐一一致） |
| 全局组件复用 | 所有 Screen 仅使用 `core:ui` 组件与图标字形 |

## 五、模块拆分（TECH 五 · 恰好 10 个）

`settings.gradle.kts` 恰好 include 10 个：`app`、`feature:directory`、`feature:service`、`feature:tunnel`、`feature:log`、`feature:setting`、`core:ui`、`core:engine`、`core:storage`、`core:network` — 与 TECH 五列表一一对应。

## 六、安全设计要点（TECH 六）

| 条目 | 实现 |
|---|---|
| 1 账号密码/token EncryptedSharedPreferences 加密 | `core/storage/data/SecurityStore.kt`（MasterKey + EncryptedSharedPreferences；Keystore 失败降级并 UI 提示） |
| 2 敏感信息不进命令行、临时配置 0600 | frpc token 由 `FrpcConfigBuilder` 写入私有配置文件（`File.setReadable(false,false)` 等 0600 权限），进程参数不含明文 token |
| 3 默认关闭匿名访问 | 服务实体 `anonymousEnabled` 默认 false，编辑页独立匿名开关（`feature/service/ServiceEditScreen.kt`） |
| 4 前台通知不泄露账号密码 | `DirTunnelForegroundService` 通知渠道文案仅含服务名/状态 |
| 5 日志可选择不记录密码 | 设置页"日志脱敏"开关 → `SettingsStore`；写入端过滤（`ServiceRuntimeController` 日志脱敏路径） |
| 6 二进制 md5 校验 | `BinaryManager` 校验 `binaries.json` md5；设置页展示组件 md5（`feature/setting/SettingScreen.kt`） |

## 七、已知风险与规避（TECH 七）

| 风险 | 实现 |
|---|---|
| 1 电池优化/应用休眠 | `core/engine/health/SystemHealthMonitor.kt`（电池受限检测 `batteryRestricted`、忽略优化检测）+ 前台服务 + 设置页引导跳转电池白名单；WakeLock 开关 |
| 2 SELinux 拦截 | 子进程启动失败捕获 → `ProcessState.Failed(message, solution)` 给出可执行提示 |
| 3 端口冲突 | 启动前 `PortChecker.conflict()`，占用时弹错误附带更换端口/关闭占用进程方案 |
| 4 SAF 权限重启失效 | `SaFDocumentTree.takePersistable()` 对所有目录持久化授权 |
| 5 二进制体积 | arm64 为主、armeabi-v7a 可选分包（清单按 abi 组织） |
| 6 毛玻璃兼容性 | `Glassmorphism.kt` 低版本自动降级半透明纯色（同上） |

## 八、打包发布（TECH 八）

`gradle assembleDebug` 通过，产出 `app/build/outputs/apk/debug/app-debug.apk`；release 签名与 AAB/GitHub Release 流程留待发布阶段配置。

---

# REQ.md 逐条映射

## 三、协议规划

| 版本 | 协议 | 实现 |
|---|---|---|
| V1.0 | FTP / FTPS | `EngineConfigBuilder.ftpConfig(tls)`（portable ftpd，FTPS 复用同进程 + TLS） |
| V1.0 | SFTP | `EngineConfigBuilder` SFTP 子系统（openssh sftp-server） |
| V1.0 | HTTP | `EngineConfigBuilder` minihttpd 静态服务 |
| V1.0 | WebDAV（+HTTPS） | 同 httpd 的 webdav 模块，独立实例可选 |
| V1.1 | SMB2/3（默认禁 SMB1） | SMB 配置生成（smbd 静态编译），协议枚举含 SMB |
| V1.1 | HTTPS 自定义证书 | 服务编辑页 `useCustomCert + certDir`；`EngineConfigBuilder.tlsFiles()` 引用 cert.pem/key.pem；设置页证书导入 |
| V1.2 | Rsync / NFS（默认隐藏） | 协议枚举 + 高级开关控制显示（`SettingsStore` 高级功能开关） |
| 穿透独立 | 自定义本地 TCP 转发 | `feature/tunnel/TcpForwardEditScreen.kt` + `TunnelRuntimeController` TCP 转发运行时（app 外服务经 FRP 转发） |

## 四、核心功能需求

### 4.1 目录管理
1. 任意目录浏览选择（内部存储/SD 卡/分区），SAF：`DirectoryListScreen`（`OpenDocumentTree`）+ `SaFDocumentTree`；tree Uri → 真实文件系统路径解析供子进程挂载。
2. 目录收藏一键复用：`DirectoryEntity.favorite` + 收藏过滤。
3. 单目录多协议实例、各实例独立端口/账号/权限/穿透开关：`ServiceInstanceEntity`（directoryId 外键 + 独立字段），服务列表按目录分组。
4. 权限：只读 / 读写 / 禁止删除：`AccessPermission` 枚举（READ_ONLY / READ_WRITE / NO_DELETE），`EngineConfigBuilder.readonly()/allowDelete()` 下发到子进程配置。

### 4.2 服务实例管理
- 属性齐全（绑定目录、协议、端口、账号密码 + 匿名独立开关、权限、穿透开关、自动启动开关）：`ServiceInstanceEntity` + `feature/service/ServiceEditScreen.kt` 表单。
- 一键启动/停止/重启/查看状态：`ServiceListScreen` 操作按钮 + `ServiceRuntimeController`（start/stop/restart/status）。

### 4.3 内网穿透
1. 公共隧道：一键开启 → 生成外网地址 + 二维码：`PublicTunnelConfigProvider`（可远端刷新 JSON）+ `TunnelViewModel` + `QrDialog`（`core/network/QrCodeEncoder.kt`）。
   - **偏差（真机阶段修正）**：原默认端点 `pub.dirtunnel.app` 并非真实域名，已移除——公共隧道默认「未配置」，启动前置校验并附方案提示（配置自有 frps / 自建 FRP 档案 / IPv6 直连）；端点在隧道页「公共隧道服务器」图形化配置，token 落加密存储。
   - **IPv6 公网直连（真实可用的零配置方案）**：`core/engine/network/GlobalIpv6.kt` 检测全局单播 IPv6（2000::/3），隧道页卡片对运行中服务自动生成 `scheme://[v6]:port` 可复制地址——不依赖任何服务器；要求访问端同样支持 IPv6。
2. 自建 FRP：服务器/端口/token 图形化表单（`FrpProfileEditScreen.kt`），`FrpcConfigBuilder` 生成 ini，不手写。
- TCP 兼容所有内置协议 + 用户自定义端口：`ProxyConfig`/`TcpForwardEditScreen`。

### 4.4 安全与审计
- IP 白名单：`core/engine/proxy/IpMatcher.kt`（CIDR 匹配）+ `ProxyConfig.whitelist`（REQ 4.4）。
- 连接/上传下载日志 + 导出：`LogDao`（ConnectionLog/TransferLog）+ `feature/log/LogViewModel`（CSV 生成）+ `LogScreen`（SAF `CreateDocument("text/csv")` 导出）。
- 现代加密套件（TLS1.2/1.3、SSH2）：FTPS/WebDAV-HTTPS 配置仅 TLS1.2+；SFTP 为 SSH2。
- 配置本地加密存储：`SecurityStore`（见 TECH 六.1）。
- 端口自定义：服务编辑页端口字段 + `PortChecker` 校验。

### 4.5 UI 主题
三模式（跟随系统/浅色/深色）：见 TECH 四主题模式实现。
- 底部毛玻璃导航栏不透明度可在 设置 → 外观 滑杆调节（`AppleSlider` 自研组件、`ShellViewModel`、`SettingsStore.navBarOpacity`）；
- 目录左滑删除采用素色滑出背景 + 红色「删除」字样（不用整块红底）。

### 4.6 监控 · 首页仪表盘
`app/dashboard/DashboardScreen.kt` + `DashboardViewModel.kt`：
- 服务运行状态（聚合 `ServiceRuntimeController.statuses`）
- 在线连接数（`activeConnections`，来自 `TcpProxyServer.stats.active`）
- 实时流量（`bytesUp/bytesDown` + 速率采样 `rateUp/rateDown`）
- 隧道状态（`TunnelRuntimeController.runtimes.connected`）
- 端口占用告警（`portConflicts`：`ProcessState.Failed` 含"端口"）
- 权限不足告警（SAF 授权失效 + 电池/后台限制警告，附解决方案动作）

## 五、非功能需求

| 条目 | 实现 |
|---|---|
| 单服务启动 <1s | 子进程异步启动 + 内部端口就绪探测（不 sleep 固定时长） |
| 后台稳定 ≥72h、息屏保活 | 前台服务 + WakeLock + 电池白名单引导 + 自动重启（真机压测待发布前执行） |
| APK ≤15MB | 当前 debug 18MB（含双 ABI 与调试信息）；release + abi 过滤 + 资源压缩目标 ≤15MB（发布前） |
| 内存 ≤200MB | 子进程独立内存，宿主仅代理转发（待真机验证） |
| 兼容 Android 8~15 | minSdk 26 → targetSdk 35；`Build.VERSION` 分支（通知渠道、前台服务类型等） |
| WiFi/蜂窝/热点 | `core/network/NetworkStatusMonitor.kt` 监听网络切换并提示 |
| 不上传用户文件 / 不收集 | 无任何上传埋点代码；仅用户主动的远端隧道配置 JSON 拉取与 DDNS 请求 |
| 隧道配置用户可控 | 公共隧道可覆盖端点/禁用，自建 FRP 完全本地 |
| 一键清空日志、关闭全部服务 | `LogViewModel.clearAll()`；设置页/列表"全部停止"（`ServiceRuntimeController.stopAll`） |
| 三步核心流程 | 首页 → 目录（选择/收藏）→ 添加服务（协议模板）→（可选）开启穿透 |
| 异常提示附带解决方案 | 统一模式：所有错误实体带 `solution` 字段（端口占用→更换端口、SAF→重新授权、电池限制→跳转白名单、SELinux/启动失败→检查 md5/日志） |

## 六、版本迭代优先级

| 版本 | 条目 | 状态与位置 |
|---|---|---|
| V1.0 | 目录选择 + SAF | `feature:directory`、`SaFDocumentTree` ✅ |
| V1.0 | FTP/FTPS/SFTP/HTTP/WebDAV | `EngineConfigBuilder` + `ServiceRuntimeController` ✅ |
| V1.0 | 公共 FRP 隧道、二维码 | `feature:tunnel` + `QrCodeEncoder` ✅ |
| V1.0 | 账号密码、只读/读写 | 服务编辑页 + `AccessPermission` ✅ |
| V1.0 | 主题三模式 | `AppleTheme` + `SettingsStore` ✅ |
| V1.0 | 基础状态监控、日志 | 仪表盘 + `feature:log` ✅ |
| V1.1 | SMB2/3 | 协议枚举 + smbd 配置 ✅ |
| V1.1 | HTTPS 自定义证书 | `useCustomCert`/`certDir` + 设置页导入 ✅ |
| V1.1 | IP 白名单 | `IpMatcher` + 代理过滤 ✅ |
| V1.1 | 自建 FRP 配置面板 | `FrpProfileEditScreen` ✅ |
| V1.1 | 日志导出 | CSV 导出 ✅ |
| V1.2 | Rsync/NFS 高阶开关 | 协议 + 高级开关（默认隐藏）✅ |
| V1.2 | 限速、连接数限制 | `TcpProxyServer` 令牌桶 + `maxConnections` ✅ |
| V1.2 | DDNS | `core/network/DdnsUpdater.kt` + 设置页 ✅ |
| V1.2 | 配置模板 | `ServiceTemplateEntity` + 服务编辑页模板选择 ✅ |

## 七、差异化亮点

1. 一体化（目录 + 多协议 + FRP 同 APP）：见上述模块结构；
2. 目录与协议解耦、单目录多协议独立配置：`DirectoryEntity` 1:N `ServiceInstanceEntity`；
3. 小白一键公共隧道 / 开发者自建 FRP + 自定义 TCP 转发：`TunnelScreen` 双 Tab；
4. 原生三主题：`AppleTheme`；
5. 纯净无广告：无广告/推送 SDK。

---

## 发布前遗留（占位项）

- [x] `binaries.json`：dirtunneld（libdirtunneld.so）ftp/sftp/sshd/minihttpd/frpc 五条 native 条目已带真实 md5（`94b13c6c…`）；frpc 已进程内化不再需要独立二进制；
- [ ] **公共隧道真实端点**：不存在免费可靠的公共服务；一键公网需运营方一台真实 frps（用户自配端点已支持）。替代真·零配置方案：IPv6 直连卡片已落地；
- [ ] smbd/rsync/nfsd 真实二进制与 md5（注意：同样受 W^X 约束，落地需进程内化或改经可 exec 路径）；
- [ ] release 签名、AAB 输出、abi 分包；
- [ ] 真机 72h 保活压测；
- [ ] APK 体积：frp 入库后 .so 达 15.1MB、debug APK 25.9MB，需裁剪未用 frp 传输层（quic-go/kcp-go）或分包策略达成 ≤15MB 目标。
