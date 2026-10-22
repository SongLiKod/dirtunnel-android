package main

// HTTP / WebDAV 服务器。
//
// - GET/HEAD/OPTIONS 由本进程直接处理（index 文件、目录列表、Range 下载）；
// - dav=yes 时其余方法（PROPFIND/PUT/MKCOL/MOVE/COPY/DELETE/LOCK...）交由
//   golang.org/x/net/webdav 处理，并按 readonly / delete 权限前置拦截；
// - auth_required=yes 时全方法走 HTTP Basic（账号来自 0600 accounts 文件）；
// - ssl_enable 时启用 TLS（自定义证书或自动生成的自签证书）。

import (
	"crypto/tls"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/net/webdav"
)

type httpCfg struct {
	bind, root, dir string
	port, maxClients int
	index      []string
	listDir    bool
	anonymous  bool
	authReq    bool
	auth       *accounts
	readOnly   bool
	allowDelete bool
	dav        bool
	ssl        bool
	cert       tls.Certificate
	certOK     bool
	certFile, keyFile string
	logf       *os.File
	webdav     *webdav.Handler
}

func runHTTP(cfgPath string, ready chan<- startup) error {
	kv, err := parseKV(cfgPath)
	if err != nil {
		return fmt.Errorf("读取配置: %w", err)
	}
	c := &httpCfg{
		bind:        kvStr(kv, "bind", "127.0.0.1"),
		port:        kvInt(kv, "port", 8080),
		root:        kv["dir"],
		listDir:     kvBool(kv, "directory_listing", true),
		anonymous:   kvBool(kv, "anonymous", false),
		authReq:     kvBool(kv, "auth_required", false),
		readOnly:    kvBool(kv, "readonly", true),
		allowDelete: kvBool(kv, "delete", false),
		dav:         kvBool(kv, "dav", false),
		ssl:         kvBool(kv, "ssl_enable", false),
		certFile:    kv["tls_cert"],
		keyFile:     kv["tls_key"],
		dir:         filepath.Dir(cfgPath),
		logf:        openLogFile(kv["log_file"]),
		maxClients:  kvInt(kv, "max_clients", 32),
	}
	if c.root == "" {
		return fmt.Errorf("配置缺少 dir")
	}
	if err := os.MkdirAll(c.root, 0o755); err != nil {
		return fmt.Errorf("root 不可用: %w", err)
	}
	c.index = []string{"index.html", "index.htm"}
	if v := kv["index_files"]; v != "" {
		c.index = strings.Split(v, ",")
	}
	if !c.anonymous {
		c.auth = loadAccounts(kv["auth_file"])
		c.authReq = c.authReq || kv["auth_required"] == "" // 有账号文件默认要求认证
	}
	if c.ssl {
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
	if c.ssl {
		ln = tls.NewListener(ln, &tls.Config{
			Certificates: []tls.Certificate{c.cert},
			MinVersion:   tls.VersionTLS12,
		})
	}
	fmt.Printf("[http] ready on %s:%d root=%s dav=%v tls=%v\n", c.bind, c.port, c.root, c.dav, c.ssl)

	maxC := c.maxClients
	if maxC <= 0 {
		maxC = 32
	}
	sem := make(chan struct{}, maxC)
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
				c.serve(w, r)
			default:
				http.Error(w, "503 Too Many Connections", http.StatusServiceUnavailable)
			}
		}),
		ReadHeaderTimeout: 15 * time.Second,
	}
	if ready != nil {
		ready <- startup{shutdown: func() { _ = srv.Close() }}
	}
	if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

