# DirTunnel ProGuard rules
# Compose + Hilt 已由各自规则覆盖；此处仅保留调试所需。
-keepattributes *Annotation*

# JNI（libdirtunneld.so 进程内协议引擎）：符号名与 Java 类/方法绑定，不可混淆
-keep class com.dirtunnel.core.engine.process.NativeEngine { *; }
