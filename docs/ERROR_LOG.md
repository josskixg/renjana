# Error Log Screen

Layar untuk melihat dan mengelola log error teknis dari Renjana.

---

## Tampilan

| Elemen | Deskripsi |
|---|---|
| Daftar log | Level error, timestamp, dan preview pesan |
| Detail log | Full stack trace / pesan error lengkap saat item di-tap |

---

## Aksi

| Aksi | Cara |
|---|---|
| Lihat detail error | Tap pada item log |
| Copy log | Tap **Copy** di detail → salin ke clipboard |
| Share log | Tap **Share** di detail → kirim via share sheet |
| Hapus satu log | Tap **Delete** pada item |
| Hapus semua log | Tap **Clear All** → konfirmasi dialog |

---

## Level Error

| Level | Keterangan |
|---|---|
| ERROR | Error kritis yang mengganggu fungsi |
| WARN | Peringatan yang perlu diperhatikan |
| INFO | Informasi umum (hook loaded, instance started, dll.) |

---

## Tujuan

Digunakan untuk debugging saat instance bermasalah. Log bisa di-share ke developer untuk laporan bug.

---

## Navigasi

- Back → `SettingsScreen`
