package main

// FTP / FTPS 服务器（RFC 959 / RFC 4217 子集）。
//
// - 控制连接按配置监听 bind_address:port（上游为 127.0.0.1 内部端口，经 TcpProxy 暴露）；
// - 被动模式数据端口由本进程直连（随机高位端口、单次接受、会话绑定）——
//   白名单/限速已在控制面（代理层）生效，数据面直连是 FTP 被动模式的通行做法；
// - ssl_only=yes 视为隐式 TLS（FTPS 9990 端口语义），ssl_enable 单独开启时支持显式 AUTH TLS；
// - 只读/可删除权限由本进程强 Enforcement（REQ 4.1.4）。

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ftpConfig struct {
	bind, root, dir string
	port, maxClients int
	anonymous, readOnly, allowDelete bool
	auth  *accounts
	logf  *os.File
	sslEnable, sslOnly bool
	cert  tls.Certificate
	certOK bool
	certFile, keyFile string
	pasvAddr string // 可选覆盖（默认自动探测局域网 IP）
	pasvIP   string // 探测缓存
}

func runFTP(cfgPath string, ready chan<- startup) error {
	kv, err := parseKV(cfgPath)
	if err != nil {
		return fmt.Errorf("读取配置: %w", err)
	}
	c := &ftpConfig{
		bind:         kvStr(kv, "bind_address", "127.0.0.1"),
		port:         kvInt(kv, "port", 2121),
		root:         kv["root"],
		anonymous:    kvBool(kv, "anonymous", false),
		readOnly:     kvBool(kv, "readonly", true),
		allowDelete:  kvBool(kv, "delete", false),
		maxClients:   kvInt(kv, "max_clients", 16),
		logf:         openLogFile(kv["log_file"]),
		sslEnable:    kvBool(kv, "ssl_enable", false),
		sslOnly:      kvBool(kv, "ssl_only", false),
		certFile:     kv["tls_cert"],
		keyFile:      kv["tls_key"],
		dir:          filepath.Dir(cfgPath),
		pasvAddr:     kv["pasv_address"],
	}
	if c.root == "" {
		return fmt.Errorf("配置缺少 root")
	}
	if err := os.MkdirAll(c.root, 0o755); err != nil {
		return fmt.Errorf("root 不可用: %w", err)
	}
	if !c.anonymous {
		c.auth = loadAccounts(kv["auth_file"])
	}
	if c.sslEnable {
		cert, err := loadOrGenCert(c.certFile, c.keyFile, c.dir)
		if err != nil {
			return fmt.Errorf("TLS 证书: %w", err)
		}
		c.cert, c.certOK = cert, true
	}
	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", c.bind, c.port))
	if err != nil {
		return fmt.Errorf("监听 %s:%d: %w", c.bind, c.port, err)
	}
	if c.sslOnly {
		if !c.certOK {
			return fmt.Errorf("ssl_only 需要 TLS 证书")
		}
		ln = tls.NewListener(ln, &tls.Config{
			Certificates: []tls.Certificate{c.cert},
			MinVersion:   tls.VersionTLS12,
		})
	}
	fmt.Printf("[ftp] ready on %s:%d root=%s tls=%v\n", c.bind, c.port, c.root, c.sslOnly)
	if ready != nil {
		ready <- startup{shutdown: func() { _ = ln.Close() }}
	}
	n := c.maxClients
	if n <= 0 {
		n = 16
	}
	sem := make(chan struct{}, n)
	for {
		conn, err := ln.Accept()
		if err != nil {
			return fmt.Errorf("accept: %w", err)
		}
		sem <- struct{}{}
		go func() {
			defer func() {
				<-sem
				if r := recover(); r != nil {
					setErr(fmt.Errorf("ftp 会话异常: %v", r))
				}
			}()
			c.handleConn(conn)
		}()
	}
}

// ==================== 会话 ====================

type ftpSession struct {
	cfg *ftpConfig
	conn net.Conn
	br   *bufio.Reader
	user string
	authed bool
	tlsOn  bool
	implicit bool
	dataType string // "A" | "I"
	pasvLn net.Listener
	activeAddr *net.TCPAddr
	protPrivate bool
	rnfr string
	cwd  string // 虚拟路径，/ 开头
	mu   sync.Mutex
}

var errQuit = fmt.Errorf("quit")

