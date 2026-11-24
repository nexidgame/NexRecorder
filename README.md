# Nex Recorder

Perekam layar minimalis untuk Android 11 (API 30) sampai Android 16 (API 36).

## Cara build
1. Buka folder ini di Android Studio (Ladybug atau lebih baru, JDK 17), tunggu Gradle sync.
2. Hubungkan HP, lalu Run. Atau Build > Generate Signed App Bundle / APK untuk rilis.
3. Setelah sync pertama, Android Studio membuat file gradle-wrapper.jar sendiri.
4. Tanpa Android Studio: unggah proyek ini ke GitHub, buka tab Actions, lalu unduh
   NexRecorder-APK dari bagian Artifacts setelah build selesai.

## Kenapa ringan
- MediaRecorder + encoder hardware (H.264 atau H.265): frame dari layar langsung ke encoder, tanpa proses di CPU.
- Tidak ada kamera atau penggambaran sentuhan. Tombol melayang bersifat opsional dan hilang saat merekam.
- UI hanya aktif saat aplikasi dibuka; saat merekam, aplikasi langsung ke latar belakang.

## Fitur
Resolusi (480p/720p/1080p/asli), 30/60/90/120 fps, bitrate 4-80 Mbps,
codec H.264 atau H.265 (otomatis kembali ke H.264 jika HP tidak mendukung H.265),
audio opsional dengan pilihan:
- Sumber suara: MIC, Raw (tanpa terproses), Performa Suara, Suara Internal, atau Internal + MIC
  (suara game dan mikrofon dicampur, dengan pengaturan volume masing-masing 0-200%)
  dan filter suara mikrofon: Mati, Ringan (peredam noise + high-pass), atau Kuat (ditambah noise gate,
  peredam gema, dan pembatas suara pecah)
  dan mode streamer (volume game otomatis turun saat Anda bicara)
- Pengubah suara pada mikrofon: Original, Bayi, Wanita, Paman, Robot, Orang Tua, Monster
  (diproses langsung di aplikasi tanpa library tambahan)
- Tingkat sampel: 44100 Hz atau 48000 Hz
- Saluran: mono atau stereo
- Enkoder: AAC, AAC_ELD, atau HE-AAC
- Bit rata-rata audio: 64, 128, 256, atau 320 Kbps (otomatis diturunkan jika melebihi batas encoder)
Jika kombinasi audio tidak didukung HP, aplikasi otomatis memakai AAC (dan MIC untuk sumber mikrofon).
Video tersimpan di Movies/NexRecorder lewat MediaStore (tanpa izin penyimpanan).

## Tampilan
Empat tab di bagian bawah:
- **Rekam**: tombol rekam, timer, ringkasan pengaturan, dan saklar tombol melayang.
- **Video**: resolusi, frame rate, bitrate, dan codec.
- **Audio**: sumber dan volume, efek suara (pengubah suara dan filter), serta kualitas audio.
- **Pengaturan**: tentang aplikasi dan pengembalian pengaturan awal.

## Tombol rekam melayang
Aktifkan saklar "Tombol rekam melayang" di aplikasi (butuh izin "Tampil di atas aplikasi lain").
Tombol merah muncul di tengah layar dan bisa digeser. Saat digeser, ikon tempat sampah muncul di
bagian bawah layar; lepaskan tombol di atasnya untuk menghapus (mematikan) tombol melayang, lalu
aktifkan lagi lewat saklar di tab Rekam. Ketuk untuk mulai merekam; tombol langsung
hilang sehingga tidak ikut terekam, dan muncul lagi setelah rekaman selesai disimpan.
Berhenti lewat tombol Berhenti di notifikasi. Android tetap menampilkan dialog izin rekam layar
setiap kali rekaman dimulai.

## Preset video
Di tab Video, satu ketukan mengatur resolusi, frame rate, bitrate, dan codec:
- Hemat: 720p, 30 fps, 4 Mbps, H.264.
- Standar: 720p, 30 fps, 8 Mbps, H.264 (pengaturan awal).
- Halus: 720p, 60 fps, 12 Mbps, H.264.
- Kualitas: 1080p, 60 fps, 16 Mbps, H.264.
Jika pengaturan diubah manual, tampil sebagai Kustom.

