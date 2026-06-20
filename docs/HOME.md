# Home Screen

Layar utama Renjana. Menampilkan daftar semua instance virtual yang telah dibuat.

## Tampilan

- **List / Grid toggle** — ubah tampilan antara list vertikal dan grid 2 kolom
- **StatHeader** — ringkasan jumlah instance aktif vs total
- **Instance card** — setiap card menampilkan: ikon app, nama instance, status (Running / Paused / Idle), dan tanggal dibuat

## Aksi

| Aksi | Cara |
|---|---|
| Buat instance baru | Tombol **+** di pojok kanan bawah → pilih app dari AppsScreen |
| Jalankan instance | Tap tombol **▶ Play** pada card |
| Hentikan instance | Tap tombol **⏹ Stop** pada card yang sedang berjalan |
| Buka detail instance | Tap pada card instance |
| Edit nama instance | Long-press card → pilih Edit |
| Hapus instance | Long-press card → pilih Delete |

## Status Instance

- 🟢 **Running** — instance sedang aktif berjalan
- 🟡 **Paused** — instance dijeda
- ⚫ **Idle** — instance tidak aktif

## Navigasi

- Tap card → `InstanceDetailScreen`
- Tombol + → `AppsScreen` (pilih app untuk di-clone)
