# Instance Detail Screen

Layar detail untuk satu instance virtual. Dibagi menjadi 4 tab.

## Tab Overview

Informasi umum instance:
- Status instance (Running / Paused / Idle) dengan indikator warna
- Nama app dan package name
- Tanggal dibuat
- Tombol **Play** / **Stop** untuk menjalankan atau menghentikan instance

## Tab Config

Konfigurasi yang diset saat instance dibuat:
- Status GMS (aktif/nonaktif)
- Status Fingerprint spoofing
- Status Signature spoof
- Status Anti-Detection

## Tab Device

Informasi device fingerprint yang digunakan instance ini:
- Model perangkat virtual
- Android ID
- IMEI
- Build fingerprint
- Informasi bisa di-refresh untuk generate fingerprint baru

## Tab Danger

Aksi destruktif yang tidak bisa di-undo:
- **Clear Data** — hapus semua data app dalam instance (seperti factory reset app)
- **Delete Instance** — hapus instance secara permanen beserta semua datanya

## Aksi Umum

| Aksi | Cara |
|---|---|
| Jalankan instance | Tombol **▶ Play** di tab Overview |
| Hentikan instance | Tombol **⏹ Stop** di tab Overview |
| Rename instance | Icon Edit di header |
| Lihat diagnostics | Tombol Bug Report → `DiagnosticsScreen` |
| Hapus instance | Tab Danger → Delete Instance |

## Navigasi

- Back → `HomeScreen`
- Bug Report → `DiagnosticsScreen`
