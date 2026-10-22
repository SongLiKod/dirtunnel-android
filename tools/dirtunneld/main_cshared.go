//go:build cshared

package main

// c-shared（Android .so）形态下无命令行入口，服务由宿主经
// StartServer/StopServer（JNI）驱动；main 必须立即返回。
func main() {}
