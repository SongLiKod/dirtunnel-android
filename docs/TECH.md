# DirTunnel 技术文档
仓库：dirtunnel-android
项目名称：DirTunnel

## 一、技术栈选型
> 定位：原生Android应用，整体UI视觉风格对齐苹果iOS原生清爽风格，导航栏采用苹果悬浮毛玻璃导航方案；优先稳定性、后台可控性、体积可控。
- 开发语言：Kotlin（主），少量JNI C/C++（用于第三方协议二进制）
- 最低SDK：26（Android 8.0）
- 目标SDK：35（Android 15）
- 构建工具：Gradle Kotlin DSL
- UI框架：Jetpack Compose
  - 说明：放弃Material3整套组件体系，**自研一套iOS风格组件库**，实现苹果质感UI、悬浮毛玻璃导航、苹果式按钮、苹果列表、苹果开关控件；Compose适合自定义绘制、毛玻璃、动画、主题动态切换。
- 架构模式：MVVM + Clean分层
  - 分层：UI层 / ViewModel / Domain业务层 / Repository / 底层服务引擎
- 依赖注入：Hilt
- 异步：Kotlin Coroutine + Flow
- 本地持久化：
  - DataStore（存储设置、主题配置、账号配置）
  - Room（连接日志、访问日志）
- 文件访问：Android Storage Access Framework (SAF) + MediaStore + 传统File API（兼容旧机型）

## 二、底层协议引擎方案（重要）
Android没有系统自带这些服务协议栈，方案：**跨平台静态编译二进制 + app内托管子进程**
> 注意：所有服务以独立子进程运行，APP负责管理启停、参数组装、日志捕获、端口监听检测

| 协议 | 底层实现 | 备注 |
|---|---|---|
| FTP / FTPS | portable ftpd（静态编译） | |
| SFTP | openssh sftp-server | SSH子系统 |
| HTTP | minihttpd / lighttpd 静态精简版 | 静态文件服务 |
| WebDAV | minihttpd webdav模块 | 和HTTP可复用同一个httpd进程或者独立实例 |
| SMB2/3 | samba静态编译 | V1.1引入，默认禁用SMB1 |
| Rsync | rsync静态编译 | V1.2 |
| NFS | nfs-utils静态编译 | V1.2，高阶 |
| FRP隧道 | frp 官方arm/arm64静态二进制 | 内置frpc客户端，图形化包装配置 |

ABI目标：arm64-v8a（主力）、armeabi-v7a（兼容旧32位设备）

> 进程管理：使用ProcessBuilder启动，监控pid，捕获stdout/stderr，监听崩溃，自动重启（可选开关）

## 三、权限清单
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`（分区存储适配）
- `SAF 文档树授权`（核心，用来访问任意用户目录）
- `FOREGROUND_SERVICE`（前台服务，保活必备）
- `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED / DATA_SYNC`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `WAKE_LOCK`（可选，防止休眠断流，给用户开关）
- `POST_NOTIFICATIONS`（Android13+，前台服务通知）

> 重要：前台服务是安卓保活唯一可靠路径，服务运行时常驻前台通知，用户可看到服务状态。

## 四、UI视觉规范（iOS Apple风格）
### 总体风格说明
整体遵循 iOS 原生设计语言：高留白、低饱和度、圆角柔和、轻阴影、清晰层级、极简，无Material水滴波纹、无Material色块。
全局控件规范：
- 按钮：iOS圆角按钮，圆角半径 12px；主按钮填充、次要按钮描边
- 列表：iOS分组样式 Grouped List，分组标题浅灰、分割线细、单元格圆角
- 开关：复刻iOS UISwitch样式
- 弹窗：iOS圆角弹窗、背景变暗蒙版
- 动画：平缓、低阻尼，苹果式弹性过渡，无夸张动画

### ✅ 重点：悬浮导航栏（苹果悬浮风格）
导航栏不是固定贴屏幕顶部状态栏，为**悬浮浮窗式导航Header**：
1. 位置：距离屏幕顶部状态栏下方有边距，左右也留边距，不是通栏；
2. 样式：圆角矩形（圆角 16dp），毛玻璃模糊背景（苹果 `systemMaterial` 等效效果）；
3. 内容：左侧返回/标题，右侧操作按钮；
4. 滚动行为：页面向上滚动时，悬浮导航保持悬浮置顶，模糊强度轻微变化；
5. 分层：导航浮层高于页面内容，低于弹窗；
6. 实现方式：Compose `BackdropBlur` + 半透明底色叠加模拟iOS毛玻璃；
7. 适配深色/浅色模式：毛玻璃底色、模糊参数跟随主题自动切换。

> 不使用安卓原生TopAppBar，完全自研悬浮导航组件 `AppleFloatingTopBar`，全局统一复用。

### 主题模式
一共三种模式：
1. 跟随系统 (System Default)
2. 浅色模式 (Light)
3. 深色模式 (Dark)

#### 浅色主题（Light · iOS浅色）
| 角色 | 颜色名称 | Hex |
|---|---|---|
| Primary | 苹果蓝（Apple System Blue） | `#007AFF` |
| OnPrimary | 纯白（White） | `#FFFFFF` |
| Background | 苹果背景白（iOS System Background） | `#F2F2F7` |
| Surface / Card | 卡片白（iOS Secondary System Background） | `#FFFFFF` |
| Text Primary | 主文本黑（iOS Label） | `#000000` |
| Text Secondary | 次要文本灰（iOS Secondary Label） | `#8E8E93` |
| Separator | 分割线浅灰（iOS Separator） | `#C6C6C8` |
| FloatingBar BG | 浅色毛玻璃底色 | `#F8F8F8CC` |
| Outline / Border | 边框浅灰 | `#D1D1D6` |
| Error | 苹果红（Apple System Red） | `#FF3B30` |

