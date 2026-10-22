// JNI 胶水：将 Go 导出的 StartServer/StopServer/LastError 暴露为
// Java_com_dirtunnel_core_engine_process_NativeEngine_* 符号，
// 与 Go 代码一同编入 c-shared .so（cgo 会编译包内 .c 文件）。
#include <jni.h>
#include <stdlib.h>

extern int StartServer(const char *mode, const char *configPath);
extern void StopServer(int handle);
extern char *LastError(void);

JNIEXPORT jint JNICALL
Java_com_dirtunnel_core_engine_process_NativeEngine_startServer(
    JNIEnv *env, jclass cls, jstring mode, jstring configPath) {
	(void)cls;
	const char *m = (*env)->GetStringUTFChars(env, mode, 0);
	const char *p = (*env)->GetStringUTFChars(env, configPath, 0);
	int h = -1;
	if (m && p) {
		h = StartServer(m, p);
	}
	if (m) (*env)->ReleaseStringUTFChars(env, mode, m);
	if (p) (*env)->ReleaseStringUTFChars(env, configPath, p);
	return (jint)h;
}

JNIEXPORT void JNICALL
Java_com_dirtunnel_core_engine_process_NativeEngine_stopServer(
    JNIEnv *env, jclass cls, jint handle) {
	(void)env;
	(void)cls;
	StopServer((int)handle);
}

JNIEXPORT jstring JNICALL
Java_com_dirtunnel_core_engine_process_NativeEngine_lastError(
    JNIEnv *env, jclass cls) {
	(void)cls;
	char *e = LastError();
	jstring s = (*env)->NewStringUTF(env, e ? e : "");
	if (e) free(e);
	return s;
}
