package main

// SFTP 服务器：自研 SSH 传输层（golang.org/x/crypto/ssh）+ SFTP 子系统
// （github.com/pkg/sftp request-server），单二进制承载子系统的"sshd"角色。
//
// 读取 EngineConfigBuilder 生成的 sshd_config 子集：
// Port / ListenAddress / HostKey / PasswordAuthentication / AllowUsers /
// PasswordFile / Subsystem（忽略：本进程即是子系统）/ PidFile（忽略）。
// 只读与禁止删除权限通过 sftp.ReadOnly + FileCmder 拒绝实现（REQ 4.1.4）。

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"os"
	"strings"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

type sftpCfg struct {
	listen string
	port   int
	root   string
	anonymous, passwordAuth bool
	auth   *accounts
	readOnly, allowDelete bool
	logf   *os.File
	sshCfg *ssh.ServerConfig
}

func runSFTP(cfgPath string, ready chan<- startup) error {
	kv, err := parseSSHD(cfgPath)
	if err != nil {
		return fmt.Errorf("读取配置: %w", err)
	}
	c := &sftpCfg{
		listen:       kvStr(kv, "ListenAddress", "127.0.0.1"),
		port:         kvInt(kv, "Port", 2222),
		passwordAuth: kvBool(kv, "PasswordAuthentication", true),
		logf:         openLogFile(""),
	}
	// root/权限由 Subsystem 行携带（我们生成的配置里 sftp-server 参数带 -d/-R），
	// 同时兼容直接写在配置里的自定义键。
	c.root = kvStr(kv, "RootPath", "")
	c.readOnly = kvBool(kv, "ReadOnly", false)
	c.allowDelete = kvBool(kv, "AllowDelete", true)
	if v := kv["Subsystem"]; v != "" {
		fields := strings.Fields(v)
		// Subsystem sftp <bin> -d <root> [-R]
		for i := 0; i < len(fields); i++ {
			switch fields[i] {
			case "-d":
				if i+1 < len(fields) {
					c.root = fields[i+1]
				}
			case "-R":
				c.readOnly = true
			}
		}
	}
	if c.root == "" {
		// 回退：无 root 时拒绝启动（配置由 Kotlin 侧生成，必有 -d）
		return fmt.Errorf("配置缺少 root（Subsystem -d）")
	}
	if err := os.MkdirAll(c.root, 0o755); err != nil {
		return fmt.Errorf("root 不可用: %w", err)
	}
	// AllowUsers/PasswordFile → 账号；都没有则匿名（任意口令）
	if pf := kv["PasswordFile"]; pf != "" {
		c.auth = loadAccounts(pf)
		if c.auth == nil && !kvBool(kv, "AnonymousHint", false) {
			// 账号文件缺失视为匿名模式（与 ftp 一致的降级行为）
		}
	}

	signer, err := loadOrGenHostKey(kv["HostKey"])
	if err != nil {
		return fmt.Errorf("主机密钥: %w", err)
	}

	sshCfg := &ssh.ServerConfig{
		PasswordCallback: func(conn ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
			if !c.passwordAuth {
				return nil, fmt.Errorf("password authentication disabled")
			}
			user := conn.User()
			if c.auth != nil && !c.auth.userAllowed(user) {
				logfLine(c.logf, "sftp login denied (user not allowed) user=%s", user)
				return nil, fmt.Errorf("user not allowed")
			}
			if !c.auth.check(user, string(pass)) {
				logfLine(c.logf, "sftp login failed user=%s", user)
				return nil, fmt.Errorf("invalid password")
			}
			logfLine(c.logf, "sftp login ok user=%s", user)
			return nil, nil
		},
		// 允许 TCP 转发类请求一律拒绝（RequestSubsystem 只放行 sftp）
		MaxAuthTries: 4,
	}
	sshCfg.AddHostKey(signer)
	c.sshCfg = sshCfg

	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", c.listen, c.port))
	if err != nil {
		return fmt.Errorf("监听 %s:%d: %w", c.listen, c.port, err)
	}
	fmt.Printf("[sftp] ready on %s:%d root=%s\n", c.listen, c.port, c.root)
	if ready != nil {
		ready <- startup{shutdown: func() { _ = ln.Close() }}
	}
	for {
		conn, err := ln.Accept()
		if err != nil {
			return fmt.Errorf("accept: %w", err)
		}
		go func() {
			defer func() {
				if r := recover(); r != nil {
					setErr(fmt.Errorf("sftp 连接异常: %v", r))
				}
			}()
			c.handleSSH(conn)
		}()
	}
}

func (c *sftpCfg) handleSSH(conn net.Conn) {
	defer conn.Close()
	sc, chans, reqs, err := ssh.NewServerConn(conn, c.sshCfg)
	if err != nil {
		logfLine(c.logf, "ssh handshake failed from=%s err=%v", conn.RemoteAddr(), err)
		return
	}
	defer sc.Close()
	go ssh.DiscardRequests(reqs)
	for newChan := range chans {
		if newChan.ChannelType() != "session" {
			_ = newChan.Reject(ssh.UnknownChannelType, "only session supported")
			continue
		}
		ch, chReqs, err := newChan.Accept()
		if err != nil {
			return
		}
		go func() {
			defer func() {
				if r := recover(); r != nil {
					setErr(fmt.Errorf("sftp 会话异常: %v", r))
				}
			}()
			defer ch.Close()
			for req := range chReqs {
				switch req.Type {
				case "subsystem":
					var sub struct{ Name string }
					if err := ssh.Unmarshal(req.Payload, &sub); err == nil && strings.EqualFold(sub.Name, "sftp") {
						_ = req.Reply(true, nil)
						c.serveSFTP(ch)
						return
					}
					_ = req.Reply(false, nil)
				default:
					// shell/exec/pty 等一律拒绝：本服务只做文件传输
					_ = req.Reply(false, nil)
				}
			}
		}()
	}
}

