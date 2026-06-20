# Renjana - Android Container App
## Multi-Instance Virtual Environment with Anti-Detection

**Version**: 1.0.0 (MVP)  
**Status**: Planning Phase  
**Created**: 2026-06-15

---

## Executive Summary

Renjana is an Android container app that enables users to:
- Run multiple instances of the same APK simultaneously
- Each instance operates with a different Google account
- Works on both rooted and non-rooted devices
- Includes anti-detection mechanisms to prevent detection by target apps

**Target Use Case**: Apps like Rigi TV, social media, games that require multiple accounts

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Renjana Container App                     │
├─────────────────────────────────────────────────────────────┤
│  UI Layer                                                    │
│  - Instance Manager                                          │
│  - App Picker                                                │
│  - Account Manager                                           │
│  - Settings                                                  │
├─────────────────────────────────────────────────────────────┤
│  Core Layer                                                  │
│  - InstanceManager                                           │
│  - AppLoader                                                 │
│  - AccountManager                                            │
│  - HookManager                                               │
├─────────────────────────────────────────────────────────────┤
│  Virtualization Layer                                        │
│  - VirtualRuntime (classloader, lifecycle)                   │
│  - VirtualFileSystem (isolated storage)                      │
│  - VirtualGMS (Google services)                              │
│  - VirtualPackageManager (signature spoof)                   │
├─────────────────────────────────────────────────────────────┤
│  Anti-Detection Layer                                        │
│  - Signature Spoofing                                        │
│  - Environment Cloaking                                      │
│  - Detection Evasion                                         │
├─────────────────────────────────────────────────────────────┤
│  System Integration                                          │
│  - Root Mode: Xposed/LSPosed hooks                          │
│  - Non-Root Mode: User-space virtualization                  │
└─────────────────────────────────────────────────────────────┘
```

---

## Module Breakdown

### Module 1: Container Runtime
**Purpose**: Load and execute APK without system installation

**Components**:
- `APKParser`: Extract manifest, DEX files, resources
- `VirtualClassLoader`: Load DEX in isolated classloader
- `ActivityLifecycleManager`: Hook Activity lifecycle methods
- `ResourceInjector`: Inject app resources into container

**Key Challenges**:
- DEX loading in ART runtime
- Activity launch without manifest registration
- Resource path remapping

**Tech Stack**:
- Java reflection for classloader manipulation
- Dalvik/ART hooking (Xposed for root, Pine/Whale for non-root)
- Resource path remapping

---

### Module 2: Virtual Environment
**Purpose**: Isolated environment per instance

**Components**:
- `VirtualFileSystem`: Per-instance data directory
- `VirtualSharedPreferences`: Isolated preferences
- `VirtualDatabase`: SQLite isolation
- `VirtualContext`: Override Context methods

**Directory Structure**:
```
/data/data/com.renjana.container/
├── instances/
│   ├── instance_001/
│   │   ├── data/
│   │   ├── cache/
│   │   ├── databases/
│   │   └── shared_prefs/
│   ├── instance_002/
│   │   └── ...
│   └── instance_003/
│       └── ...
└── apps/
    ├── com.target.app/
    │   ├── base.apk
    │   └── lib/
    └── ...
```

**Key Challenges**:
- Redirect all file I/O to virtual paths
- Isolate network requests per instance
- Manage inter-instance communication

---

### Module 3: GMS Virtualization (Google Mobile Services)
**Purpose**: Multi-account Google support per instance

**Components**:
- `GoogleAccountManager`: Per-instance account storage
- `GMSServiceProxy`: Intercept GMS API calls
- `FirebaseBridge`: Virtualize Firebase Auth

**Key APIs to Hook**:
```kotlin
GoogleSignInClient.silentSignIn()
FirebaseAuth.getInstance()
AccountManager.getAccounts()
GoogleApiAvailability.isGooglePlayServicesAvailable()
```

**Account Flow**:
1. User adds Google account in Renjana UI
2. Renjana stores account tokens (OAuth, ID tokens)
3. Guest app calls Google Sign-In
4. Hook intercepts and returns virtualized account
5. Each instance sees only its assigned account

**Key Challenges**:
- GMS is proprietary, requires reverse engineering
- Token refresh and expiration handling
- Firebase instance isolation

---

### Module 4: Hook Framework
**Purpose**: Intercept system calls & app behavior

**Root Mode (Xposed/LSPosed)**:
```kotlin
class RenjanaHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook ActivityThread
        XposedHelpers.findAndHookMethod(
            ActivityThread::class.java,
            "handleLaunchActivity",
            // ...
        )
        
        // Hook PackageManager
        XposedHelpers.findAndHookMethod(
            PackageManager::class.java,
            "getPackageInfo",
            // ...
        )
        
        // Hook GoogleSignIn
        XposedHelpers.findAndHookMethod(
            GoogleSignIn::class.java,
            "getSignedInAccountFromIntent",
            // ...
        )
    }
}
```

**Non-Root Mode (Pine/Whale)**:
- Inline hooking at runtime
- Less stable but works without root
- Requires careful memory management

**Hook Targets**:
1. `ActivityThread.handleLaunchActivity()` - lifecycle control
2. `PackageManager.getPackageInfo()` - signature spoof
3. `GoogleSignIn.getSignedInAccountFromIntent()` - intercept login
4. `Context.getSharedPreferences()` - redirect to virtual path
5. `File` constructors - redirect to virtual filesystem

---

### Module 5: Multi-Instance Manager
**Purpose**: Create, clone, manage instances

**Data Model**:
```kotlin
data class Instance(
    val id: String,              // UUID
    val appName: String,         // Display name
    val packageName: String,     // com.example.app
    val version: String,         // 1.0.0
    val googleAccount: GoogleAccount?, // Per-instance account
    val createdAt: Long,
    val lastUsed: Long,
    val config: InstanceConfig   // Custom settings
)

