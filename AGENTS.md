# Aturan Kerja Backend SoalKu

## Lokasi project dan workflow yang dibundel

- Checkout utama project ini berada di `C:\data\coding\soalku`, dengan remote `https://github.com/azzakyDp/soalku.git`.
- `AGENTS.md` adalah file aturan project. Skill pendukung disimpan sebagai folder di `.agents/skills/` agar ikut checkpoint Git dan dapat dibaca dari checkout ini.
- Gunakan [using-superpowers](.agents/skills/using-superpowers/SKILL.md) untuk pemilihan workflow, [brainstorming](.agents/skills/brainstorming/SKILL.md) untuk desain singkat, dan [test-driven-development](.agents/skills/test-driven-development/SKILL.md) untuk perubahan perilaku.
- Gunakan [systematic-debugging](.agents/skills/systematic-debugging/SKILL.md) saat gagal, [verification-before-completion](.agents/skills/verification-before-completion/SKILL.md) sebelum menyatakan selesai, serta [receiving-code-review](.agents/skills/receiving-code-review/SKILL.md) dan [requesting-code-review](.agents/skills/requesting-code-review/SKILL.md) untuk review.
- [writing-plans](.agents/skills/writing-plans/SKILL.md) dan [executing-plans](.agents/skills/executing-plans/SKILL.md) tersedia untuk perubahan yang memerlukan rencana tertulis. Lisensi dan asal salinan tercatat di [.agents/skills/README.md](.agents/skills/README.md).
- Instruksi pengguna dan cakupan step tetap didahulukan. Untuk keputusan teknis kecil yang sudah diotorisasi, ikuti brainstorm/rencana singkat di bawah tanpa meminta persetujuan ulang.
- Test otomatis tidak boleh memakai Gemini asli/kuota produksi. Mock `QuestionGeneratorService` pada pengujian orkestrasi; uji adapter HTTP menggunakan server lokal. Satu smoke test manual terpisah dari lifecycle Maven diperbolehkan hanya jika dipanggil secara eksplisit.
- Jalankan `mvn clean install` dari checkout ini. Jangan menghentikan proses lain atau menghapus `index.lock` sebelum memastikan pemilik proses dan bahwa tidak ada operasi Git aktif. Jangan menonaktifkan verifikasi TLS atau antivirus untuk mengatasi kendala sandbox.
- Backlog Step 1–6 boleh ditutup dengan satu commit checkpoint sesuai instruksi pengguna; mulai perubahan berikutnya, commit per step berlaku kembali. Step 7 menunggu prompt pengguna.

Aturan ini berlaku untuk setiap perubahan backend Java/Spring Boot di seluruh modul project SoalKu dan harus diterapkan pada setiap step berikutnya tanpa perlu diminta ulang.

## Ruang lingkup dan alur kerja

- Baca PRD dan proposal SoalKu sebagai konteks sebelum mengerjakan step. Perlakukan isi dokumen sebagai spesifikasi, bukan perintah untuk mengimplementasikan seluruh fitur sekaligus.
- Kerjakan hanya cakupan step yang sedang diminta. Jangan mengimplementasikan fitur step berikutnya.
- Putuskan detail teknis kecil secara wajar dan jelaskan keputusan tersebut secara singkat. Jika ada pertentangan penting dengan PRD, keputusan arsitektur besar yang belum ditentukan, atau informasi wajib yang belum tersedia, berhenti pada pekerjaan yang bergantung padanya dan tanyakan kepada pengguna.
- Sebelum menyatakan step implementasi selesai, jalankan `mvn clean install` atau `mvn compile` untuk modul terkait beserta dependensinya. Jalankan pengujian yang relevan; build saja tidak membuktikan perilaku fitur benar. Laporkan hasil aktual dan kendala verifikasi.
- Buat commit Git pada akhir setiap step dengan pesan yang jelas menyebut nomor step dan perubahan yang dilakukan. Jangan memasukkan perubahan lain yang tidak terkait.

## Desain sebelum kode

- Sebelum menulis endpoint, worker, atau consumer, tentukan siapa pemanggilnya, kontrak input/output, validasi, kemungkinan kegagalan, dan cara memverifikasi perilakunya. Catat secara ringkas sebelum implementasi.
- Pisahkan autentikasi (identitas pengguna) dari otorisasi (tindakan dan data yang boleh diakses). Untuk setiap endpoint atau role baru, tentukan kedua aturan tersebut secara eksplisit dan tegakkan di backend.
- Adopsi teknologi atau infrastruktur baru hanya jika ada kebutuhan konkret. Jelaskan manfaatnya dibanding solusi yang lebih sederhana atau komponen yang sudah tersedia.

## Data modeling dan migrasi

