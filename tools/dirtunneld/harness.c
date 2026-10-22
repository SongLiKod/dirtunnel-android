// harness.c：进程内加载 libdirtunneld.so 的冒烟宿主（开发期测试，不进包）。
// 用法：harness <ftp|http|sftp> <config> <seconds> [restart|dupstart]
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*start_fn)(const char *, const char *);
typedef void (*stop_fn)(int);
typedef char *(*err_fn)(void);

static start_fn g_start;
static stop_fn g_stop;
static err_fn g_err;

static int start_or_fail(const char *mode, const char *cfg, const char *tag) {
	int id = g_start(mode, cfg);
	if (id < 0) {
		char *e = g_err();
		fprintf(stderr, "%s_FAIL: %s\n", tag, e ? e : "(empty)");
		free(e);
		return -1;
	}
	printf("%s id=%d\n", tag, id);
	fflush(stdout);
	return id;
}

int main(int argc, char **argv) {
	if (argc < 4) {
		fprintf(stderr, "usage: harness <mode> <cfg> <secs> [restart|dupstart]\n");
		return 2;
	}
	void *h = dlopen("./libdirtunneld.so", RTLD_NOW);
	if (!h) {
		fprintf(stderr, "dlopen: %s\n", dlerror());
		return 2;
	}
	g_start = (start_fn)dlsym(h, "StartServer");
	g_stop = (stop_fn)dlsym(h, "StopServer");
	g_err = (err_fn)dlsym(h, "LastError");
	if (!g_start || !g_stop || !g_err) {
		fprintf(stderr, "dlsym missing: %p %p %p\n", (void *)g_start, (void *)g_stop, (void *)g_err);
		return 3;
	}

	int secs = atoi(argv[3]);

	if (argc > 4 && strcmp(argv[4], "dupstart") == 0) {
		int id = g_start(argv[1], argv[2]);
		if (id >= 0) {
			fprintf(stderr, "DUPSTART_UNEXPECTED_OK id=%d\n", id);
			g_stop(id);
			return 1;
		}
		char *e = g_err();
		printf("DUPSTART_REJECTED: %s\n", e ? e : "(empty)");
		free(e);
		return 0;
	}

	int id = start_or_fail(argv[1], argv[2], "STARTED");
	if (id < 0)
		return 1;
	sleep(secs);
	g_stop(id);
	printf("STOPPED\n");
	fflush(stdout);

	if (argc > 4 && strcmp(argv[4], "restart") == 0) {
		int id2 = start_or_fail(argv[1], argv[2], "RESTARTED");
		if (id2 < 0)
			return 1;
		sleep(secs);
		g_stop(id2);
		printf("RESTARTED_STOPPED\n");
		fflush(stdout);
	}
	return 0;
}
