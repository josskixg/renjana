<div align="center">

# 🫙 Renjana

**Android virtual container — run multiple instances of any app, each with its own Google account.**

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Min SDK](https://img.shields.io/badge/Min%20SDK-29-orange?style=flat)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34-blue?style=flat)
![License](https://img.shields.io/badge/License-Apache%202.0-green?style=flat)
![Build](https://img.shields.io/badge/Build-Manual%20CI-lightgrey?style=flat)

</div>

---

## What is Renjana?

Renjana is an Android container app that lets you clone any installed app and run it as an isolated virtual instance. Each instance gets its own storage, settings, and Google account — no root required.

---

## Features

| Feature | Description |
|---|---|
| 🗂️ Multi-Instance | Clone any app and run unlimited parallel instances |
| 🔑 Per-Instance Accounts | Assign a different Google account to each instance |
| 🔒 Isolated Storage | Each instance has its own data, cache, and shared prefs |
| 🌱 No Root Required | Works on stock devices via Pine hook framework |

---

## Roadmap

The following features are planned for v0.1.0:

| Feature | Status | Target |
|---|---|---|
| 🛡️ Anti-Detection | In Progress | v0.1.0 |
| 📱 Fingerprint Spoofing | In Progress | v0.1.0 |
| ✍️ Signature Spoof | In Progress | v0.1.0 |
| 🔌 Xposed Support | Planned | TBA |

---

## Architecture

```
+-------------------+------------------------------------------+
|                   Renjana Container App                       |
+-------------------+------------------------------------------+
|  UI Layer         |  Jetpack Compose + Material 3             |
+-------------------+------------------------------------------+
|  Core Layer       |  Instance Management + App Loading        |
+-------------------+------------------------------------------+
|  Virtualization   |  Runtime + Filesystem + GMS Proxy         |
+-------------------+------------------------------------------+
|  Anti-Detection   |  Signature Spoof + Environment Cloak      |
+-------------------+------------------------------------------+
|  System           |  Xposed (root) / Pine (non-root)          |
+-------------------+------------------------------------------+
```

---

## Requirements

- Android 10+ (API 29)
- Kotlin 1.9.20
- Android Gradle Plugin 8.1.0
- JDK 17

---

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore)
./gradlew assembleRelease
```

CI builds are manual-only. Trigger from the **Actions** tab on GitHub.

---

## Quick Start

1. Install the APK on your device
2. Grant required permissions
3. Add a Google account via the **Accounts** tab
4. Tap **+** on the Home screen → select an app
5. Configure instance options → tap **Create**
6. Tap **▶ Play** to launch the instance

---

## Documentation

Per-screen user guides are in [`docs/`](docs/README.md).

| Screen | Guide |
|---|---|
| Home | [docs/HOME.md](docs/HOME.md) |
| Apps | [docs/APPS.md](docs/APPS.md) |
| Create Instance | [docs/CREATE_INSTANCE.md](docs/CREATE_INSTANCE.md) |
| Instance Detail | [docs/INSTANCE_DETAIL.md](docs/INSTANCE_DETAIL.md) |
| Diagnostics | [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md) |
| Accounts | [docs/ACCOUNTS.md](docs/ACCOUNTS.md) |
| Settings | [docs/SETTINGS.md](docs/SETTINGS.md) |
| Error Log | [docs/ERROR_LOG.md](docs/ERROR_LOG.md) |

---

## Tech Stack

| Layer | Library | Version |
|---|---|---|
| UI | Jetpack Compose BOM | 2023.10.01 |
| Database | Room | 2.6.0 |
| Async | Coroutines | 1.7.3 |
| Hook (non-root) | Pine | latest |
| Hook (root) | Xposed | latest |
| Navigation | Navigation Compose | 2.7.4 |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## Disclaimer

For educational and research purposes only. Use responsibly and in compliance with all applicable laws and terms of service.
