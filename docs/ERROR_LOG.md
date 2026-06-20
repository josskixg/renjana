# Error Log Screen

Layar untuk melihat dan mengelola log error teknis dari Renjana.

## Tampilan

- **Daftar log** — setiap item menampilkan level error, timestamp, dan preview pesan
- **Detail log** — tap item untuk melihat full stack trace / pesan error lengkap

## Aksi

| Aksi | Cara |
|---|---|
| Lihat detail error | Tap pada item log |
| Copy log | Tombol **Copy** di detail → salin ke clipboard |
| Share log | Tombol **Share** di detail → kirim via share sheet |
| Hapus satu log | Tombol **Delete** pada item |
| Hapus semua log | Tombol Clear All → dialog konfirmasi |

## Level Error

- **ERROR** — error kritis yang mengganggu fungsi
- **WARN** — peringatan yang perlu diperhatikan
- **INFO** — informasi umum (hook loaded, instance started, dll.)

## Tujuan

Digunakan untuk debugging saat instance bermasalah atau ada fitur yang tidak berjalan sesuai ekspektasi. Log bisa di-share ke developer untuk laporan bug.

## Navigasi

- Back → `SettingsScreen`