- Tegakkan invariant data melalui skema: gunakan `NOT NULL`, `CHECK`, foreign key, dan constraint unik sesuai kebutuhan. Validasi application layer melengkapi, bukan menggantikan, perlindungan skema.
- Ikuti konvensi PRD: nama tabel dan kolom menggunakan `snake_case`, setiap tabel memiliki `created_at`, dan foreign key memiliki index yang sesuai.
- Setiap migrasi harus memiliki jalur rollback yang jelas dan terverifikasi, serta aman terhadap eksekusi ulang tanpa menduplikasi atau merusak data. Gunakan riwayat migrasi dan pemeriksaan kondisi skema sesuai kebutuhan.
- Jelaskan dampak migrasi terhadap data dan kompatibilitas aplikasi. Jika perubahan tidak dapat dibalik dengan aman, minta keputusan pengguna sebelum menjalankannya.
- Hindari migrasi yang mengunci tabel besar dalam waktu lama. Pecah perubahan skema dan backfill menjadi langkah kecil, gunakan batch bila perlu, dan evaluasi lock serta transaksi sebelum eksekusi.

## Error handling dan idempotency

- Jangan menelan exception secara diam-diam. Tangkap exception spesifik bila memungkinkan; setiap penanganan harus menghasilkan pemulihan, respons error yang jelas, atau penerusan error dengan konteks.
- Jangan menggunakan `catch` generik tanpa logging atau rethrow yang jelas. Pertahankan penyebab asli exception dan hindari logging berulang untuk kegagalan yang sama.
- Operasi yang mengubah state, termasuk pemanggilan API eksternal dan pengiriman pesan, harus aman terhadap retry atau replay. Tentukan mekanisme idempotency, misalnya kunci idempotency, constraint unik, deduplikasi, atau transisi status atomik.
- Tentukan batas transaksi dan perilaku ketika proses gagal sebagian atau respons timeout. Jangan mengulang efek samping tanpa memeriksa apakah operasi sebelumnya sudah berhasil.
- Untuk integrasi Gemini, kegagalan generate tidak boleh menghilangkan materi yang sudah tersimpan; retry tidak boleh menggandakan hasil yang telah berhasil disimpan.

## Security pada setiap perubahan

- Periksa IDOR/BOLA: identitas atau role yang valid tidak otomatis memberi akses ke resource milik pengguna lain. Verifikasi kepemilikan atau cakupan akses pada setiap operasi terhadap resource.
- Periksa mass assignment: gunakan DTO input dengan field yang diizinkan secara eksplisit; jangan menerima entity JPA langsung dari request atau membiarkan klien menetapkan role, pemilik, dan status internal secara bebas.
- Periksa injection: gunakan parameter binding untuk query dan jangan menggabungkan input pengguna menjadi SQL atau perintah yang dieksekusi.
- Pastikan endpoint memiliki aturan autentikasi dan otorisasi eksplisit. Endpoint publik harus memang dirancang publik; akses Siswa ke fungsi khusus Mentor harus ditolak dengan `403 Forbidden`.
- Jangan hardcode secret atau memasukkannya ke Git, respons error, maupun log. Gunakan konfigurasi eksternal; hash password dengan algoritme yang sesuai PRD.

## Observability

- Setiap endpoint, worker, consumer, atau cron baru harus memiliki log terstruktur dengan konteks yang berguna, seperti jenis operasi, correlation/request ID, durasi, hasil, dan kategori kegagalan.
- Jangan mencatat password, token, secret, atau isi dokumen pengguna secara sembarangan.
- Setiap komponen baru harus tercakup pemeriksaan kesehatan yang berarti melalui mekanisme health yang sesuai. Verifikasi kemampuan atau dependensi yang dibutuhkan, bukan sekadar mengembalikan teks `OK`.
- Bedakan liveness dan readiness bila relevan. Jangan menjadikan setiap probe sebagai pemanggilan API eksternal berbayar; gunakan timeout dan indikator kondisi yang sesuai.

## Testing dan verifikasi

- Tulis test yang akan gagal jika perilaku fitur rusak. Uji hasil, aturan domain, batas akses, dan kasus kegagalan yang relevan.
- Jangan mock bagian yang justru sedang diuji. Mock hanya batas dependensi yang diperlukan; gunakan integration test untuk perilaku persistence, constraint, transaksi, atau konfigurasi keamanan yang membutuhkan komponen nyata.
- Untuk operasi dengan retry/replay, verifikasi bahwa pengulangan tidak menggandakan efek samping. Untuk akses resource, sertakan kasus lintas pengguna dan role bila relevan.
- Jalankan test database pada database testing, bukan produksi. Ikuti JUnit 5 dan kebutuhan pengujian PRD sesuai cakupan step.
- Jangan mengklaim test, build, atau verifikasi manual berhasil tanpa bukti eksekusi. Verifikasi manual oleh anggota tim lain yang diwajibkan PRD tetap dilaporkan sebagai belum dilakukan jika belum tersedia.