func (c *sftpCfg) serveSFTP(ch ssh.Channel) {
	h := &fsHandler{root: c.root, readOnly: c.readOnly, allowDelete: c.allowDelete}
	handlers := sftp.Handlers{
		FileGet:  h,
		FilePut:  h,
		FileCmd:  h,
		FileList: h,
	}
	// 只读语义由 handlers 强制（Filewrite/Filecmd 拒绝写与删除）
	server := sftp.NewRequestServer(ch, handlers)
	defer server.Close()
	if err := server.Serve(); err != nil && err != io.EOF {
		logfLine(c.logf, "sftp session ended: %v", err)
	}
}

// ==================== 主机密钥 ====================

// loadOrGenHostKey 加载 HostKey（Kotlin 侧生成的 PKCS#8 RSA PEM，
// 或 OpenSSH 格式）；不可用时生成 ed25519 并覆盖（0600）。
func loadOrGenHostKey(p string) (ssh.Signer, error) {
	if p != "" {
		if pemBytes, err := os.ReadFile(p); err == nil && len(pemBytes) > 0 {
			if s, err := ssh.ParsePrivateKey(pemBytes); err == nil {
				return s, nil
			}
		}
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, err
	}
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
	if p != "" {
		if err := os.WriteFile(p, pemBytes, 0o600); err != nil {
			return nil, err
		}
	}
	return ssh.ParsePrivateKey(pemBytes)
}

// ==================== SFTP 文件系统 Handler ====================

// fsHandler 同时实现 FileReader/FileWriter/FileCmder/FileLister。
type fsHandler struct {
	root        string
	readOnly    bool
	allowDelete bool
}

func (h *fsHandler) abs(vpath string) string {
	return resolveJail(h.root, vpath)
}

func mapFSError(err error) error {
	if err == nil {
		return nil
	}
	if os.IsNotExist(err) {
		return sftp.ErrSSHFxNoSuchFile
	}
	if os.IsPermission(err) {
		return sftp.ErrSSHFxPermissionDenied
	}
	return sftp.ErrSSHFxFailure
}

// ----- 读（Get） -----

func (h *fsHandler) Fileread(r *sftp.Request) (io.ReaderAt, error) {
	f, err := os.Open(h.abs(r.Filepath))
	if err != nil {
		return nil, mapFSError(err)
	}
	return f, nil
}

// ----- 写（Put / Open） -----

func (h *fsHandler) Filewrite(r *sftp.Request) (io.WriterAt, error) {
	if h.readOnly {
		return nil, sftp.ErrSSHFxPermissionDenied
	}
	flag := os.O_RDWR | os.O_CREATE
	// sftp 打开标志（协议固定值）：APPEND=0x00000004 TRUNC=0x00000008 EXCL=0x00000010
	// 注意：不用 O_APPEND——pkg/sftp 通过 WriteAt 写入，二者冲突（见其文档）；
	// 追加模式由客户端自行把偏移定位到文件尾。
	if r.Flags&0x00000008 != 0 {
		flag |= os.O_TRUNC
	}
	if r.Flags&0x00000010 != 0 {
		flag |= os.O_EXCL
	}
	f, err := os.OpenFile(h.abs(r.Filepath), flag, 0o644)
	if err != nil {
		return nil, mapFSError(err)
	}
	return f, nil
}

// ----- 命令（MKD/RMD/Remove/Rename/Setstat...） -----

func (h *fsHandler) Filecmd(r *sftp.Request) error {
	switch r.Method {
	case "Rename", "PosixRename":
		if h.readOnly || !h.allowDelete {
			return sftp.ErrSSHFxPermissionDenied
		}
		return mapFSError(os.Rename(h.abs(r.Filepath), h.abs(r.Target)))
	case "Rmdir":
		if h.readOnly || !h.allowDelete {
			return sftp.ErrSSHFxPermissionDenied
		}
		return mapFSError(os.Remove(h.abs(r.Filepath)))
	case "Remove":
		if h.readOnly || !h.allowDelete {
			return sftp.ErrSSHFxPermissionDenied
		}
		return mapFSError(os.Remove(h.abs(r.Filepath)))
	case "Mkdir":
		if h.readOnly {
			return sftp.ErrSSHFxPermissionDenied
		}
		return mapFSError(os.Mkdir(h.abs(r.Filepath), 0o755))
	case "Setstat", "Fsetstat":
		if h.readOnly {
			return sftp.ErrSSHFxPermissionDenied
		}
		// 属性设置尽力而为：本场景仅 chmod/时间戳，静默忽略不支持的属性
		return nil
	case "Symlink", "Link":
		return sftp.ErrSSHFxOpUnsupported
	default:
		return sftp.ErrSSHFxOpUnsupported
	}
}

// ----- 列表（List / Stat / Readlink） -----

func (h *fsHandler) Filelist(r *sftp.Request) (sftp.ListerAt, error) {
	p := h.abs(r.Filepath)
	st, err := os.Stat(p)
	if err != nil {
		return nil, mapFSError(err)
	}
	if !st.IsDir() {
		return listerAt{st}, nil
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, mapFSError(err)
	}
	defer f.Close()
	infos, err := f.Readdir(-1)
	if err != nil {
		return nil, mapFSError(err)
	}
	return listerAt(infos), nil
}

type listerAt []os.FileInfo

func (l listerAt) ListAt(dst []os.FileInfo, off int64) (int, error) {
	if off >= int64(len(l)) {
		return 0, io.EOF
	}
	n := copy(dst, l[off:])
	return n, nil
}
