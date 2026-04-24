# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class tw.pp.kazi.**$$serializer { *; }
-keepclassmembers class tw.pp.kazi.** {
    *** Companion;
}
-keepclasseswithmembers class tw.pp.kazi.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
