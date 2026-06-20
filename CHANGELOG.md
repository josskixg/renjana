# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-06-18

### 🎉 Initial Release

#### ✨ Added

**Core Virtualization**
- VirtualClassLoader with isolated DEX loading per instance
- Activity stub system with 10 pre-registered activity slots
- Intent router for smart intent interception and routing
- Resource manager for per-instance resource isolation
- Wrapper activity for guest app lifecycle management

**Multi-Instance Support**
- Create multiple instances of the same app
- Isolated data directories per instance
- Independent cache and settings for each instance
- Instance cloning functionality

**Google Services Integration**
- Google Sign-In virtualization with account picker
- Firebase authentication bypass
- Play Billing proxy for in-app purchases
- GMS service proxy for Google APIs

**Anti-Detection**
- Play Integrity API bypass
- SafetyNet bypass with attestation spoofing
- Per-instance signature spoofing
- Device fingerprint randomization (IMEI, MAC, Android ID, etc.)
- Frida detection evasion
- Root detection evasion

**Frida Integration**
- FridaManager for gadget lifecycle management
- ScriptInjector for runtime JavaScript injection
- HookManagerActivity for visual hook management
- Support for inline and file-based scripts

**User Interface**
- Modern Jetpack Compose UI with Material 3
- Home screen with instance grid
- Apps screen for APK selection
- Accounts screen for Google account management
- Settings screen for configuration
- Create Instance wizard with multi-step flow

**Database**
- Room database for persistent storage
- Instance entity with configuration support
- Google account entity with token management
- DAO layer with Flow support

**Build System**
- Gradle 8.11 with Kotlin DSL support
- Signed release builds with keystore
- R8 minification for release APK (3.3MB)
- Debug builds with full symbols (50MB)

**Documentation**
- Comprehensive README with usage guide
- Contributing guidelines
- Apache 2.0 license
- Architecture documentation

#### 🔧 Technical Details

- **Package:** `com.fesu.renjana`
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 34 (Android 14)
- **Kotlin:** 1.9.20
- **Compose BOM:** 2023.10.01
- **Room:** 2.6.0
- **Coroutines:** 1.7.3

#### 📦 Dependencies

- AndroidX Core KTX 1.12.0
- AndroidX AppCompat 1.6.1
- Lifecycle Runtime KTX 2.6.2
- Activity Compose 1.8.0
- Navigation Compose 2.7.4
- Room Runtime & KTX 2.6.0
- Gson 2.10.1
- Xposed API 82 (compileOnly)
- Play Services Auth 20.7.0 (compileOnly)

#### 🐛 Known Issues

- Some aggressive anti-tamper apps may still detect virtualization
- Performance overhead for resource-intensive applications
- Limited to Android 10 and above
- Frida gadget requires manual asset placement

---

## Version History

| Version | Release Date | Status |
|---------|--------------|--------|
| 0.1.0   | 2026-06-18   | ✅ Current |

---

## Upcoming Features (Roadmap)

### v0.2.0 (Planned)
- [ ] Plugin system for extensibility
- [ ] Improved performance optimization
- [ ] Extended app compatibility
- [ ] Better error handling and crash recovery
- [ ] Automated testing suite

### v0.3.0 (Planned)
- [ ] Multi-user support
- [ ] Cloud sync for instances
- [ ] Advanced hook editor
- [ ] Performance profiling tools
- [ ] Documentation website

### v1.0.0 (Future)
- [ ] Production-ready stability
- [ ] Comprehensive test coverage
- [ ] Play Store release (if compliant)
- [ ] Enterprise features
- [ ] API for third-party integration

---

## Migration Guide

### From Previous Versions

This is the initial release, so no migration is needed.

### For Developers

If you're integrating Renjana into your workflow:

1. **Install APK** on your device
2. **Grant permissions** (storage, etc.)
3. **Add apps** you want to virtualize
4. **Create instances** as needed

See [README.md](README.md) for detailed usage instructions.

---

## Contributors

### Core Team
- Renjana Development Team

### Special Thanks
- VirtualXposed community for inspiration
- LSPatch developers for modern techniques
- Frida project for dynamic instrumentation
- Android open-source community

---

## Reporting Issues

Found a bug or have a feature request?

- **Bugs:** [Open an Issue](../../issues/new?template=bug_report.md)
- **Features:** [Request a Feature](../../issues/new?template=feature_request.md)
- **Questions:** [Start a Discussion](../../discussions)

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**[⬆ Back to Top](#changelog)**

</div>
