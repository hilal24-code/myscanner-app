# MyScanner

Aplikasi Android untuk memindai dokumen menjadi PDF, mirip CamScanner. Dibuat dengan **Kotlin** dan **Jetpack Compose**.

Hasil scan diluruskan otomatis, bayangan dihilangkan, lalu disimpan sebagai PDF A4 yang bisa dibuka langsung di dalam aplikasi.

## Fitur

**Scan**
- Scan lewat Google ML Kit Document Scanner (kamera atau impor dari galeri, sampai 20 halaman sekali scan)
- Tambah halaman ke dokumen yang sudah ada
- Tampilan loading dengan progres ("Memproses halaman 2 dari 3") selama halaman diproses

**Pembersihan otomatis (OpenCV)**
- Deteksi tepi kertas dan koreksi perspektif
- Luruskan teks yang miring (deskew) tanpa pojok putih
- Hilangkan bayangan dan netralkan warna kertas
- Gambar asli setiap halaman disimpan di folder `orig` sebagai cadangan

**Dokumen dan PDF**
- Home: daftar dokumen dengan thumbnail, tanggal, dan jumlah halaman
- Detail dokumen: lihat halaman, tambah halaman, hapus halaman, ganti nama, hapus dokumen
- Simpan PDF ke `Documents/MyScanner` (semua halaman berukuran A4 seragam)
- Menu Files: daftar PDF yang sudah disimpan, pencarian, dan viewer PDF di dalam aplikasi
- Share PDF ke aplikasi lain
- Mode pilih (tap **Pilih** atau tekan lama) untuk menghapus banyak dokumen atau PDF sekaligus, dengan tombol **Pilih semua**

## Teknologi

| Bagian | Yang dipakai |
|---|---|
| Bahasa dan UI | Kotlin, Jetpack Compose, Material 3 |
| Scanner | ML Kit Document Scanner (`play-services-mlkit-document-scanner`) |
| Pengolahan gambar | OpenCV 4.9.0 |
| Pembuatan PDF | `android.graphics.pdf.PdfDocument` |
| Tampilan PDF | `android.graphics.pdf.PdfRenderer` |
| Penyimpanan PDF | MediaStore (`Documents/MyScanner`) |
| Penyimpanan dokumen | Folder pribadi aplikasi (`filesDir/docs`) |

## Struktur kode

```
app/src/main/java/com/gamepadbridge/myscanner/
├── MainActivity.kt        # Navigasi, Home, Detail dokumen, overlay loading
├── PdfScreens.kt          # Daftar PDF (Files) dan viewer PDF
├── DocStore.kt            # Penyimpanan dokumen, ekspor PDF, daftar dan hapus PDF
├── DocumentAligner.kt     # Crop, perspektif, dan deskew
├── DocumentEnhancer.kt    # Hapus bayangan dan putihkan kertas
└── DocumentFilter.kt      # Filter lama (tidak dipakai lagi, boleh dihapus)
```

## Persyaratan

- Android Studio versi terbaru
- HP Android 10 (API 29) atau lebih baru
- **Google Play Services** di perangkat (ML Kit scanner tidak jalan di emulator tanpa Play Store)

## Cara menjalankan

1. Clone repo:
   ```
   git clone https://github.com/hilal24-code/myscanner-app.git
   ```
2. Buka foldernya di Android Studio, tunggu **Gradle Sync** selesai.
3. Colok HP (aktifkan **USB debugging**) atau pakai emulator yang punya Play Store.
4. Klik **Run ▶**.

## Tips agar hasil scan bagus

- Pakai **flash** atau cahaya yang merata, hindari bayangan tangan dan HP jatuh ke kertas.
- Di layar scanner Google, matikan filter **Tingkatkan** (pilih **Asli**) supaya filter buatan aplikasi yang bekerja.
- Pegang HP sejajar dengan kertas.
- Lepas kertas dari plastik pelindung, karena kerutan dan pantulan cahaya tidak bisa diperbaiki oleh software.
- Deskew otomatis hanya mengoreksi kemiringan sampai sekitar 25 derajat.

## Rencana pengembangan

- [ ] Tombol "Asli / Tingkatkan" per dokumen
- [ ] Rotate dan crop manual per halaman
- [ ] Urutkan ulang halaman
- [ ] Zoom di viewer PDF
- [ ] OCR (salin teks, cari isi dokumen, nama otomatis)
- [ ] Kompres PDF dan password PDF
- [ ] Tanda tangan
- [ ] Tempat sampah dan kunci aplikasi

## Catatan

Proyek ini masih dalam pengembangan. Angka-angka pada filter (batas warna, kekuatan blur dan sharpen) mungkin perlu disetel sesuai kondisi foto dan perangkat.

## Lisensi

Belum ditentukan.