func (c *ftpConfig) handleConn(conn net.Conn) {
	s := &ftpSession{cfg: c, conn: conn, br: bufio.NewReader(conn), cwd: "/", dataType: "I"}
	if _, ok := conn.(*tls.Conn); ok {
		s.implicit, s.tlsOn = true, true
	}
	defer s.cleanup()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Minute))
	s.reply(220, "DirTunnel dirtunneld FTP server ready")
	for {
		line, err := s.readLine()
		if err != nil {
			return
		}
		_ = s.conn.SetDeadline(time.Now().Add(15 * time.Minute))
		parts := strings.SplitN(strings.TrimRight(line, "\r\n"), " ", 2)
		cmd := strings.ToUpper(parts[0])
		arg := ""
		if len(parts) == 2 {
			arg = parts[1]
		}
		if s.dispatch(cmd, arg) == errQuit {
			return
		}
	}
}

func (s *ftpSession) readLine() (string, error) {
	var buf []byte
	for {
		chunk, err := s.br.ReadSlice('\n')
		buf = append(buf, chunk...)
		if err == bufio.ErrBufferFull {
			if len(buf) > 16*1024 {
				return "", fmt.Errorf("line too long")
			}
			continue
		}
		return string(buf), err
	}
}

func (s *ftpSession) reply(code int, msg string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fmt.Fprintf(s.conn, "%d %s\r\n", code, msg)
}

func (s *ftpSession) replyLines(code int, lines []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, l := range lines {
		if i == 0 {
			fmt.Fprintf(s.conn, "%d-%s\r\n", code, l)
		} else {
			fmt.Fprintf(s.conn, " %s\r\n", l)
		}
	}
	fmt.Fprintf(s.conn, "%d End\r\n", code)
}

func (s *ftpSession) cleanup() {
	if s.pasvLn != nil {
		_ = s.pasvLn.Close()
		s.pasvLn = nil
	}
	_ = s.conn.Close()
	logfLine(s.cfg.logf, "session closed user=%s from=%s", s.user, s.conn.RemoteAddr())
}

// ==================== 命令分发 ====================

