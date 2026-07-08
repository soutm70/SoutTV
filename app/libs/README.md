# Vendored libraries

## media3-decoder-ffmpeg.aar

The Media3 FFmpeg audio-decoder extension. Google does not publish a prebuilt
artifact for it, so this AAR is built from source and vendored here. It provides
software decoding for audio codecs that many devices have no hardware MediaCodec
for, notably **AC-3 / E-AC-3 / DTS / TrueHD / MP2** carried by US ATSC broadcast
channels. Without it, ExoPlayer reports "no audio tracks" and plays silent on
boxes like the Chromecast with Google TV. It is wired in as the fallback audio
renderer (`EXTENSION_RENDERER_MODE_ON`) in `core/playback/AerioRenderers.kt`.

### How it was built (reproducible)

- media3 checkout: tag `1.4.1` (matches the `media3` version in
  `gradle/libs.versions.toml`)
- ffmpeg: `release/6.0` (n6.0.x) cloned into
  `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`
- NDK `25.1.8937393`, cmake `3.31.x`, nasm (for the x86_64 build)
- module `minSdkVersion` raised to 21 so every ABI links at >= 21 (the app's
  own minSdk is 26)
- `libraries/decoder_ffmpeg/src/main/jni/CMakeLists.txt` patched with
  `target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")` so the
  .so links with 16 KB ELF page alignment (Android 15+/16 requirement; NDK r26
  and below default to 4 KB and Android 16 then runs the app in "page size
  compatible mode" with a launch warning). The ffmpeg static libs need no
  rebuild for this; alignment is fixed at the final shared-object link. Verify
  with `llvm-readelf -l libffmpegJNI.so`: every LOAD segment must show align
  `0x4000`.

```
# in the media3 checkout, from libraries/decoder_ffmpeg/src/main/jni
./build_ffmpeg.sh "<repo>/libraries/decoder_ffmpeg/src/main" \
  "$ANDROID_SDK/ndk/25.1.8937393" darwin-x86_64 21 \
  ac3 eac3 dca mlp truehd mp2 aac mp3 flac alac
# then, from the media3 checkout root
./gradlew :lib-decoder-ffmpeg:assembleRelease
# output: libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar
```

Rebuild and replace this file when bumping the `media3` version so the extension
stays binary-compatible with the maven media3 artifacts.
