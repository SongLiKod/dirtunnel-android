// sftptest：SFTP 子系统冒烟客户端（开发期测试用，不参与 APK 构建）。
//
//	go run ./cmd/sftptest 127.0.0.1:12222 dirtunnel Abc123xyz [readonly]
package main

import (
	"fmt"
	"io"
	"os"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

func main() {
	addr := "127.0.0.1:12222"
	user, pass := "dirtunnel", "Abc123xyz"
	readonly := false
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}
	if len(os.Args) > 3 {
		user, pass = os.Args[2], os.Args[3]
	}
	if len(os.Args) > 4 && os.Args[4] == "readonly" {
		readonly = true
	}

	cc, err := ssh.Dial("tcp", addr, &ssh.ClientConfig{
		User:            user,
		Auth:            []ssh.AuthMethod{ssh.Password(pass)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
	})
	fail("ssh dial", err)
	defer cc.Close()

	sc, err := sftp.NewClient(cc)
	fail("sftp client", err)
	defer sc.Close()

	// 1) 列表
	entries, err := sc.ReadDir("/")
	fail("readdir /", err)
	fmt.Printf("PASS readdir / -> %d entries\n", len(entries))

	// 2) 下载
	rf, err := sc.Open("/hello2.txt")
	fail("open /hello2.txt", err)
	data, err := io.ReadAll(rf)
	fail("read /hello2.txt", err)
	fail("close read", rf.Close())
	fmt.Printf("PASS read /hello2.txt -> %q\n", string(data))

	// 3) 上传（只读模式下跳过，稍后单独断言拒绝）
	if !readonly {
		f, err := sc.Create("/sftp_up.txt")
		fail("create /sftp_up.txt", err)
		_, err = f.Write([]byte("sftp upload ok"))
		fail("write", err)
		fail("close", f.Close())
		fmt.Println("PASS create+write /sftp_up.txt")

		// 4) 目录 / 重命名
		fail("mkdir /sdir", sc.Mkdir("/sdir"))
		fail("rename", sc.Rename("/sftp_up.txt", "/sdir/moved.txt"))
		fmt.Println("PASS mkdir+rename")

		entries, err = sc.ReadDir("/sdir")
		fail("readdir /sdir", err)
		if len(entries) != 1 {
			fail("readdir /sdir size", fmt.Errorf("want 1 got %d", len(entries)))
		}
		fmt.Println("PASS readdir /sdir -> moved.txt")

		// 5) 删除
		fail("remove", sc.Remove("/sdir/moved.txt"))
		fail("rmdir", sc.RemoveDirectory("/sdir"))
		fmt.Println("PASS remove+removeDirectory")
	}

	if readonly {
		// 只读变体：写入必须失败
		if f, err := sc.Create("/nope.txt"); err == nil {
			f.Close()
			fail("readonly create must fail", fmt.Errorf("created!"))
		}
		fmt.Println("PASS readonly rejects write")
		// 只读也应拒绝删除
		if err := sc.Remove("/hello2.txt"); err == nil {
			fail("readonly remove must fail", fmt.Errorf("removed!"))
		}
		fmt.Println("PASS readonly rejects delete")
	}

	fmt.Println("ALL SFTP TESTS PASSED")
}

func fail(step string, err error) {
	if err != nil {
		fmt.Printf("FAIL %s: %v\n", step, err)
		os.Exit(1)
	}
}