func (s *ftpSession) dispatch(cmd, arg string) error {
	c := s.cfg
	// 未登录允许的命令（RFC 959 最小集合）
	switch cmd {
	case "USER", "PASS", "QUIT", "FEAT", "OPTS", "NOOP", "SYST", "AUTH", "PBSZ", "PROT", "HELP", "CLNT":
	default:
		if !s.authed {
			s.reply(530, "Please login with USER and PASS.")
			return nil
		}
	}
	switch cmd {
	case "USER":
		s.user = strings.TrimSpace(arg)
		s.authed = false
		if c.anonymous {
			// 匿名也要走 PASS（空密码）
		}
		s.reply(331, "Password required")
	case "PASS":
		pw := strings.TrimSpace(arg)
		if c.anonymous {
			s.authed = true
		} else if c.auth == nil {
			s.reply(530, "Account misconfigured (missing auth file).")
			return nil
		} else if !c.auth.check(s.user, pw) {
			s.reply(530, "Login incorrect.")
			logfLine(c.logf, "login failed user=%s from=%s", s.user, s.conn.RemoteAddr())
			return nil
		} else {
			s.authed = true
		}
		if s.authed {
			s.reply(230, "User logged in, proceed.")
			logfLine(c.logf, "login ok user=%s from=%s", s.user, s.conn.RemoteAddr())
		}
	case "QUIT":
		s.reply(221, "Goodbye.")
		return errQuit
	case "NOOP":
		s.reply(200, "OK")
	case "SYST":
		s.reply(215, "UNIX Type: L8")
	case "FEAT":
		feats := []string{"Extensions supported:", "UTF8", "SIZE", "MDTM", "PASV", "EPSV", "TVFS", "REST STREAM"}
		if c.sslEnable {
			feats = append(feats, "AUTH TLS", "AUTH SSL", "PBSZ", "PROT")
		}
		s.replyLines(211, feats)
	case "OPTS":
		s.reply(200, "OK")
	case "CLNT":
		s.reply(200, "OK")
	case "HELP":
		s.reply(214, "USER PASS QUIT PASV EPSV PORT EPRT TYPE LIST NLST RETR STOR APPE DELE MKD RMD CWD PWD SIZE MDTM RNFR RNTO SYST FEAT")
	case "AUTH":
		s.authTLS(arg)
	case "PBSZ":
		s.reply(200, "PBSZ=0")
	case "PROT":
		if strings.EqualFold(strings.TrimSpace(arg), "P") {
			s.protPrivate = true
			s.reply(200, "Data channel will be protected")
		} else {
			s.protPrivate = false
			s.reply(200, "Data channel clear")
		}
	case "TYPE":
		t := strings.ToUpper(strings.TrimSpace(arg))
		switch {
		case strings.HasPrefix(t, "A"):
			s.dataType = "A"
			s.reply(200, "Type set to A")
		case strings.HasPrefix(t, "I"), strings.HasPrefix(t, "L"):
			s.dataType = "I"
			s.reply(200, "Type set to I")
		default:
			s.reply(504, "Unsupported type")
		}
	case "PWD", "XPWD":
		s.reply(257, fmt.Sprintf("\"%s\" is current directory", s.cwd))
	case "CWD", "XCWD":
		abs := resolveJail(c.root, s.join(arg))
		st, err := os.Stat(abs)
		if err != nil || !st.IsDir() {
			s.reply(550, "Failed to change directory.")
			return nil
		}
		s.cwd = virtualOf(c.root, abs)
		s.reply(250, "Directory changed")
	case "CDUP", "XCUP":
		s.dispatch("CWD", "..")
	case "PASV":
		s.openPassive()
	case "EPSV":
		s.openEPSV()
	case "PORT":
		s.cmdPort(arg)
	case "EPRT":
		s.cmdEPort(arg)
	case "LIST", "NLST":
		s.cmdList(cmd, arg)
	case "RETR":
		s.cmdRetr(arg)
	case "STOR", "APPE":
		s.cmdStor(cmd, arg)
	case "DELE":
		if s.cfg.readOnly || !s.cfg.allowDelete {
			s.reply(550, "Permission denied (read-only or delete disabled).")
			return nil
		}
		if err := os.Remove(resolveJail(c.root, s.join(arg))); err != nil {
			s.reply(550, "Delete failed.")
		} else {
			s.reply(250, "Deleted.")
			logfLine(c.logf, "delete user=%s path=%s", s.user, arg)
		}
	case "MKD", "XMKD":
		if s.cfg.readOnly {
			s.reply(550, "Permission denied (read-only).")
			return nil
		}
		abs := resolveJail(c.root, s.join(arg))
		if err := os.Mkdir(abs, 0o755); err != nil {
			s.reply(550, "Create directory failed.")
		} else {
			s.reply(257, fmt.Sprintf("\"%s\" created", virtualOf(c.root, abs)))
		}
	case "RMD", "XRMD":
		if s.cfg.readOnly || !s.cfg.allowDelete {
			s.reply(550, "Permission denied (read-only or delete disabled).")
			return nil
		}
		if err := os.Remove(resolveJail(c.root, s.join(arg))); err != nil {
			s.reply(550, "Remove directory failed.")
		} else {
			s.reply(250, "Removed.")
		}
	case "RNFR":
		if s.cfg.readOnly || !s.cfg.allowDelete {
			s.reply(550, "Permission denied.")
			return nil
		}
		abs := resolveJail(c.root, s.join(arg))
		if _, err := os.Stat(abs); err != nil {
			s.reply(550, "File not found.")
			return nil
		}
		s.rnfr = abs
		s.reply(350, "Ready for RNTO")
	case "RNTO":
		if s.rnfr == "" {
			s.reply(503, "RNFR required first.")
			return nil
		}
		dst := resolveJail(c.root, s.join(arg))
		if err := os.Rename(s.rnfr, dst); err != nil {
			s.reply(550, "Rename failed.")
		} else {
			s.reply(250, "Renamed.")
		}
		s.rnfr = ""
	case "SIZE":
		st, err := os.Stat(resolveJail(c.root, s.join(arg)))
		if err != nil || st.IsDir() {
			s.reply(550, "Not a plain file.")
			return nil
		}
		s.reply(213, strconv.FormatInt(st.Size(), 10))
	case "MDTM":
		st, err := os.Stat(resolveJail(c.root, s.join(arg)))
		if err != nil {
			s.reply(550, "Not found.")
			return nil
		}
		s.reply(213, st.ModTime().UTC().Format("20060102150405"))
	default:
		s.reply(502, "Command not implemented.")
	}
	return nil
}

// join 组合 cwd 与相对参数为虚拟路径。
func (s *ftpSession) join(arg string) string {
	arg = strings.TrimSpace(arg)
	if strings.HasPrefix(arg, "/") {
		return arg
	}
	if arg == "" {
		return s.cwd
	}
	return path.Join(s.cwd, arg)
}

// ==================== TLS 升级 ====================