## Watermark
Di tab Rekam, pilih gambar dari penyimpanan HP lalu nyalakan saklar Watermark (butuh izin "Tampil di atas
aplikasi lain"). Gambar tampil di tengah atas layar dan ikut terekam.
- Geser untuk memindahkan (bisa sampai ke tengah layar). Posisi disimpan terpisah untuk tiap arah layar
  (portrait, portrait terbalik, landscape, landscape terbalik).
- Saklar "Geser vertikal saja" (muncul setelah gambar dipilih): Mati = watermark bebas digeser ke mana saja.
  Aktif = watermark selalu di tengah layar (kiri-kanan), hanya bisa digeser naik-turun dari atas sampai tengah
  layar (posisi awalnya di tengah-tengah layar), dan cubitan hanya mengubah ukuran tanpa menggeser posisi tengahnya (tidak bisa diputar). Posisi tiap mode disimpan terpisah.
- Slider "Transparansi" mengatur kepekatan watermark (10% sampai 100%), terpisah dari kunci posisi.
- Cubit dua jari untuk membesarkan atau mengecilkan, putar dua jari untuk memutar 360 derajat.
- Ketuk gambar untuk menampilkan tombol kunci. Setelah dikunci, posisi, ukuran, dan putaran tidak bisa
  diubah dan tampilannya tetap jelas (tidak jadi transparan). Hanya area gambar yang menahan sentuhan ke game. Buka kunci lewat saklar di tab Rekam atau notifikasi.
- Sebelum dikunci, seret gambar ke ikon tempat sampah yang muncul di bawah layar untuk membuangnya.

## Tab Media (pemutar dan penyunting)
Tab "Media" menampilkan daftar hasil rekaman (Movies/NexRecorder). Ketuk satu video untuk membuka pemutar:
tombol Edit, Sebelumnya, Putar/Jeda, Selanjutnya. Tombol Edit menampilkan atau menyembunyikan menu suntingan.
- Menu atas: Trim, Cut, Mute, Kompres & Resolusi, Crop & Rasio.
- Fitur tambahan: Sensor Area, Teks/Subtitle, Kecepatan (0.5x/1x/1.5x/2x), Putar & Flip, Musik Latar,
  Sulih Suara, Coretan, Video ke GIF, Ekstrak Audio (M4A), Bingkai Kanvas, Watermark, Smart Trim.
- Semua suntingan dikumpulkan lalu diterapkan sekaligus lewat "Simpan hasil" menjadi video baru
  (NexEdit_*.mp4); video asli tidak diubah. GIF disimpan di Pictures/NexRecorder, audio di Music/NexRecorder.
- Memakai Media3 (ExoPlayer + Transformer). Batasan: sensor area berupa kotak penutup (bukan blur halus),
  latar bingkai kanvas hitam polos, ekstrak audio hanya M4A, pratinjau belum menampilkan crop/putar.

## Kamera (facecam)
Nyalakan saklar "Kamera (facecam)" di tab Rekam (butuh izin kamera dan "Tampil di atas aplikasi lain").
Kamera depan tampil di kiri atas layar dan ikut terekam. Arah gambar mengikuti arah HP. Geser untuk memindahkan. Ketuk kamera untuk
menampilkan tombol: pengaturan (bentuk segi empat atau lingkaran, efek Bright/Warm/Dark), tutup,
balik kamera depan/belakang, dan ubah ukuran (seret).

## Trigger mode
Di tab Pengaturan, pilih cara memunculkan tombol hijau saat merekam (aktif jika tombol rekam melayang
dinyalakan). Geser dari tepi layar, lalu ketuk tombol hijau untuk menyimpan rekaman. Tombol hijau hilang
sendiri setelah beberapa detik jika tidak diketuk, dan tombol rekam muncul lagi setelah rekaman tersimpan.
- Klik Ikon: hanya tombol rekam melayang (berhenti lewat notifikasi).
- Geser dari Sisi: geser dari tepi layar (kanan, kiri, atau kedua sisi).
- Geser Setengah Atas: geser dari tepi layar bagian atas (setengah tinggi layar).
- Geser Dapat Diatur: sisi kanan, kiri, atas, bawah, atau kedua sisi. Area cakupan diatur lewat
  editor layar penuh: seret dua pegangan di tepi layar, lalu Simpan.
- Sensitivitas geser: Paling Tidak Sensitif, Kurang Sensitif, Bawaan, atau Lebih Sensitif.

## Catatan Android 14-16
Foreground service bertipe mediaProjection dimulai sebelum getMediaProjection(),
callback didaftarkan sebelum createVirtualDisplay(), dan UI edge-to-edge.
Suara Internal memakai AudioPlaybackCapture: direkam terpisah lalu digabung dengan video
saat berhenti, jadi ada jeda singkat untuk menyimpan. Aplikasi/game yang melarang
perekaman audio akan menghasilkan suara kosong.
