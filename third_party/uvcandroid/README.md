# UVCAndroid local fork

Source: `https://github.com/shiyinghan/UVCAndroid`, tag `1.0.13`, commit
`6018c7f20238832929cf61427427bb7828056260`.

This project includes the `libuvccamera/src` module at that commit. The local
changes add `IRawFrameCallback` and a JNI callback in
`UVCPreview::uvc_preview_frame_callback`, before MJPEG decoding and before the
preview queue can discard a frame. The callback exposes a temporary direct
ByteBuffer, native `CLOCK_BOOTTIME` receive time, UVC sequence, width and
height. The app copies selected bytes synchronously before returning.

The Android module build file is adapted to this project's Gradle version and
builds only `arm64-v8a` for the target Honor phone. Upstream and bundled native
libraries have separate licenses; notices are retained in this directory and
in their source files. No Linux EgoUVC source is included or modified.