#### 深色主题（Dark · iOS深色）
| 角色 | 颜色名称 | Hex |
|---|---|---|
| Primary | 苹果蓝（Apple System Blue） | `#0A84FF` |
| OnPrimary | 纯白（White） | `#FFFFFF` |
| Background | 苹果深色背景（iOS Dark System Background） | `#000000` |
| Surface / Card | 深色卡片（iOS Dark Secondary Background） | `#1C1C1E` |
| Text Primary | 主文本白（iOS Dark Label） | `#FFFFFF` |
| Text Secondary | 次要文本灰（iOS Dark Secondary Label） | `#8E8E93` |
| Separator | 深色分割线（iOS Dark Separator） | `#38383A` |
| FloatingBar BG | 深色毛玻璃底色 | `#2C2C2ECC` |
| Outline / Border | 深色边框灰 | `#48484A` |
| Error | 苹果红（Apple System Dark Red） | `#FF453A` |

#### 跟随系统
读取系统夜间模式设置，自动在上面浅色、深色两套调色板之间切换，悬浮导航毛玻璃样式同步切换。

Compose 实现思路：
- 保存枚举主题选项到DataStore：`SYSTEM / LIGHT / DARK`
- 全局封装 AppleTheme，所有页面读取这套色值；
- 悬浮导航组件 `AppleFloatingTopBar` 内置毛玻璃模糊逻辑，自动适配当前主题。

## 五、模块拆分
1. app：主入口 + Compose 页面路由
2. feature:directory：目录选择、SAF授权、目录收藏、苹果风格目录列表
3. feature:service：服务实例CRUD、启停、参数管理、状态卡片
4. feature:tunnel：FRP隧道面板、公共隧道、自建隧道配置
5. feature:log：日志列表、日志导出
6. feature:setting：主题切换、全局设置、证书导入、高级开关
7. core:ui：【新增】iOS风格组件库，包含：AppleFloatingTopBar、AppleGroupList、AppleSwitch、AppleButton、AppleAlertDialog、毛玻璃封装
8. core:engine：底层二进制进程管理器、端口检测、pid监控
9. core:storage：SAF文件封装、DataStore、Room
10. core:network：网络状态监听、二维码生成

## 六、安全设计要点
1. 账号密码、frp token在DataStore内使用EncryptedSharedPreferences加密存储；
2. 子进程参数不通过命令行明文传递敏感信息（尽量写入临时配置文件，权限0600）；
3. 所有服务默认不开启匿名访问，匿名访问需要用户手动勾选；
4. 前台通知不泄露账号密码；
5. 日志可以选择不记录密码；
6. 二进制文件校验md5，防止被篡改。

## 七、已知风险与规避方案
1. Android电池优化、应用休眠 → 强制前台服务 + 用户引导关闭电池限制
2. 不同厂商SELinux拦截子进程网络/文件访问 → 增加检测，给出提示
3. 端口冲突 → 启动前扫描端口，提示更换端口
4. SAF权限重启失效 → 持久化SAF Uri授权
5. 二进制体积膨胀 → 只保留arm64为主，armeabi-v7可选独立分包
6. Android平台没有原生毛玻璃API兼容性差异：低版本机型毛玻璃失效时自动降级为对应主题半透明纯色背景，保证可用性

## 八、打包发布
- 输出：AAB + APK
- 签名：release版本使用开发者自有密钥
- 分发：GitHub Release
