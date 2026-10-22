package main

import (
	"fmt"
	"sync"
	"sync/atomic"
)

// startup 承载 run* 监听就绪后的关闭句柄。
type startup struct {
	shutdown func()
}

type runningServer struct {
	shutdown func()
}

var (
	srvMu     sync.Mutex
	srvNext   int32
	servers   = map[int32]*runningServer{}
	lastErrAV atomic.Value // string
)

func setErr(err error) {
	if err != nil {
		lastErrAV.Store(err.Error())
	}
}

func lastErrString() string {
	v, _ := lastErrAV.Load().(string)
	return v
}

func register(st startup) int32 {
	srvMu.Lock()
	defer srvMu.Unlock()
	srvNext++
	servers[srvNext] = &runningServer{shutdown: st.shutdown}
	return srvNext
}

// watch 登记服务并监听其退出（进程内运行无外部进程可盯，只能记录错误供诊断）。
func watch(st startup, done <-chan error) int32 {
	h := register(st)
	go func() {
		if err := <-done; err != nil {
			setErr(fmt.Errorf("服务已退出: %v", err))
		}
	}()
	return h
}
