<div align="center">

<!-- Replace with your actual app icon path once finalized -->
<img src="Clover/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" alt="Clover icon" />

# Clovr

**A 4chan imageboard reader for Android**

Fork of [chandevel/Clover](https://github.com/chandevel/Clover)

![Version](https://img.shields.io/badge/version-3.1.4-4a7c4e?style=flat-square)
![Android](https://img.shields.io/badge/Android-minSDK%2023-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-GPLv3-blue?style=flat-square)

</div>

---

## Features

- **Modern video player** — rebuilt on Media3 with VP9 support and hold-to-speed-up playback
- **Yotsuba Green default theme** — ships with the classic green theme out of the box
- **Configurable media speed** — set hold-to-speed-up rates in Settings › Media

## 📦 Installation

### Option 1 — APK

Download the latest release from [here](https://github.com/foooooooooooooooooooooooooootw/Clovr/releases/latest/download/Clovr.apk) or the Releases section.

### Option 2 — Build from Source

Open in Android Studio, or build from the command line:

```bash
./gradlew assembleDebug
```

| | |
|---|---|
| Min SDK | Android 6.0 (API 23) |
| Gradle | 8.13.2 |

Last tested on Android 12. 

## Why This Fork Exists

The original Clover is solid, but newer Android versions and modern codecs exposed limitations.

This fork aims to:

- Keep the experience lightweight
- Fix issues that have been bugging me for years
- Leverage advantages of newer devices while maintaining as much compatibility as possible

## License

GPL-3.0 — based on [Clover by chandevel](https://github.com/chandevel/Clover)
