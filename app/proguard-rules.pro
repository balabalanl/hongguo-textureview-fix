# Module entry point must survive any future R8 run.
-keep class com.zcode.hongguofix.ModuleMain { *; }
-keep class com.zcode.hongguofix.** { *; }
-dontwarn io.github.libxposed.**
