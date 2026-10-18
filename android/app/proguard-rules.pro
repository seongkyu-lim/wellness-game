# kotlinx.serialization: @Serializable DTO의 serializer를 유지한다.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class com.example.wellnessgame.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.wellnessgame.**$$serializer { *; }

# Retrofit 인터페이스(제네릭 반환형 유지)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.example.wellnessgame.data.network.WellnessApi { *; }

# Kakao / Naver SDK
-keep class com.kakao.sdk.**.model.* { <fields>; }
-dontwarn com.kakao.sdk.**
-keep class com.navercorp.nid.** { *; }
-dontwarn com.navercorp.nid.**
