# EgoStereoIMU

**An egocentric stereo vision and IMU data recorder for Android.**  
面向第一视角（Egocentric）数据采集的双目视觉与惯性传感器记录工具。

EgoStereoIMU 旨在使用 Android 手机通过 USB OTG 连接双目 UVC 摄像头，在设备端进行实时预览、视频与 IMU 数据采集，并将多模态数据及时间戳保存为可供后续处理的 **Episode**。

> **Project status — Work in progress.** 当前主要适配 **2UQ2（USB VID:PID `1bcf:0b15`）**。USB 连接、双目预览和基础采集已进行真机验证；原始 MJPEG 30 FPS 录制路径已加入代码，仍需在目标手机上验证性能和稳定性。

## 功能概览 / Features

| 功能 | 当前状态 |
| --- | --- |
| Android USB Host 设备识别与授权 | 已完成真机验证 |
| 双目 UVC 实时预览 | 已完成真机验证 |
| 2UQ2 XU IMU 读取与六轴数据解析 | 已在真机 Episode 中验证，仍需连续性测试 |
| 图像、IMU 与时间戳的 Episode 保存 | 基础流程已实现 |
| 解码前原始 MJPEG 回调与异步写入 | 已加入 V0.2.3，待目标手机实测 |
| 原始 MJPEG **稳定 30 FPS** 录制 | **目标，尚未验收** |
| 相机—IMU 硬件级时间同步 | 尚未实现 |

## 系统架构 / Architecture

```text
2UQ2 Stereo UVC Camera + IMU
               │ USB OTG
               ▼
       Android USB Host
               │
      UVCAndroid / libuvc
               │
        ┌──────┴─────────┐
        │                │
   MJPEG video        XU IMU data
        │                │
   ┌────┴─────┐       IMU parser
   │          │           │
 Preview   Raw frame      │
 renderer  recorder       │
   │          │           │
   └──────────┴───────────┘
               │
         Episode writer
               │
       Data validation / export
```

实时预览与数据记录采用不同的数据路径：预览可以解码绘制，正式采集目标是直接保存摄像头输出的 MJPEG 字节，避免重复 JPEG 编码。

## 硬件与环境 / Requirements

**当前测试设备**

- USB 双目相机：2UQ2，VID:PID `1bcf:0b15`
- 主机：支持 USB Host / OTG 的 Android 设备（目前以荣耀 Android 12 手机为测试对象）
- USB-C OTG 数据连接；若出现供电或带宽问题，建议测试带供电的 USB Hub

**构建环境（当前工程配置）**

- JDK 17
- Android SDK Platform 35
- Android NDK r27d (`27.3.13750724`)
- Gradle 8.7、Android Gradle Plugin 8.6.1、Kotlin 1.9.24
- `minSdk=31`，`compileSdk=35`，`targetSdk=35`

## 快速开始 / Quick Start

克隆仓库并配置本机 Android SDK 路径：

```bash
git clone https://github.com/BAAI-Ego/EgoStereoIMU.git
cd EgoStereoIMU
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
```

构建并运行单元测试：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 位于：`app/build/outputs/apk/debug/app-debug.apk`。

如果手机已通过 ADB 连接，可运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在手机上连接 2UQ2，允许 CAMERA 和 USB 设备访问权限，然后打开相机预览。使用界面中的采集按钮开始和停止 Episode 录制。可用的采集模式取决于当前构建版本。

> Android SDK、NDK 和 USB 访问权限可能因开发环境及手机型号而异。目前不保证兼容所有 Android 手机或 UVC 设备。

## Episode 数据格式 / Data Format

当前原始 MJPEG 录制路径使用的目录结构：

```text
episode_YYYYMMDD_HHMMSS_xxx/
├── video.mjpg           # 连续原始 MJPEG 帧字节
├── video_index.csv      # 视频帧索引与时间戳
├── imu_packets.bin      # 设备返回的原始 IMU 包
├── imu_packets.csv      # IMU 包级记录和主机时间信息
├── imu_samples.csv      # 解析后的六轴 IMU 样本
├── metadata.json        # 设备信息和采集配置
└── quality.json         # 数据量、错误及连续性统计
```

2UQ2 当前固件协议中，一个 IMU 数据包为 **252 字节**，包含 **14 条 18 字节记录**；解析保留原始加速度、角速度整数值及设备计数器。物理单位转换需要相应的传感器参数或标定。

**时间戳说明：** 视频原生回调记录的主机时间来自 `CLOCK_BOOTTIME`，IMU 记录 USB `GET_CUR` 请求前后的主机时间。它们便于在同一主机时间域分析数据，但**不等于曝光时刻或每条 IMU 样本的硬件时间，也不代表已实现硬件同步**。

在 Linux 上检查 Episode（如仓库中包含相应脚本）：

```bash
python3 scripts/inspect_episode.py /path/to/episode --decode
```

`--decode` 需要安装 Pillow。旧版诊断采集可能输出独立 JPEG 文件，不应与原始 MJPEG 录制格式混淆。

## 当前限制 / Known Limitations

- 目标采集速率为 **3840 × 1080、30 FPS**；设备声明支持 3840 × 1080 MJPEG 60 FPS，不代表手机已经稳定保存 30 FPS。
- 早期版本使用 RGBX → JPEG 重编码并限制保存帧率；正式路径正在迁移到解码前原始 MJPEG 录制。
- 已观察到 IMU 设备计数器存在少量不连续现象，需要继续排查 USB 读取间隔、缓冲及重复包处理。
- 相机与 IMU 的硬件级同步、长时间录制和异常断连恢复仍需完善。

## Roadmap

- [x] USB 识别、授权和双目实时预览
- [x] IMU XU 读取与基础解析验证
- [ ] 目标手机上验证原始 MJPEG 稳定 30 FPS 保存
- [ ] 完成图像/IMU 时间戳质量评估与同步标定
- [ ] 完善长时间采集、断连恢复与数据完整性检查
- [ ] 发布可复用的 Episode 数据规范、示例数据与稳定 APK

## 开源与贡献 / Open Source & Contributing

欢迎针对设备兼容性、UVC 扩展单元、时间同步和数据采集质量提出 Issue 或 Pull Request。

本项目包含或依赖第三方 UVC / USB / 图像处理组件。使用和再分发时，请保留并遵守 `third_party/` 中适用的版权与许可证声明。

**许可证状态：** 项目主仓库许可证尚待维护者确认。正式公开开源前需要添加合适的顶层 `LICENSE` 文件；第三方组件的许可证不自动等于整个项目的许可证。
