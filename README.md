# Renjana - Android Container App

Renjana is an Android container app that enables users to run multiple instances of the same app with different Google accounts simultaneously.

## Features

- **Multi-Instance Support**: Clone any app and run multiple instances
- **Per-Instance Google Accounts**: Each instance can use a different Google account
- **Root & Non-Root Support**: Works on both rooted and non-rooted devices
- **Anti-Detection**: Built-in mechanisms to prevent detection by target apps
- **Isolated Storage**: Each instance has its own isolated data, cache, and preferences

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Renjana Container App                     │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Jetpack Compose)                                  │
├─────────────────────────────────────────────────────────────┤
│  Core Layer (Instance Management, App Loading)               │
├─────────────────────────────────────────────────────────────┤
│  Virtualization Layer (Runtime, Filesystem, GMS)             │
├─────────────────────────────────────────────────────────────┤
│  Anti-Detection Layer (Signature Spoof, Environment Cloak)   │
├─────────────────────────────────────────────────────────────┤
│  System Integration (Xposed for root, Pine for non-root)     │
└─────────────────────────────────────────────────────────────┘
```

## Requirements

- Android 10+ (API 29+)
- Kotlin 1.9.20
- Android Gradle Plugin 8.1.0

## Building

```bash
./gradlew assembleDebug
```

## Installation

1. Install the APK on your device
2. Grant required permissions
3. Add Google accounts
4. Clone apps and assign accounts
5. Launch instances

## Technical Details

See [ROADMAP.md](ROADMAP.md) for detailed architecture and implementation plan.

## License

TBD

## Disclaimer

This project is for educational and research purposes only. Use responsibly and in compliance with all applicable laws and terms of service.
