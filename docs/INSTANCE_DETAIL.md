# Instance Detail Screen

Layar detail untuk satu instance virtual. Dibagi menjadi 4 tab.

---

## Tab Overview

| Elemen | Deskripsi |
|---|---|
| Status indicator | Running / Paused / Idle dengan indikator warna |
| App info | Nama app dan package name |
| Tanggal dibuat | Timestamp pembuatan instance |
| Tombol Play / Stop | Jalankan atau hentikan instance |

---

## Tab Config

| Opsi | Nilai |
|---|---|
| GMS | Aktif / Nonaktif |
| Fingerprint Spoofing | Aktif / Nonaktif |
| Signature Spoof | Aktif / Nonaktif |
| Anti-Detection | Aktif / Nonaktif |

---

## Tab Device

| Field | Keterangan |
|---|---|
| Model | Perangkat virtual yang di-spoof |
| Android ID | ID unik yang di-generate |
| IMEI | IMEI virtual instance ini |
| Build Fingerprint | Fingerprint palsu |

Tap **Refresh** untuk generate fingerprint baru.

---

## Tab Danger

| Aksi | Efek |
|---|---|
| Clear Data | Hapus semua data app dalam instance — seperti factory reset app |
| Delete Instance | Hapus instance secara permanen beserta semua datanya |

> ⚠️ Kedua aksi ini **tidak bisa di-undo**. Dialog konfirmasi akan muncul sebelum dieksekusi.

---

## Aksi Umum

| Aksi | Cara |
|---|---|
| Jalankan instance | Tombol **▶ Play** di tab Overview |
| Hentikan instance | Tombol **⏹ Stop** di tab Overview |
| Rename instance | Icon **Edit** di header |
| Lihat diagnostics | Tombol **Bug Report** di toolbar |

---

## Navigasi

- Back → `HomeScreen`
- Bug Report → `DiagnosticsScreen`
