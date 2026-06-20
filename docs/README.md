# Renjana — Panduan Menu

Dokumentasi per layar untuk aplikasi Renjana Virtual Container.

---

## Daftar Layar

| Layar | File | Deskripsi |
|---|---|---|
| Home | [HOME.md](HOME.md) | Daftar semua instance virtual |
| Apps | [APPS.md](APPS.md) | Pilih app untuk di-clone |
| Create Instance | [CREATE_INSTANCE.md](CREATE_INSTANCE.md) | Konfigurasi & buat instance baru |
| Instance Detail | [INSTANCE_DETAIL.md](INSTANCE_DETAIL.md) | Detail, kontrol, dan config per instance |
| Diagnostics | [DIAGNOSTICS.md](DIAGNOSTICS.md) | Perbandingan nilai real vs spoofed |
| Accounts | [ACCOUNTS.md](ACCOUNTS.md) | Manajemen akun Google |
| Settings | [SETTINGS.md](SETTINGS.md) | Pengaturan tampilan & data global |
| Error Log | [ERROR_LOG.md](ERROR_LOG.md) | Log error & crash untuk debugging |

---

## Alur Navigasi

```
HomeScreen
├── FAB (+) ──────────► AppsScreen ──► CreateInstanceScreen ──► HomeScreen
├── Tap card ─────────► InstanceDetailScreen
│                           └── Bug Report ──► DiagnosticsScreen
└── Bottom Bar
    ├── Accounts ────► AccountsScreen
    └── Settings ────► SettingsScreen
                           └── Error Logs ──► ErrorLogScreen
```