func (c *httpCfg) serve(w http.ResponseWriter, r *http.Request) {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	if c.authReq {
		user, pass, ok := r.BasicAuth()
		if !ok || !c.auth.check(user, pass) {
			w.Header().Set("WWW-Authenticate", `Basic realm="DirTunnel"`)
			http.Error(w, "401 Unauthorized", http.StatusUnauthorized)
			logfLine(c.logf, "401 %s %s from=%s", r.Method, r.URL.Path, host)
			return
		}
	}
	m := r.Method
	switch {
	case (m == http.MethodPut || m == http.MethodDelete || m == "MKCOL" || m == "MOVE" || m == "COPY") && c.readOnly:
		http.Error(w, "403 Read-only share", http.StatusForbidden)
		logfLine(c.logf, "403(read-only) %s %s from=%s", m, r.URL.Path, host)
		return
	case (m == http.MethodDelete || m == "MOVE") && !c.allowDelete:
		http.Error(w, "403 Delete disabled", http.StatusForbidden)
		logfLine(c.logf, "403(no-delete) %s %s from=%s", m, r.URL.Path, host)
		return
	}
	switch m {
	case http.MethodGet, http.MethodHead:
		c.serveStatic(w, r)
	case http.MethodOptions:
		if c.dav {
			w.Header().Set("DAV", "1, 2")
			w.Header().Set("Allow", "GET, HEAD, OPTIONS, PUT, DELETE, MKCOL, MOVE, COPY, PROPFIND, PROPPATCH, LOCK, UNLOCK")
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("Allow", "GET, HEAD, OPTIONS")
		w.WriteHeader(http.StatusOK)
	default:
		if !c.dav {
			http.Error(w, "405 Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}
		c.webdavHandler().ServeHTTP(w, r)
	}
	logfLine(c.logf, "%s %s from=%s", m, r.URL.Path, host)
}

func (c *httpCfg) webdavHandler() *webdav.Handler {
	if c.webdav == nil {
		c.webdav = &webdav.Handler{
			FileSystem: webdav.Dir(c.root),
			LockSystem: webdav.NewMemLS(),
			Logger: func(r *http.Request, err error) {
				if err != nil {
					logfLine(c.logf, "dav %s %s err=%v", r.Method, r.URL.Path, err)
				}
			},
		}
	}
	return c.webdav
}

// serveStatic：GET/HEAD —— index 文件 → 目录列表 → 单文件（支持 Range）。
func (c *httpCfg) serveStatic(w http.ResponseWriter, r *http.Request) {
	vpath := path.Clean("/" + strings.TrimPrefix(r.URL.Path, "/"))
	abs := resolveJail(c.root, vpath)
	st, err := os.Stat(abs)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if st.IsDir() {
		// 无尾斜杠时补跳转，保证相对链接正确
		if !strings.HasSuffix(r.URL.Path, "/") {
			u := r.URL.Path + "/"
			if r.URL.RawQuery != "" {
				u += "?" + r.URL.RawQuery
			}
			http.Redirect(w, r, u, http.StatusMovedPermanently)
			return
		}
		for _, idx := range c.index {
			idx = strings.TrimSpace(idx)
			if idx == "" {
				continue
			}
			ip := filepath.Join(abs, filepath.FromSlash(path.Clean("/"+idx)))
			if s2, err := os.Stat(ip); err == nil && !s2.IsDir() {
				abs, st = ip, s2
				break
			}
		}
	}
	if st.IsDir() {
		if !c.listDir {
			http.Error(w, "403 Directory listing disabled", http.StatusForbidden)
			return
		}
		c.serveListing(w, r, abs, vpath)
		return
	}
	f, err := os.Open(abs)
	if err != nil {
		http.Error(w, "403 Forbidden", http.StatusForbidden)
		return
	}
	defer f.Close()
	http.ServeContent(w, r, st.Name(), st.ModTime(), f)
}

func (c *httpCfg) serveListing(w http.ResponseWriter, r *http.Request, abs, vpath string) {
	f, err := os.Open(abs)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	entries, err := f.ReadDir(-1)
	if err != nil {
		http.Error(w, "500", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	if r.Method == http.MethodHead {
		return
	}
	var b strings.Builder
	b.WriteString("<!doctype html><html><head><meta charset=utf-8>")
	b.WriteString("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
	b.WriteString("<title>Index of " + html.EscapeString(vpath) + "</title>")
	b.WriteString("<style>body{font-family:-apple-system,Roboto,sans-serif;margin:24px auto;max-width:720px;color:#111}")
	b.WriteString("a{color:#007AFF;text-decoration:none}li{padding:6px 0;border-bottom:1px solid #eee}")
	b.WriteString("h1{font-size:20px}</style></head><body>")
	b.WriteString("<h1>Index of " + html.EscapeString(vpath) + "</h1><ul>")
	if vpath != "/" {
		b.WriteString("<li><a href=\"../\">../</a></li>")
	}
	for _, e := range entries {
		href := url.PathEscape(e.Name())
		name := e.Name()
		suffix := "/"
		if !e.IsDir() {
			suffix = ""
		} else {
			href += "/"
		}
		b.WriteString("<li><a href=\"" + href + "\">" + html.EscapeString(name+suffix) + "</a></li>")
	}
	b.WriteString("</ul></body></html>")
	_, _ = io.WriteString(w, b.String())
}
