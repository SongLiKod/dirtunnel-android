# 协议引擎组件

按 TECH.md 二、底层协议引擎方案，服务协议以「静态编译引擎 + app 内托管」方式运行。

## V1.0 内置引擎：dirtunneld（进程内形态）

**为什么是进程内**：Android 10+（API 29，SELinux W^X）禁止 `untrusted_app`
exec 自身数据目录中的可执行文件（ProcessBuilder 报
`Cannot run program ... error=13`），子进程形态在现代手机上不可行。
因此 dirtunneld 编译为 Go **c-shared**：

```
core/engine/src/main/jniLibs/arm64-v8a/libdirtunneld.so   # 随 APK 安装
```

由 `NativeEngine`（JNI）在 App 进程内加载，`StartServer(mode, cfgPath)` /
`StopServer(handle)` 驱动，语义与子进程一致：

- 仍只监听 127.0.0.1 内部端口，对外统一经 TcpProxyServer
  （IP 白名单 / 连接数 / 限速 / 流量记账）暴露；
- 密码只写 0600 配置文件，经路径传入（TECH 六.2）；
- `.so` 与 binaries.json 的 md5 基准比对（TECH 六.6）。

单引擎三模式（由 `EngineConfigBuilder` 的 `nativeMode` 指定）：

```
ftp  → FTP / FTPS（RFC 959 / RFC 4217）
http → HTTP + WebDAV（Basic 认证、目录列表、PUT/MKCOL/PROPFIND…）
sftp → SFTP（x/crypto/ssh + pkg/sftp 子系统）
```

清单中 `ftpd` / `sftp-server` / `sshd` / `minihttpd` 四个条目
`"native": true`，共享同一个 `libdirtunneld.so`（同一 md5）。

### 构建

```
cd tools/dirtunneld
# 1) 独立二进制（开发期回归测试用）
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o dirtunneld-arm64 .

# 2) Android c-shared（进 jniLibs）
export CC=<交叉编译包装器: aarch64-linux-android26 + NDK sysroot + --rtlib=compiler-rt>
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CGO_CFLAGS="-I$(pwd)" \
  go build -tags cshared -buildmode=c-shared -ldflags="-s -w" \
  -o ../../core/engine/src/main/jniLibs/arm64-v8a/libdirtunneld.so .

# 3) md5sum 产物 → 同步 binaries.json 四个条目的 md5
# 4) 冒烟：独立二进制用 so_smoke/harness；SFTP 客户端 go run ./cmd/sftptest
```

已通过容器内全协议冒烟（独立二进制 + dlopen 进程内双路径）：
HTTP 认证/列表/下载/PUT/PROPFIND/DELETE、FTP LIST/RETR/STOR/MKD/DELE/
RNFR/RNTO/只读 550/错密 530、FTPS 隐式 TLS、SFTP 读写与只读、
进程内启动/停止/重启/端口冲突拒绝。

## 待内置组件（V1.1 / V1.2，正式发行包放置）

仍走 assets 释放 + 子进程托管（届时需按 W^X 限制评估进程内或 system 权限方案）：

```
core/engine/src/main/assets/
├── binaries.json          # 组件清单（name/file/version/md5/abis/native）
└── bin/
    ├── arm64-v8a/         # 主力 ABI
    │   ├── smbd           # SMB2/3（samba，V1.1，默认禁用 SMB1）
    │   ├── rsync          # Rsync（V1.2）
    │   ├── nfs-utils      # NFS（V1.2 高阶）
    │   └── frpc           # frp 官方静态二进制
    └── armeabi-v7a/       # 32 位分包（同名文件）
```

## 要求

1. V1.0 引擎以 jniLibs `.so` 形式内置（`native: true`），PMS 释放到
   nativeLibraryDir，无需运行时拷贝；
2. assets 目录组件（V1.1/V1.2）为静态二进制，释放后自动 `chmod 755`；
3. **必须**在 `binaries.json` 中补齐 `md5` 基准值（`native` 条目填 `.so` 的
   md5），应用启动/设置页会做完整性校验（TECH 六.6）；
4. 体积控制：V1.0 目标 APK ≤15MB，armeabi-v7a 可独立分包（TECH 七.5）。
