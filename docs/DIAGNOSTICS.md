# Diagnostics Screen

Layar teknis untuk membandingkan konfigurasi virtual instance vs info device nyata.

---

## Tampilan

| Elemen | Deskripsi |
|---|---|
| Real Device Info | Data device fisik aktual |
| Instance Config | Nilai yang dipakai instance (spoofed) |
| Visualisasi | Grafik perbandingan nilai real vs spoofed |

---

## Field yang Dibandingkan

| Field | Real Device | Instance (Spoofed) |
|---|---|---|
| Model | Device asli | Nilai yang di-spoof |
| Android ID | ID asli | ID yang di-generate |
| Build Fingerprint | Fingerprint asli | Fingerprint palsu |
| IMEI | IMEI asli | IMEI virtual |

---

## Tujuan

Verifikasi bahwa anti-detection dan fingerprint spoofing berjalan benar. Nilai yang dilihat app target seharusnya **berbeda** dari nilai device nyata.

---

## Navigasi

- Back → `InstanceDetailScreen`
