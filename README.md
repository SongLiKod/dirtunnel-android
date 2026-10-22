# DirTunnel

安卓多协议目录服务 & 内网穿透工具。在手机上任意选择目录，一键启动 FTP / FTPS / SFTP / HTTP / WebDAV 等文件服务，并集成 FRP 内网穿透，实现局域网 / 外网访问。

- 需求规格：[docs/REQ.md](docs/REQ.md)
- 技术方案：[docs/TECH.md](docs/TECH.md)
- 需求逐条实现映射：[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)

## 功能概览

| 能力 | 说明 |
|---|---|
| 目录管理 | SAF 任意目录选择、持久化授权、目录收藏、左滑删除、单目录多协议绑定、只读/读写/禁止删除 |
| 协议服务 | FTP / FTPS / SFTP / HTTP / WebDAV（V1.0），SMB2/3 + 自定义 HTTPS 证书（V1.1），Rsync / NFS（V1.2，默认隐藏） |
| 内网穿透 | 自建 FRP 面板（图形化，不手写 ini）、公共隧道（配置自有 frps 端点，外网地址 + 自动二维码）、IPv6 公网直连（零服务器自动生成可复制地址）、自定义本地 TCP 端口转发 |
| 安全审计 | IP 白名单、连接/传输日志 + CSV 导出、账号与 frp token 加密存储、敏感配置 0600、二进制 md5 校验 |
| 保活 | 前台服务常驻 + WakeLock、开机自启、电池优化白名单引导、进程崩溃自动重启 |
| 监控 | 首页仪表盘：服务状态、在线连接数、实时流量、隧道状态、端口占用告警、权限不足告警 |
| 主题 | 跟随系统 / 浅色 / 深色三模式，iOS 风格精确色值，悬浮毛玻璃导航（透明度可调） |

## 技术栈

Kotlin · Jetpack Compose（自研 iOS 风格组件，不使用 Material3 组件体系）· Hilt · MVVM + Clean · Room + DataStore · Gradle Kotlin DSL

- minSdk 26 / targetSdk 35 / compileSdk 35
- 恰好 10 个模块：`app` + 5 个 `feature:*` + 4 个 `core:*`

## 模块结构

```
app                     主入口、NavGraph、悬浮毛玻璃底栏、仪表盘、前台服务、开机广播
feature:directory       目录选择 / SAF 授权 / 目录列表
feature:service         服务实例 CRUD、启停重启、状态卡片、运行时控制
feature:tunnel          公共隧道 / 自建 FRP 面板 / 自定义 TCP 转发、二维码分享
feature:log             连接与传输日志、筛选、CSV 导出、一键清空
feature:setting         主题、保活、电池、通知、脱敏、DDNS、证书、组件 md5、免责声明
core:ui                 iOS 风格组件库（AppleFloatingTopBar/AppleGroupList/AppleSwitch/…）+ 主题
core:engine             二进制托管与 md5 校验、子进程管理、配置生成、端口检测、TCP 代理（白名单/限速/连接数）、健康监测
core:storage            Room 实体与 DAO、SettingsStore、SecurityStore(加密)、SAF 文档树
core:network            网络状态监听、二维码生成、公共隧道配置、DDNS 更新
```

## 构建

```bash
export ANDROID_HOME=<sdk>
export JAVA_HOME=<jdk17>
./gradlew assembleDebug     # 输出 app/build/outputs/apk/debug/app-debug.apk
```

### CI（GitHub Actions）

[.github/workflows/build.yml](.github/workflows/build.yml)：

- **触发**：`main` 分支 push、`v*` 标签、PR、手动 dispatch（可填版本号直接发 Release）；
- **Unit Tests**：`./gradlew testDebugUnitTest`；
- **Android (APK)**：`./gradlew assembleDebug assembleRelease`，产物上传 artifact；
- **Publish Release**：打 `v*` 标签（或手动填版本号）后自动创建 GitHub Release 并附带 APK；
- **签名**（可选）：配置仓库 secret `ANDROID_KEYSTORE_BASE64`（base64 编码的 jks）+ `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`，未配置时 release 产物为 unsigned APK。

## 架构要点

- 协议子进程只监听 `127.0.0.1:internalPort`，由 `TcpProxyServer` 绑定用户端口统一执行
  IP 白名单 → 连接数限制 → 限速 → 双向转发 → 流量记账（REQ 4.4 / V1.2）。
- frpc token 等敏感参数不进命令行，写入 0600 临时配置文件（TECH 六.2）。
- 二进制清单 `core/engine/src/main/assets/binaries.json` 记录各协议二进制的路径与 md5；
  ftpd / sftp-server / sshd / minihttpd / frpc 五条 native 条目已带真实 md5 并在释放后校验。
- Android 10+ SELinux 禁止执行应用数据目录文件（W^X）：`dirtunneld` 与 `frpc` 均**进程内**
  链接（`libdirtunneld.so`，Go c-shared 集成 frp v0.61 client 库），无独立可执行文件。

## 发布前遗留

1. smbd / rsync / nfsd 进程内化与真实 md5；
2. APK 体积瘦身（裁剪 frp 未用传输层）与真机 72h 保活压测；
3. 公共隧道默认端点需运营方提供真实 frps（当前未配置，用户自备或使用 IPv6 直连）。

## 声明

> 本软件为个人兴趣开发产物，仅供个人学习、娱乐、非商业性质免费使用。作者保留全部知识产权，未经书面许可不得二次开发、修改、复刻、衍生创作或用于任何商业营利用途。使用者需遵守当地法律法规与网络使用规范，违规使用的后果自行承担。