func (s *ftpSession) authTLS(arg string) {
	if s.tlsOn {
		s.reply(234, "TLS already established")
		return
	}
	a := strings.ToUpper(strings.TrimSpace(arg))
	if a != "TLS" && a != "SSL" && a != "TLS+C" && a != "SSL+C" {
		s.reply(504, "Unsupported AUTH type")
		return
	}
	if !s.cfg.sslEnable || !s.cfg.certOK {
		s.reply(504, "TLS not enabled")
		return
	}
	// 把 bufio 中已缓冲的字节交给 TLS 握手读取
	pending := make([]byte, s.br.Buffered())
	if len(pending) > 0 {
		_, _ = io.ReadFull(s.br, pending)
	}
	tc := tls.Server(&prefixConn{Conn: s.conn, r: bytes.NewReader(pending)}, &tls.Config{
		Certificates: []tls.Certificate{s.cfg.cert},
		MinVersion:   tls.VersionTLS12,
	})
	_ = tc.SetDeadline(time.Now().Add(30 * time.Second))
	if err := tc.Handshake(); err != nil {
		s.reply(425, "TLS handshake failed: "+err.Error())
		return
	}
	_ = tc.SetDeadline(time.Time{})
	s.conn = tc
	s.br = bufio.NewReader(tc)
	s.tlsOn = true
	s.reply(234, "TLS connection established")
	logfLine(s.cfg.logf, "auth tls ok user=%s", s.user)
}

// ==================== 被动 / 主动数据通道 ====================

func (s *ftpSession) pasvIP() string {
	if s.cfg.pasvAddr != "" {
		return s.cfg.pasvAddr
	}
	if s.cfg.pasvIP == "" {
		s.cfg.pasvIP = lanIP()
	}
	return s.cfg.pasvIP
}

func (s *ftpSession) openPassive() {
	if s.pasvLn != nil {
		_ = s.pasvLn.Close()
		s.pasvLn = nil
	}
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		s.reply(425, "Cannot open passive connection.")
		return
	}
	s.pasvLn = ln
	s.activeAddr = nil
	port := ln.Addr().(*net.TCPAddr).Port
	ip := net.ParseIP(s.pasvIP()).To4()
	if ip == nil {
		s.reply(425, "No IPv4 address for PASV.")
		return
	}
	s.reply(227, fmt.Sprintf("Entering Passive Mode (%d,%d,%d,%d,%d,%d)",
		ip[0], ip[1], ip[2], ip[3], port>>8, port&0xff))
}

func (s *ftpSession) openEPSV() {
	if s.pasvLn != nil {
		_ = s.pasvLn.Close()
		s.pasvLn = nil
	}
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		s.reply(425, "Cannot open passive connection.")
		return
	}
	s.pasvLn = ln
	s.activeAddr = nil
	port := ln.Addr().(*net.TCPAddr).Port
	s.reply(229, fmt.Sprintf("Entering Extended Passive Mode (|||%d|)", port))
}

func (s *ftpSession) cmdPort(arg string) {
	parts := strings.Split(strings.TrimSpace(arg), ",")
	if len(parts) != 6 {
		s.reply(501, "Syntax: PORT h1,h2,h3,h4,p1,p2")
		return
	}
	nums := make([]int, 6)
	for i, p := range parts {
		n, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || n < 0 || n > 255 {
			s.reply(501, "Invalid PORT.")
			return
		}
		nums[i] = n
	}
	ip := net.IPv4(byte(nums[0]), byte(nums[1]), byte(nums[2]), byte(nums[3]))
	port := nums[4]<<8 | nums[5]
	s.closePassive()
	s.activeAddr = &net.TCPAddr{IP: ip, Port: port}
	s.reply(200, "PORT command ok.")
}

func (s *ftpSession) cmdEPort(arg string) {
	// |proto|addr|port|  proto = 1 (IPv4) / 2 (IPv6)
	arg = strings.TrimSpace(arg)
	if len(arg) < 5 || arg[0] != '|' || arg[len(arg)-1] != '|' {
		s.reply(501, "Syntax: EPRT |proto|addr|port|")
		return
	}
	body := arg[1 : len(arg)-1]
	parts := strings.Split(body, "|")
	if len(parts) != 3 {
		s.reply(501, "Invalid EPRT.")
		return
	}
	port, err := strconv.Atoi(parts[2])
	if err != nil {
		s.reply(501, "Invalid EPRT port.")
		return
	}
	ip := net.ParseIP(parts[1])
	if ip == nil {
		s.reply(501, "Invalid EPRT address.")
		return
	}
	s.closePassive()
	s.activeAddr = &net.TCPAddr{IP: ip, Port: port}
	s.reply(200, "EPRT command ok.")
}