data class GoogleAccount(
    val email: String,
    val tokenCache: Map<String, Token>, // OAuth tokens
    val profile: UserProfile
)
```

**Operations**:
- `createInstance(app: APK, account: GoogleAccount)`: Create new instance
- `cloneInstance(instanceId: String)`: Duplicate instance with same app
- `launchInstance(instanceId: String)`: Start app in container
- `deleteInstance(instanceId: String)`: Remove instance & data
- `switchAccount(instanceId: String, account: GoogleAccount)`: Change account

---

### Module 6: Anti-Detection Layer
**Purpose**: Hide container from guest app

**Techniques**:

1. **Signature Spoofing**:
   - Return original APK signature to guest app
   - Hook `PackageManager.getPackageInfo()`
   - Prevent detection via signature mismatch

2. **Environment Cloaking**:
   - Hide `/data/data/com.renjana.container/` from guest
   - Fake `Build.SERIAL`, `Build.FINGERPRINT`
   - Randomize device identifiers per instance
   - Mask container app from `PackageManager.getInstalledPackages()`

3. **Detection Evasion**:
   - Block reflection on container classes
   - Obfuscate container code (ProGuard/R8)
   - Detect & block anti-virtualization checks
   - Intercept file existence checks for known container paths

**Common Detection Methods to Counter**:
```kotlin
// Guest app might check:
File("/data/data/com.parallel.space").exists()
PackageManager.getInstalledPackages() contains container
Stack trace contains container class names
System property "ro.product.model" matches container
```

---

### Module 7: UI Layer
**Purpose**: User-friendly instance management

**Screens**:
1. **Home Screen**: Grid of installed instances with quick launch
2. **App Picker**: List installed APKs, import from file
3. **Instance Creator**: Select app, choose Google account, configure settings
4. **Account Manager**: List Google accounts, add new, assign to instances
5. **Settings**: Root/non-root toggle, performance settings, backup/restore

**Tech Stack**:
- Kotlin + Jetpack Compose + Material 3
- MVVM architecture
- Room database for instance/account storage

---

## Implementation Roadmap

### Phase 1: MVP (Weeks 1-3)
**Goal**: Basic container with multi-account support

**Week 1: Core Runtime**
- [ ] Setup Android project (Gradle, Kotlin)
- [ ] APK parser & DEX loader
- [ ] Basic Activity lifecycle hooks
- [ ] Simple app launch in container

**Week 2: Virtual Environment**
- [ ] Virtual file system
- [ ] SharedPreferences isolation
- [ ] Database isolation
- [ ] Context virtualization

**Week 3: GMS + Multi-Instance**
- [ ] Google account manager
- [ ] GMS hook for Google Sign-In
- [ ] Instance manager (create/clone/delete)
- [ ] Basic UI (instance list, launch)

**Deliverable**: Can clone an app, assign different Google accounts, launch separately

---

### Phase 2: Hardening (Weeks 4-6)
**Goal**: Stability & anti-detection

**Week 4: Anti-Detection**
- [ ] Signature spoofing
- [ ] Environment cloaking
- [ ] Detection evasion

**Week 5: Hook Framework**
- [ ] Xposed integration (root mode)
- [ ] Pine/Whale integration (non-root)
- [ ] Hook stability improvements

**Week 6: Polish**
- [ ] Error handling
- [ ] Performance optimization
- [ ] Memory management
- [ ] Crash recovery

---

### Phase 3: Production (Weeks 7-9)
**Goal**: User-ready release

**Week 7: Advanced Features**
- [ ] Backup/restore instances
- [ ] Instance sync across devices
- [ ] Custom hooks per app
- [ ] Plugin system

**Week 8: UI/UX**
- [ ] Complete UI redesign
- [ ] Onboarding flow
- [ ] Documentation
- [ ] FAQ & troubleshooting

**Week 9: Testing & Release**
- [ ] Beta testing program
- [ ] Bug fixes
- [ ] Play Store preparation (or alternative distribution)
- [ ] Marketing materials

---

## Technical Challenges & Solutions

### Challenge 1: DEX Loading in ART
**Problem**: Android Runtime restricts DEX loading from non-standard paths

**Solution**:
```kotlin
// Use InMemoryDexClassLoader (API 26+)
val dexBytes = File(apkPath).readBytes()
val loader = InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), parentClassLoader)

