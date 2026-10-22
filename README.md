# DirTunnel

安卓多协议目录服务 & 内网穿透工具。在手机上任意选择目录，一键启动 FTP / FTPS / SFTP / HTTP / WebDAV 等文件服务，并集成 FRP 内网穿透，实现局域网 / 外网访问。

- 需求规格：[docs/REQ.md](docs/REQ.md)
- 技术方案：[docs/TECH.md](docs/TECH.md)
- 需求逐条实现映射：[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)

## 功能概览

| 能力 | 说明 |
|---|---|
| 目录管理 | SAF 任意目录选择、持久化授权、目录收藏、单目录多协议绑定、只读/读写/禁止删除 |
| 协议服务 | FTP / FTPS / SFTP / HTTP / WebDAV（V1.0），SMB2/3 + 自定义 HTTPS 证书（V1.1），Rsync / NFS（V1.2，默认隐藏） |
| 内网穿透 | 公共隧道一键使用（外网地址 + 二维码）、自建 FRP 面板（图形化，不手写 ini）、自定义本地 TCP 端口转发 |
| 安全审计 | IP 白名单、连接/传输日志 + CSV 导出、账号与 frp token 加密存储、敏感配置 0600、二进制 md5 校验 |
| 保活 | 前台服务常驻 + WakeLock、开机自启、电池优化白名单引导、进程崩溃自动重启 |
| 监控 | 首页仪表盘：服务状态、在线连接数、实时流量、隧道状态、端口占用告警、权限不足告警 |
| 主题 | 跟随系统 / 浅色 / 深色三模式，iOS 风格精确色值，悬浮毛玻璃导航 |

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
feature:setting         主题、保活、电池、通知、脱敏、公共隧道端点、DDNS、证书、组件 md5
core:ui                 iOS 风格组件库（AppleFloatingTopBar/AppleGroupList/AppleSwitch/…）+ 主题
core:engine             二进制托管与 md5 校验、子进程管理、配置生成、端口检测、TCP 代理（白名单/限速/连接数）、健康监测
core:storage            Room 实体与 DAO、SettingsStore、SecurityStore(加密)、SAF 文档树
core:network            网络状态监听、二维码生成、公共隧道配置、DDNS 更新
```

## 构建

```bash
export ANDROID_HOME=<sdk>
export JAVA_HOME=<jdk17>
gradle assembleDebug     # 输出 app/build/outputs/apk/debug/app-debug.apk
```

## 架构要点

- 协议子进程只监听 `127.0.0.1:internalPort`，由 `TcpProxyServer` 绑定用户端口统一执行
  IP 白名单 → 连接数限制 → 限速 → 双向转发 → 流量记账（REQ 4.4 / V1.2）。
- frpc token 等敏感参数不进命令行，写入 0600 临时配置文件（TECH 六.2）。
- 二进制清单 `core/engine/src/main/assets/binaries.json` 记录各协议二进制的路径与 md5；
  **当前为占位（md5 为空），发布前需补齐真实二进制与校验值**。

## 发布前遗留

1. 补齐 `binaries.json` 中各静态二进制（frpc / ftpd / sftp-server / minihttpd / smbd …）及 md5；
2. release 签名密钥配置（TECH 八）；
3. APK 体积（V1.0 目标 ≤15MB）与真机 72h 保活压测。
