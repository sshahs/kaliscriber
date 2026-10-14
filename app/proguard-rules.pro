# R8 shrinks and optimises release builds. These rules keep what is only reached by name.

# Keep class and method names so crash logs from users stay readable; shrinking and optimising
# still apply.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature,EnclosingMethod

# sherpa-onnx (on-device speech): the native library reads Kotlin config objects field by field,
# builds result objects and calls back into Kotlin, all by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Diarization progress: native code looks up invoke(IIJ)Ljava/lang/Integer; on this class.
-keep class com.kaliscriber.app.speech.LocalSpeechEngine$DiarizationCallback { *; }

# kotlinx.serialization: generated serializers are found through companions.
-keepclassmembers @kotlinx.serialization.Serializable class com.kaliscriber.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kaliscriber.**$$serializer { *; }
-keepclassmembers class com.kaliscriber.** {
    *** Companion;
}
-keepclasseswithmembers class com.kaliscriber.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# commons-compress has optional codecs we don't ship (we only read tar.bz2 model archives).
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
