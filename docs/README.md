# Renjana — Dokumentasi Menu

Panduan per layar (screen) untuk aplikasi Renjana Virtual Container.

## Daftar Menu

| File | Layar | Deskripsi Singkat |
|---|---|---|
| [HOME.md](HOME.md) | Home Screen | Daftar semua instance virtual |
| [APPS.md](APPS.md) | Apps Screen | Pilih app untuk di-clone |
| [CREATE_INSTANCE.md](CREATE_INSTANCE.md) | Create Instance | Konfigurasi & buat instance baru |
| [INSTANCE_DETAIL.md](INSTANCE_DETAIL.md) | Instance Detail | Detail, kontrol, dan config instance |
| [DIAGNOSTICS.md](DIAGNOSTICS.md) | Diagnostics | Bandingkan nilai real vs spoofed |
| [ACCOUNTS.md](ACCOUNTS.md) | Accounts | Manajemen akun Google |
| [SETTINGS.md](SETTINGS.md) | Settings | Pengaturan tampilan & data global |
| [ERROR_LOG.md](ERROR_LOG.md) | Error Log | Log error & crash untuk debugging |

## Alur Navigasi Utama

```
HomeScreen
├── + (FAB) → AppsScreen → CreateInstanceScreen → HomeScreen
├── Tap card → InstanceDetailScreen
│   └── Bug Report → DiagnosticsScreen
└── Bottom Bar
    ├── Accounts → AccountsScreen
    └── Settings → SettingsScreen
        └── Error Logs → ErrorLogScreen
```
