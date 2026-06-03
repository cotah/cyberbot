# Default ProGuard rules for CyberBot.
# Keep Gson model classes intact (reflection-based serialization).
-keep class com.cyberbot.ai.network.models.** { *; }

# OkHttp / Okio platform warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
