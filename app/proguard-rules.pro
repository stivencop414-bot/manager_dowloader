# Manager Downloader v0.8.1 security release rules.
# Native bindings must keep their JNI-visible names.
-keep class com.frostwire.jlibtorrent.** { *; }
-dontwarn com.frostwire.jlibtorrent.**

# NewPipe uses reflection in portions of extractor metadata and service implementations.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Keep Android WebView bridge listener types referenced by platform callbacks.
-keepclassmembers class * implements androidx.webkit.WebViewCompat$WebMessageListener { *; }

# NewPipeExtractor v0.26.3 uses Rhino. These Android/R8 rules follow the
# upstream NewPipe release configuration for Rhino/JSR-223 and additionally
# suppress only the five JVM-only java.beans types reported by R8 in run
# 33107012993. Android does not provide java.beans; the optional Rhino
# JavaToJSONConverters path must not become a release-build blocker.
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**

# Rhino engine metadata references JSR-223 classes that are not part of the
# Android runtime. NewPipe upstream keeps/suppresses these explicitly.
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

# Exact java.beans classes reported missing by R8. No global ignore-warning rule.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
