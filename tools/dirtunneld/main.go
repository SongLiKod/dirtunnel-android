// dirtunneld DirTunnel 协议引擎（TECH 二 · 静态编译 + 子进程托管）。
//
// 单二进制多协议：ftp / http / sftp 三种运行模式，
// 命令行由 core:engine 的 EngineConfigBuilder 生成：
//
//	dirtunneld ftp  -c /path/ftpd.conf
//	dirtunneld http -c /path/minihttpd.conf
//	dirtunneld sftp -f /path/sshd_config -D
//
// 亦可构建为 Android c-shared .so（-tags cshared -buildmode=c-shared），
// 经 JNI 进程内托管：现代 Android（API 29+，SELinux W^X）禁止应用 exec
// 自身数据目录中的文件，V1.0 协议引擎在 App 进程内运行（gomobile 同款架构）。
//
// 约定：只监听配置中的 127.0.0.1 内部端口，对外流量统一经
// TcpProxyServer（白名单/限速/连接数/记账）暴露；密码只出现在
// 0600 配置文件中，不进命令行（TECH 六.2）。
//
//go:build !cshared

package main

import (
	"fmt"
	"os"
)

func main() {
	mode, cfg := parseArgs(os.Args[1:])
	if mode == "" || cfg == "" {
		fmt.Fprintln(os.Stderr, "usage: dirtunneld <ftp|http|sftp> -c <config-file>")
		os.Exit(2)
	}
	var err error
	switch mode {
	case "ftp":
		err = runFTP(cfg, nil)
	case "http":
		err = runHTTP(cfg, nil)
	case "sftp":
		err = runSFTP(cfg, nil)
	default:
		err = fmt.Errorf("未知模式 %q（支持 ftp/http/sftp）", mode)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "[dirtunneld] %s 启动失败: %v\n", mode, err)
		os.Exit(1)
	}
}

// parseArgs 解析：第一个非 - 开头且不是选项值的参数为 mode；-c/-f 指定配置文件；
// 忽略 sshd 风格的 -D（本程序本就不 fork 后台）。
func parseArgs(args []string) (mode, cfg string) {
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch a {
		case "-c", "-f", "--config":
			if i+1 < len(args) {
				i++
				cfg = args[i]
			}
		case "-D", "--daemonize", "-e", "-h":
			// 忽略
		default:
			if len(a) > 0 && a[0] != '-' && mode == "" {
				mode = a
			}
		}
	}
	return mode, cfg
}
