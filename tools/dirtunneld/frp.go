package main

// frp 客户端（进程内模式，REQ 4.3 隧道）：以 frp 官方 client 库承载，
// 与主引擎同因走 c-shared（安卓 10+ SELinux W^X 禁止 exec 应用数据目录文件）。
// 配置由 Kotlin 侧 FrpcConfigBuilder 生成 frpc TOML（v1 格式），
// 此处用官方 config.LoadClientConfig 解析（兼容 toml/yaml/json/ini）。

import (
	"context"
	"fmt"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config"
	"github.com/fatedier/frp/pkg/util/log"
)

func runFRP(cfgPath string, ready chan<- startup) error {
	cfg, proxyCfgs, visitorCfgs, _, err := config.LoadClientConfig(cfgPath, false)
	if err != nil {
		return fmt.Errorf("读取 frp 配置: %w", err)
	}
	// 日志落配置指定文件（Kotlin 侧 tail 该文件解析登录成功/远端端口）
	log.InitLogger(cfg.Log.To, cfg.Log.Level, int(cfg.Log.MaxDays), cfg.Log.DisablePrintColor)

	svr, err := client.NewService(client.ServiceOptions{
		Common:         cfg,
		ProxyCfgs:      proxyCfgs,
		VisitorCfgs:    visitorCfgs,
		ConfigFilePath: cfgPath,
	})
	if err != nil {
		return fmt.Errorf("创建 frp 客户端: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// 立即就绪：Run 内部会自动重试登录（loginFailExit=false），
	// “已连接”状态由宿主解析 frpc 日志获得，不在此阻塞。
	if ready != nil {
		ready <- startup{shutdown: cancel}
	}

	if err := svr.Run(ctx); err != nil && ctx.Err() == nil {
		return fmt.Errorf("frp 客户端退出: %w", err)
	}
	return nil
}
