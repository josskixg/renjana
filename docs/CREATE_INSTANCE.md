# Create Instance Screen

Layar konfigurasi sebelum membuat instance virtual baru dari sebuah app.

## Tampilan

- **App info** — ikon, nama app, dan package name (ter-prefill dari AppsScreen)
- **Form opsi** — toggle untuk fitur yang diaktifkan per instance

## Opsi Konfigurasi

| Opsi | Deskripsi |
|---|---|
| **Enable GMS** | Aktifkan Google Mobile Services virtual untuk instance ini |
| **Enable Fingerprint** | Gunakan device fingerprint spoofing (IMEI, Android ID, dll.) |
| **Spoof Signature** | Palsukan signature APK agar lolos pengecekan tanda tangan |
| **Enable Anti-Detection** | Aktifkan semua mekanisme anti-detection (PackageManager hiding, file system hook, stack trace filter) |

## Aksi

| Aksi | Cara |
|---|---|
| Buat instance | Tombol **Create** setelah semua opsi dikonfigurasi |
| Batal | Tombol back / navigasi back |

## Alur

1. Masuk dari AppsScreen → package name & APK path ter-prefill otomatis
2. Atur opsi sesuai kebutuhan
3. Tap Create → instance dibuat → kembali ke HomeScreen

## Navigasi

- Berhasil dibuat → kembali otomatis ke `HomeScreen`
- Back → `AppsScreen`
