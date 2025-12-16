# ============================================
# WebSnap ProGuard Rules
# ============================================
# 保留行号信息，方便调试崩溃日志
-keepattributes SourceFile,LineNumberTable

# 保留 Parcelable 实现
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