func (s *ftpSession) closePassive() {
	if s.pasvLn != nil {
		_ = s.pasvLn.Close()
		s.pasvLn = nil
	}
}

// openData 建立数据通道（被动=Accept 单连接；主动=回连客户端）。
func (s *ftpSession) openData() (net.Conn, error) {
	var conn net.Conn
	if s.pasvLn != nil {
		if tl, ok := s.pasvLn.(*net.TCPListener); ok {
			_ = tl.SetDeadline(time.Now().Add(30 * time.Second))
		}
		c, err := s.pasvLn.Accept()
		_ = s.pasvLn.Close()
		s.pasvLn = nil
		if err != nil {
			return nil, err
		}
		conn = c
	} else if s.activeAddr != nil {
		c, err := net.DialTimeout("tcp", s.activeAddr.String(), 15*time.Second)
		if err != nil {
			return nil, err
		}
		conn = c
	} else {
		return nil, fmt.Errorf("no PORT/PASV")
	}
	_ = conn.SetDeadline(time.Now().Add(10 * time.Minute))
	// PROT P：数据通道 TLS。被动模式下本端为 TLS server；主动模式下客户端在
	// 监听侧作为 TLS client，本端对称地以 TLS client 握手（RFC 4217）。
	if s.protPrivate && s.tlsOn && s.cfg.certOK {
		var tc *tls.Conn
		if s.activeAddr != nil && s.pasvLn == nil {
			tc = tls.Client(conn, &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12})
		} else {
			tc = tls.Server(conn, &tls.Config{Certificates: []tls.Certificate{s.cfg.cert}, MinVersion: tls.VersionTLS12})
		}
		_ = tc.SetDeadline(time.Now().Add(30 * time.Second))
		if err := tc.Handshake(); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("data tls: %w", err)
		}
		_ = tc.SetDeadline(time.Now().Add(10 * time.Minute))
		conn = tc
	}
	return conn, nil
}

// ==================== 数据传输命令 ====================

func (s *ftpSession) cmdList(listCmd, arg string) {
	// 过滤 LIST 标志位（-la 之类），取第一个非 "-" 项为路径
	pathArg := s.cwd
	for _, f := range strings.Fields(arg) {
		if strings.HasPrefix(f, "-") {
			continue
		}
		pathArg = f
		break
	}
	abs := resolveJail(s.cfg.root, s.join(pathArg))
	st, err := os.Stat(abs)
	if err != nil {
		s.reply(550, "Failed to open directory.")
		return
	}
	var entries []os.FileInfo
	if st.IsDir() {
		f, err := os.Open(abs)
		if err != nil {
			s.reply(550, "Failed to open directory.")
			return
		}
		entries, err = f.Readdir(-1)
		f.Close()
		if err != nil {
			s.reply(550, "Failed to list directory.")
			return
		}
		sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	} else {
		entries = []os.FileInfo{st}
	}
	s.reply(150, "Here comes the directory listing.")
	data, err := s.openData()
	if err != nil {
		s.reply(425, "Cannot open data connection.")
		return
	}
	var buf bytes.Buffer
	for _, e := range entries {
		if listCmd == "NLST" {
			buf.WriteString(e.Name())
			buf.WriteString("\r\n")
		} else {
			buf.WriteString(listLine(e))
		}
	}
	_, werr := io.Copy(data, strings.NewReader(buf.String()))
	_ = data.Close()
	if werr != nil {
		s.reply(426, "Data connection error.")
		return
	}
	s.reply(226, "Directory send OK.")
}

func listLine(fi os.FileInfo) string {
	perm := "-rw-r--r--"
	nlink := 1
	if fi.IsDir() {
		perm = "drwxr-xr-x"
		nlink = 2
	} else if fi.Mode()&0o111 != 0 {
		perm = "-rwxr-xr-x"
	}
	t := fi.ModTime()
	ts := t.Format("Jan _2 15:04")
	if time.Since(t) > 180*24*time.Hour {
		ts = t.Format("Jan _2  2006")
	}
	return fmt.Sprintf("%s %3d %-10s %-10s %12d %s %s\r\n",
		perm, nlink, "dirtunnel", "dirtunnel", fi.Size(), ts, fi.Name())
}

