package main

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ==================== 配置解析 ====================

// parseKV 解析 "key = value" 格式（ftpd.conf / minihttpd.conf）。
func parseKV(cfgPath string) (map[string]string, error) {
	m := map[string]string{}
	f, err := os.Open(cfgPath)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		kv := strings.SplitN(line, "=", 2)
		if len(kv) != 2 {
			continue
		}
		m[strings.TrimSpace(kv[0])] = strings.TrimSpace(kv[1])
	}
	return m, sc.Err()
}

// parseSSHD 解析 sshd_config 风格的 "Key value ..."（本程序只读取 EngineConfigBuilder 生成的子集）。
func parseSSHD(cfgPath string) (map[string]string, error) {
	m := map[string]string{}
	f, err := os.Open(cfgPath)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		m[fields[0]] = strings.Join(fields[1:], " ")
	}
	return m, sc.Err()
}

func kvStr(m map[string]string, k, def string) string {
	if v, ok := m[k]; ok && v != "" {
		return v
	}
	return def
}

func kvInt(m map[string]string, k string, def int) int {
	if v, ok := m[k]; ok {
		if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil {
			return n
		}
	}
	return def
}

func kvBool(m map[string]string, k string, def bool) bool {
	v, ok := m[k]
	if !ok {
		return def
	}
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "yes", "true", "1", "on":
		return true
	case "no", "false", "0", "off":
		return false
	}
	return def
}

// ==================== 账号文件（0600，user:pass 每行） ====================

type accounts struct {
	users map[string]string
}

// loadAccounts 读取账号文件；path 为空或读取失败返回 nil（nil = 不校验 = 匿名模式）。
func loadAccounts(path string) *accounts {
	if path == "" {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	a := &accounts{users: map[string]string{}}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if i := strings.Index(line, ":"); i > 0 {
			a.users[line[:i]] = line[i+1:]
		}
	}
	if len(a.users) == 0 {
		return nil
	}
	return a
}

func (a *accounts) check(user, pass string) bool {
	if a == nil {
		return true
	}
	p, ok := a.users[user]
	return ok && p == pass
}

func (a *accounts) userAllowed(user string) bool {
	if a == nil {
		return true
	}
	_, ok := a.users[user]
	return ok
}

// ==================== TLS 证书 ====================

// loadOrGenCert 加载自定义证书（tls_cert/tls_key）；缺失时在配置目录生成自签
// ECDSA P-256 证书（0600，十年有效），保证 FTPS / HTTPS 开箱可用。
func loadOrGenCert(certPath, keyPath, dir string) (tls.Certificate, error) {
	if certPath != "" && keyPath != "" {
		if _, e1 := os.Stat(certPath); e1 == nil {
			if _, e2 := os.Stat(keyPath); e2 == nil {
				return tls.LoadX509KeyPair(certPath, keyPath)
			}
		}
	}
	certFile := filepath.Join(dir, "self_signed.cert")
	keyFile := filepath.Join(dir, "self_signed.key")
	if cert, err := tls.LoadX509KeyPair(certFile, keyFile); err == nil {
		return cert, nil
	}
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return tls.Certificate{}, err
	}
	now := time.Now()
	tmpl := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "DirTunnel self-signed", Organization: []string{"DirTunnel"}},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		return tls.Certificate{}, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return tls.Certificate{}, err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(certFile, certPEM, 0o600); err != nil {
		return tls.Certificate{}, err
	}
	if err := os.WriteFile(keyFile, keyPEM, 0o600); err != nil {
		return tls.Certificate{}, err
	}
	return tls.LoadX509KeyPair(certFile, keyFile)
}

// ==================== 日志文件 ====================

func openLogFile(p string) *os.File {
	if p == "" {
		return nil
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return nil
	}
	return f
}

func logfLine(f *os.File, format string, args ...any) {
	if f == nil {
		return
	}
	fmt.Fprintf(f, "%s %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, args...))
}

// ==================== 网络工具 ====================

// lanIP 返回本机可被局域网访问的 IPv4（PASV 广告地址），没有则回环。
func lanIP() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, ic := range ifaces {
		if ic.Flags&net.FlagUp == 0 || ic.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ic.Addrs()
		for _, a := range addrs {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil {
				continue
			}
			ip4 := ip.To4()
			if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() || ip4.IsUnspecified() {
				continue
			}
			return ip4.String()
		}
	}
	return "127.0.0.1"
}

// resolveJail 将虚拟路径（POSIX，永远 / 开头）安全地映射到 root 之下。
// path.Clean 后不可能逃出 root（"/.." → "/"）。
func resolveJail(root, vpath string) string {
	v := path.Clean("/" + strings.TrimPrefix(vpath, "/"))
	return filepath.Join(root, filepath.FromSlash(v))
}

// virtualPath 将绝对路径转换为虚拟路径（/ 开头）。
func virtualOf(root, abs string) string {
	rel, err := filepath.Rel(root, abs)
	if err != nil {
		return "/"
	}
	return path.Clean("/" + filepath.ToSlash(rel))
}

// ==================== TLS 缓冲连接（AUTH TLS 握手前已读入缓冲的字节） ====================

// prefixConn 优先从 prefix 读取，读完再落到底层连接。
// AUTH TLS 升级时用于把 bufio 中已缓冲的 ClientHello 还给 TLS 握手。
type prefixConn struct {
	net.Conn
	r io.Reader
}

func (c *prefixConn) Read(p []byte) (int, error) {
	if c.r != nil {
		n, err := c.r.Read(p)
		if n > 0 {
			return n, nil
		}
		if err != nil && err != io.EOF {
			return n, err
		}
		c.r = nil
	}
	return c.Conn.Read(p)
}