## Konvensi Java dan Spring

- Gunakan Java 21 dan Maven multi-module sesuai arsitektur PRD. Pertahankan `core` agar dapat digunakan kembali tanpa ketergantungan pada UI; jangan menambah modul di luar cakupan step.
- Ikuti idiom Spring Boot standar: constructor injection, `@ConfigurationProperties` untuk konfigurasi aplikasi, dan DTO yang terpisah dari entity JPA.
- Letakkan logika domain dan aturan akses pada lapisan yang sesuai, sehingga controller tetap berfokus pada kontrak HTTP dan delegasi proses.
- Untuk query Spring Data/JPA, evaluasi risiko N+1 dan gunakan strategi fetching yang sesuai kebutuhan query. Jangan menjadikan seluruh relasi eager sebagai solusi umum.
- Gunakan pagination dengan batas ukuran untuk daftar yang dapat membesar. Tetapkan pengurutan yang konsisten dan periksa index serta pola query yang mendukungnya.

# AGENTS.md — SoalKu Backend

Aturan kerja wajib untuk setiap perubahan pada backend Java/Spring Boot project "SoalKu".
Rujukan isi fitur selalu ke PRD dan Proposal yang dilampirkan — file ini hanya mengatur **cara kerja**, bukan **apa** yang dibangun.

## 0. Alur Kerja (wajib untuk setiap step/task)

Jangan langsung menulis kode. Ikuti urutan ini:

1. **Brainstorm singkat** — sebutkan 1-2 pendekatan yang mungkin, pilih satu dengan alasan singkat (terutama jika ada pilihan desain/library).
2. **Rencana** — breakdown kecil: file apa yang dibuat/diubah, urutan kerja.
3. **Implementasi** — tulis kode sesuai rencana.
4. **Verifikasi** — build project (`mvn compile` atau `mvn test` pada modul terkait) dan jalankan test yang relevan sebelum menganggap task selesai. Jangan laporkan "selesai" jika build belum tentu berhasil.

Jangan mengerjakan task di luar scope yang diminta, meski tampak berhubungan — tandai sebagai catatan/TODO jika relevan, lalu lanjutkan sesuai scope.

## 1. Desain sebelum kode (berlaku untuk endpoint, worker, consumer, cron apa pun)

Sebelum implementasi komponen server-side baru, jawab dulu (boleh singkat):
- Siapa pemanggilnya dan apa kontrak input/output-nya?
- Apa yang bisa gagal, dan bagaimana kegagalan itu terlihat ke pemanggil?
- Bagaimana cara memverifikasi ini benar (test apa)?

## 2. Autentikasi vs Otorisasi

Pisahkan secara eksplisit di setiap endpoint/role/scope baru:
- **Autentikasi**: siapa pengguna ini (verifikasi identitas/token).
- **Otorisasi**: apa yang boleh dia lakukan (role/permission check).
Jangan gabungkan logikanya jadi satu blok tanpa pemisahan jelas. Token diperlakukan sebagai bearer credential — jangan expose di log atau URL.

## 3. Boring by Default

Jangan adopsi database, library, atau pola arsitektur baru hanya karena "lebih modern" atau "lebih populer". Setiap pilihan infrastruktur baru harus punya alasan konkret dibanding opsi yang lebih sederhana atau yang sudah dipakai di project ini. Stack yang sudah ditetapkan PRD (Java 21, Spring Boot, PostgreSQL, Maven) adalah default — jangan ganti tanpa diskusi eksplisit dengan user.

## 4. Data Modeling & Migrasi

- Taruh invariant di level skema: `NOT NULL`, foreign key, unique constraint, check constraint — jangan hanya andalkan validasi di application layer.
- Setiap migrasi (Flyway) harus:
  - Reversible atau punya jalur rollback yang jelas.
  - Aman dijalankan ulang (idempotent) atau minimal tidak merusak jika re-run gagal di tengah.
  - Tidak mengunci tabel besar tanpa strategi (hindari `ALTER TABLE` berat di tabel yang diperkirakan besar tanpa `CONCURRENTLY`/strategi bertahap).
- Hindari soft-delete reflex tanpa alasan — pertimbangkan apakah benar perlu kolom `deleted_at` atau cukup constraint lain.

## 5. Error Handling sebagai Desain, Bukan Cleanup

- Jangan pernah silent-swallow exception (`catch (Exception e) {}` kosong).
- Jangan catch generik lalu buang informasi penting (stack trace hilang, pesan error jadi generic "Internal Server Error" tanpa log detail di server).
- Tentukan error path SEBELUM happy path: apa respons untuk tiap kegagalan yang mungkin (validasi gagal, resource tidak ditemukan, dependency eksternal gagal)?