func (s *ftpSession) cmdRetr(arg string) {
	if arg == "" {
		s.reply(501, "RETR requires a path.")
		return
	}
	abs := resolveJail(s.cfg.root, s.join(arg))
	st, err := os.Stat(abs)
	if err != nil {
		s.reply(550, "File not found.")
		return
	}
	if st.IsDir() {
		s.reply(550, "Is a directory.")
		return
	}
	f, err := os.Open(abs)
	if err != nil {
		s.reply(550, "Cannot open file.")
		return
	}
	defer f.Close()
	s.reply(150, "Opening data connection for download.")
	data, err := s.openData()
	if err != nil {
		s.reply(425, "Cannot open data connection.")
		return
	}
	var src io.Reader = f
	if s.dataType == "A" {
		src = asciiRead(f)
	}
	_, cerr := io.Copy(data, src)
	_ = data.Close()
	if cerr != nil {
		s.reply(426, "Transfer aborted.")
		return
	}
	s.reply(226, "Transfer complete.")
	logfLine(s.cfg.logf, "retr user=%s path=%s bytes=%d", s.user, arg, st.Size())
}

func (s *ftpSession) cmdStor(cmd, arg string) {
	if arg == "" {
		s.reply(501, "STOR requires a path.")
		return
	}
	if s.cfg.readOnly {
		s.reply(550, "Permission denied (read-only).")
		return
	}
	abs := resolveJail(s.cfg.root, s.join(arg))
	if st, err := os.Stat(abs); err == nil && st.IsDir() {
		s.reply(550, "Is a directory.")
		return
	}
	flag := os.O_CREATE | os.O_WRONLY
	if cmd == "APPE" {
		flag |= os.O_APPEND
	} else {
		flag |= os.O_TRUNC
	}
	f, err := os.OpenFile(abs, flag, 0o644)
	if err != nil {
		s.reply(550, "Cannot create file.")
		return
	}
	defer f.Close()
	s.reply(150, "Opening data connection for upload.")
	data, err := s.openData()
	if err != nil {
		s.reply(425, "Cannot open data connection.")
		return
	}
	var dst io.Writer = f
	if s.dataType == "A" {
		dst = asciiWrite(f)
	}
	n, cerr := io.Copy(dst, data)
	_ = data.Close()
	if cerr != nil {
		s.reply(426, "Transfer aborted.")
		return
	}
	s.reply(226, "Transfer complete.")
	logfLine(s.cfg.logf, "stor user=%s path=%s bytes=%d", s.user, arg, n)
}

// ASCII 类型转换（TYPE A）：读方向 lone \n → \r\n；写方向 \r\n → \n。
type asciiReader struct {
	r    io.Reader
	prev byte
	buf  []byte
}

func asciiRead(r io.Reader) io.Reader { return &asciiReader{r: r} }

func (a *asciiReader) Read(p []byte) (int, error) {
	for {
		if len(a.buf) > 0 {
			n := copy(p, a.buf)
			a.buf = a.buf[n:]
			return n, nil
		}
		in := make([]byte, 32*1024)
		n, err := a.r.Read(in)
		if n > 0 {
			out := make([]byte, 0, n+n/32+2)
			for _, b := range in[:n] {
				if b == '\n' && a.prev != '\r' {
					out = append(out, '\r')
				}
				out = append(out, b)
				a.prev = b
			}
			a.buf = out
		}
		if err != nil {
			if len(a.buf) > 0 {
				n2 := copy(p, a.buf)
				a.buf = a.buf[n2:]
				if n2 > 0 {
					return n2, nil
				}
			}
			return 0, err
		}
	}
}

type asciiWriter struct {
	w        io.Writer
	pendingR bool // 上一字节是 \r，等待判断是否成对
}

func asciiWrite(w io.Writer) io.Writer { return &asciiWriter{w: w} }

func (a *asciiWriter) Write(p []byte) (int, error) {
	var b bytes.Buffer
	for _, ch := range p {
		if a.pendingR {
			a.pendingR = false
			if ch == '\n' {
				b.WriteByte('\n') // \r\n → \n（丢弃 \r）
				continue
			}
			b.WriteByte('\r') // 孤立 \r 原样保留
		}
		if ch == '\r' {
			a.pendingR = true
			continue
		}
		b.WriteByte(ch)
	}
	if b.Len() > 0 {
		if _, err := a.w.Write(b.Bytes()); err != nil {
			return 0, err
		}
	}
	return len(p), nil
}
