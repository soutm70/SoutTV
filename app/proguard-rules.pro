# Add project specific ProGuard rules here.

# FFmpeg audio decoder extension (vendored AAR). DefaultRenderersFactory loads
# FfmpegAudioRenderer reflectively, so keep it (and the JNI bridge) even if R8
# shrinking is ever enabled, otherwise AC-3/E-AC-3/DTS broadcast audio silently
# loses its software-decode fallback.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