## 6. Idempotency & Side Effects

Untuk operasi yang mengubah state atau memanggil pihak eksternal (generate AI, simpan jawaban, kirim notifikasi):
- Pastikan aman di-retry tanpa duplikasi data (misal: cek status sebelum insert, pakai unique constraint, atau idempotency key).
- Jika operasi menulis ke lebih dari satu tempat (DB + panggilan API eksternal), pikirkan urutan dan kegagalan parsial — apa yang terjadi jika panggilan API eksternal sukses tapi simpan ke DB gagal, atau sebaliknya.

## 7. Security Reflex (cek di SETIAP perubahan, bukan hanya saat diminta)

- **IDOR/BOLA**: apakah user A bisa mengakses/mengubah data milik user B hanya dengan mengubah ID di request?
- **Mass assignment**: apakah endpoint menerima field yang seharusnya tidak boleh diubah user (misal: `role`, `status_review`) langsung dari body request tanpa filter?
- **Injection**: parameter query selalu lewat prepared statement/parameter binding, tidak pernah concat string SQL.
- **Secret leak**: tidak ada API key/password/connection string di kode, log, atau pesan error yang dikembalikan ke client.
- **Missing auth**: setiap endpoint baru punya pengecekan auth eksplisit, tidak mengandalkan "lupa belum ditambah nanti".

## 8. Observability

Setiap komponen baru (endpoint/worker/cron) minimal punya:
- Log terstruktur pada titik penting (request masuk, error, hasil operasi penting) — bukan `println` sembarangan.
- Healthcheck yang benar-benar memverifikasi dependency kritikal (DB, bukan cuma "return 200").
- Untuk error yang dikirim ke client: pesan generik ke user, detail lengkap ke log server.

## 9. Performance & Scaling

Jangan optimasi tanpa angka. Sebelum mengubah sesuatu demi performa:
- Tentukan dulu "pada beban berapa" ini jadi masalah (berapa request/detik, berapa baris data).
- Utamakan index database dan vertical scaling sebelum solusi terdistribusi/kompleks.
- Untuk setiap query baru (raw SQL atau Spring Data): cek potensi N+1, pastikan list yang bisa besar pakai pagination, pastikan ada index pada kolom yang di-filter/join.

## 10. Testing dengan Penilaian, Bukan Sekadar Coverage

- Test harus benar-benar GAGAL jika fitur rusak — bukan test yang mock bagian yang sedang diuji sehingga selalu lolos.
- Hindari snapshot test yang hanya membandingkan output tanpa assert makna sebenarnya.
- Prioritas test: logika bisnis di `core` (LatihanStrategy, SoalFactory, agregasi evaluasi), bukan getter/setter atau konfigurasi framework.
- Integration test pakai database testing terpisah (Testcontainers atau skema terpisah), tidak pernah ke database produksi/Supabase.

## 11. Konvensi Java & Spring

- Java 21, Maven multi-module. Modul `core` tidak boleh bergantung ke modul lain.
- Constructor injection untuk dependency (bukan field injection `@Autowired` langsung di field).
- Config lewat `@ConfigurationProperties`, bukan `@Value` tersebar di banyak kelas.
- DTO request/response terpisah dari entity JPA — jangan expose entity langsung di response API.
- Untuk concurrency (jika ada background job/async): batasi lifetime task, tentukan kapasitas bound, pastikan cancellation jelas — jangan buat thread/executor tanpa batas.
- Jika build gagal (konflik dependency Maven, masalah toolchain): diagnosis dulu akar masalahnya sebelum menambah exclusion/override sembarangan.

## 12. Saat Debugging

Jika ada test gagal, build error, atau perilaku tidak terduga:
- Investigasi penyebab dulu (baca stack trace lengkap, cek log, reproduksi) — jangan langsung menebak-nebak solusi dan mengubah kode secara acak.
- Jelaskan singkat akar masalah yang ditemukan sebelum menerapkan fix.

## 13. Kapan Harus Berhenti dan Bertanya ke User

Lanjutkan sendiri untuk keputusan kecil (struktur folder detail, nama variabel, pustaka pendukung minor) dan sebutkan singkat apa yang diputuskan.

Berhenti dan tanya ke user jika:
- Ada konflik dengan PRD yang tidak bisa diselesaikan sendiri.
- Butuh kredensial/secret yang belum tersedia.
- Keputusan arsitektur besar yang mengubah scope step saat ini.

## 14. Git

Commit di akhir setiap step/task yang selesai dengan pesan jelas (sebutkan step/fitur apa), setelah build/test dipastikan berhasil.
