# Diagnostics Screen

Layar teknis untuk membandingkan konfigurasi virtual instance vs info device nyata.

## Tampilan

- **Real Device Info** — data device fisik aktual (model, Android version, fingerprint)
- **Instance Config** — konfigurasi yang dipakai instance (spoofed values)
- **Visualisasi grafik** — representasi visual perbandingan nilai real vs spoofed

## Informasi yang Ditampilkan

| Field | Real Device | Instance (Spoofed) |
|---|---|---|
| Model | Device asli | Nilai yang di-spoof |
| Android ID | ID asli | ID yang di-generate |
| Build Fingerprint | Fingerprint asli | Fingerprint palsu |
| IMEI | IMEI asli | IMEI yang di-spoof |

## Tujuan

Digunakan untuk memverifikasi bahwa anti-detection dan fingerprint spoofing berjalan benar — nilai yang dilihat app target seharusnya berbeda dari nilai device nyata.

## Navigasi

- Back → `InstanceDetailScreen`