// Fallback: DexClassLoader (copy to writable directory first)
val optimizedDir = context.getDir("dex_opt", Context.MODE_PRIVATE)
val dexLoader = DexClassLoader(dexPath, optimizedDir.absolutePath, null, parent)
```

---

### Challenge 2: Activity Launch Without Manifest
**Problem**: Guest app's Activities aren't in container's manifest

**Solution**:
```kotlin
// Hook ActivityThread to intercept Activity creation
XposedHelpers.findAndHookMethod(
    ActivityThread::class.java,
    "handleLaunchActivity",
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val intent = param.args[0] as Intent
            val componentName = intent.component
            // Redirect to container's Activity wrapper
            intent.component = ComponentName(
                "com.renjana.container",
                "com.renjana.container.WrapperActivity"
            )
        }
    }
)
```

---

### Challenge 3: Google Sign-In Virtualization
**Problem**: Google Play Services checks calling package signature

**Solution**:
```kotlin
// Hook GoogleSignInClient to return virtualized account
XposedHelpers.findAndHookMethod(
    GoogleSignInClient::class.java,
    "silentSignIn",
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val instanceId = getCurrentInstanceId()
            val account = AccountManager.getAccount(instanceId)
            val result = TaskCompletionSource<GoogleSignInAccount>()
            result.setResult(account.toGoogleSignInAccount())
            param.result = result.task
        }
    }
)
```

---

### Challenge 4: Resource Loading
**Problem**: Guest app expects resources in standard paths

**Solution**:
```kotlin
// Create virtual AssetManager
val assetManager = AssetManager::class.java.newInstance()
val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
addAssetPathMethod.invoke(assetManager, guestApkPath)

// Create virtual Resources
val hostResources = context.resources
val virtualResources = Resources(
    assetManager,
    hostResources.displayMetrics,
    hostResources.configuration
)
```

---

## Dependencies

```gradle
dependencies {
    // Core
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.20'
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    
    // UI
    implementation 'androidx.compose.ui:ui:1.5.4'
    implementation 'androidx.compose.material3:material3:1.1.2'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2'
    
    // Hooking (Root)
    compileOnly 'de.robv.android.xposed:api:82'
    
    // Hooking (Non-Root)
    implementation 'top.canyie.pine:pine:0.2.8'
    implementation 'top.canyie.pine:xposed:0.0.8'
    
    // APK Parsing
    implementation 'com.android.tools.build:apksig:8.1.0'
    implementation 'net.dongliu:apk-parser:0.2.5'
    
    // Database
    implementation 'androidx.room:room-runtime:2.6.0'
    implementation 'androidx.room:room-ktx:2.6.0'
    kapt 'androidx.room:room-compiler:2.6.0'
    
    // Utilities
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'io.github.classgraph:classgraph:4.8.165'
}
```

---

## Reference Projects

1. **VirtualXposed** (GPL-3.0)
   - Non-root Xposed implementation
   - Great reference for runtime virtualization
   - https://github.com/android-hacker/VirtualXposed

2. **SandVXposed** (Apache-2.0)
   - Fork of VirtualXposed
   - Better maintained, cleaner code
   - https://github.com/ganyao114/SandVXposed

3. **LSPatch** (GPL-3.0)
   - Modern Xposed implementation
   - Works on Android 12+
   - https://github.com/LSPosed/LSPatch

4. **Parallel Space** (Closed-source)
   - Commercial reference
   - Study UX & feature set

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Google Play Store rejection | High | High | Distribute via GitHub, alternative stores |
| GMS API changes | Medium | High | Modular design, easy to update hooks |
| Detection by target apps | Medium | Medium | Continuous anti-detection updates |
| Stability issues | High | Medium | Extensive testing, crash reporting |
| Legal issues | Low | High | Open-source, no proprietary code |

---

## Success Metrics

**Phase 1 (MVP)**:
- Can launch WhatsApp clone with 2 different accounts
- Can launch Instagram clone with 3 different accounts
- Works on Android 10+ (API 29+)
- < 5% crash rate

**Phase 2 (Hardening)**:
- Bypasses detection in top 20 apps
- < 2% crash rate
- Memory usage < 200MB per instance
- Launch time < 3 seconds

**Phase 3 (Production)**:
- 1000+ beta users
- 4.0+ rating
- < 1% crash rate
- Supports 50+ popular apps

---

## Next Steps

1. **Create project structure** (Gradle, manifests, base classes)
2. **Implement APK parser** (extract DEX, resources, manifest)
3. **Build basic DEX loader** (load & execute guest app code)
4. **Hook Activity lifecycle** (launch guest Activities)
5. **Implement virtual file system** (isolate storage)
6. **Add Google account manager** (per-instance accounts)
7. **Hook Google Sign-In** (return virtualized account)
8. **Build instance manager** (create, clone, launch)
9. **Create basic UI** (instance list, launch button)
10. **Test with real apps** (WhatsApp, Instagram, etc.)

---

**Last Updated**: 2026-06-15  
**Maintainer**: Renjana Team
