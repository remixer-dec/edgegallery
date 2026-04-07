# AI Edge Gallery ProGuard Rules

# Keep Compose
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.accompanist.permissions.ExperimentalPermissionsApi <fields>;
}

# Keep Hilt
-keep class dagger.hilt.android.internal.buildflags.BuildInfoProviderFactory { *; }
-keepattributes annotations

# Keep Moshi
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.squareup.moshiToJson <fields>;
}
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
}

# Keep Protobuf
-dontwarn javax.annotation.**
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @com.google.protobuf.* <methods>;
}
-keepclassmembers,allowoptimization class * {
    @com.google.protobuf.* <fields>;
}
-keepclassmembers class * {
    @com.google.protobuf.Descriptors.FileDescriptorInternal <fields>;
}
-keepclassmembers class com.google.protobuf.Descriptors$FileDescriptorProto {
    public <init>(...);
}
-keepclassmembers class com.google.protobuf.Descriptors$DescriptorProtos { *; }

# Keep TensorFlow Lite
-dontwarn org.tensorflow.lite.**
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }

# Tink KeysDownloader references external HTTP clients not used by this app
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**

# Keep TFLite model loading
-keep class * extends java.lang.Object {
    native <methods>;
}

# Common Android
-keepattributes EnclosingMethod
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes UserAnnotation
-dontnote android.webkit.JavascriptInterface
-keepclassmembers class * implements android.webkit.JavascriptInterface {
    public <methods>;
}

# For JNI
-keepclassmembers class * {
    native <methods>;
}

# Keep the Application class - R8 was removing it causing ClassNotFoundException
-keep class com.google.ai.edge.gallery.GalleryApplication { *; }
-keep class com.google.ai.edge.gallery.** { *; }

# Keep CustomTask implementations
-keep class com.google.ai.edge.gallery.customtasks.** { *; }

# Keep LiteRT LM classes - accessed from native code via JNI
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
