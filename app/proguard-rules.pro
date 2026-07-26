# Keep the JNI entry points: their names are part of the ABI contract with
# rust/majsoul-jni and app/src/main/cpp, and R8 has no way to see the callers.
-keepclasseswithmembernames,includedescriptorclasses class moe.majsoulmax.app.core.MitmNative {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class moe.majsoulmax.app.core.Tun2SocksNative {
    native <methods>;
}
# kotlinx.serialization: keep the generated serializers for the models that are
# persisted to disk and read back by another process.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class moe.majsoulmax.app.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class moe.majsoulmax.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class moe.majsoulmax.app.data.TunnelSettings { *; }
-keep,includedescriptorclasses class moe.majsoulmax.app.data.TunnelSettings$* { *; }
-keep,includedescriptorclasses class moe.majsoulmax.app.data.TunnelStatus { *; }
-keep,includedescriptorclasses class moe.majsoulmax.app.data.TunnelStatus$* { *; }

# The VpnService is resolved by the framework from the manifest.
-keep class moe.majsoulmax.app.service.MajsoulVpnService { *; }

# WebView JavaScript bridge is unused, but keep androidx.webkit's boundary
# interfaces intact.
-keep class androidx.webkit.** { *; }
