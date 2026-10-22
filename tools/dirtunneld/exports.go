package main

/*
#include <stdlib.h>
#cgo CFLAGS: -I${SRCDIR}
*/
import "C"

import (
	"fmt"
	"time"
)

// StartServer 进程内启动协议服务（JNI/宿主入口）。
// 同步完成配置解析与监听后返回句柄（>=1），失败返回 -1（原因见 LastError）。
//
//export StartServer
func StartServer(mode, cfgPath *C.char) C.int {
	m := C.GoString(mode)
	p := C.GoString(cfgPath)
	lastErrAV.Store("")

	ready := make(chan startup, 1)
	done := make(chan error, 1)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				err := fmt.Errorf("引擎异常: %v", r)
				setErr(err)
				done <- err
			}
		}()
		var err error
		switch m {
		case "ftp":
			err = runFTP(p, ready)
		case "http":
			err = runHTTP(p, ready)
		case "sftp":
			err = runSFTP(p, ready)
		default:
			err = fmt.Errorf("未知模式 %q（支持 ftp/http/sftp）", m)
		}
		if err != nil {
			setErr(err)
		}
		done <- err
	}()

	timer := time.NewTimer(8 * time.Second)
	defer timer.Stop()
	select {
	case st := <-ready:
		return C.int(watch(st, done))
	case err := <-done:
		// 极小概率 ready/done 同时就绪：以就绪为准
		select {
		case st := <-ready:
			return C.int(watch(st, done))
		default:
		}
		if err == nil {
			err = fmt.Errorf("%s 未就绪即退出", m)
		}
		setErr(err)
		return C.int(-1)
	case <-timer.C:
		setErr(fmt.Errorf("%s 启动超时（8s）", m))
		return C.int(-1)
	}
}

// StopServer 停止指定句柄的服务（关闭监听与在途连接）。
//
//export StopServer
func StopServer(h C.int) {
	srvMu.Lock()
	s := servers[int32(h)]
	delete(servers, int32(h))
	srvMu.Unlock()
	if s != nil && s.shutdown != nil {
		s.shutdown()
	}
}

// LastError 返回最近一次错误描述（C 字符串，调用方负责 free）。
//
//export LastError
func LastError() *C.char {
	return C.CString(lastErrString())
}
