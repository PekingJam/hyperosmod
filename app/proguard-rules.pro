# Keep the libxposed entry point readable: it is resolved from META-INF/xposed/java_init.list.
-keep class com.hyperosmod.xposed.ModuleEntry { *; }
-keep class com.hyperosmod.xposed.** { *; }

# The framework provides these classes at runtime.
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.api.** { *; }

# Keep annotations used by the framework/service descriptors.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep resource names referenced from XML / reflection.
-keepclassmembers class **.R$* {
    public static <fields>;
}
