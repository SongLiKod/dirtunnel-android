#!/system/bin/sh
# libdirtunneld.so 进程内加载冒烟：dlopen → 启动/客户端/停止/重启 → 异常路径
set -u
T=/data/user/0/com.mobilecoder.ide/files/projects/dirtunnel-android/tools/dirtunneld
D=/data/user/0/com.mobilecoder.ide/cache/tmp/opencode/dtest
export PATH=/data/user/0/com.mobilecoder.ide/files/gosdk/go/bin:$PATH
export GOPATH=/data/user/0/com.mobilecoder.ide/files/gosdk/gopath
export GOCACHE=/data/user/0/com.mobilecoder.ide/files/gosdk/gocache
export GOPROXY=https://goproxy.cn,direct
export GOOS=linux GOARCH=arm64 CGO_ENABLED=0
cd "$T" || exit 1

wait_line() {
	i=0
	while [ "$i" -lt "$2" ]; do
		grep -q "$1" "$3" 2>/dev/null && return 0
		sleep 0.5
		i=$((i + 1))
	done
	return 1
}

run_case() {
	mode=$1
	cfg=$2
	client=$3
	out="h_${mode}.out"
	rm -f "$out"
	./harness "$mode" "$cfg" 5 restart > "$out" 2>&1 &
	hp=$!
	if ! wait_line "^STARTED" 20 "$out"; then
		echo "$mode: FAIL no STARTED -> $(cat "$out")"
		wait "$hp"
		return 1
	fi
	echo "$mode pass1:"
	$client
	if ! wait_line "^RESTARTED" 20 "$out"; then
		echo "$mode: FAIL no RESTARTED -> $(cat "$out")"
		kill "$hp" 2>/dev/null
		return 1
	fi
	echo "$mode pass2:"
	$client
	wait "$hp" || { echo "$mode: harness rc=$? -> $(cat "$out")"; return 1; }
	grep -qE "FAIL" "$out" && { echo "$mode: FAILED -> $(cat "$out")"; return 1; }
	echo "$mode: OK ($(grep -cE '^(STARTED|RESTARTED)' "$out") starts)"
}

http_client() {
	c1=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/)
	c2=$(curl -s -u dirtunnel:Abc123xyz -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/hello2.txt)
	echo "  http noauth=$c1(401) auth-get=$c2(200)"
}
ftp_client() {
	f=$(curl -s -u dirtunnel:Abc123xyz --list-only ftp://127.0.0.1:12121/ | wc -l)
	echo "  ftp list-lines=$f(>0)"
}
sftp_client() {
	r=$(go run ./cmd/sftptest 127.0.0.1:12222 dirtunnel Abc123xyz 2>&1 | tail -1)
	echo "  sftp $r"
}

run_case http "$D/minihttpd.conf" http_client
run_case ftp "$D/ftpd.conf" ftp_client
run_case sftp "$D/sshd_config" sftp_client

./harness http "$D/minihttpd.conf" 1 dupstart > h_dup.out 2>&1
grep -q "DUPSTART_REJECTED: " h_dup.out && echo "dupstart: $(head -c 120 h_dup.out)" || echo "dupstart FAIL: $(cat h_dup.out)"

./harness bogus "$D/minihttpd.conf" 1 > h_bad.out 2>&1
grep -q "START_FAIL: " h_bad.out && echo "badmode: $(head -c 120 h_bad.out)" || echo "badmode FAIL: $(cat h_bad.out)"
