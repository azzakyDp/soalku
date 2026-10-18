# Status kompatibilitas PostgreSQL sebelum Step 7

Diperiksa 18 Oktober 2026. Database testing lokal memakai PostgreSQL **18.4**.
Dependency project saat ini adalah Flyway **11.7.2** (dikelola Spring Boot
3.5.16). Versi Flyway tersebut memperingatkan bahwa pengujiannya baru sampai
PostgreSQL 17. Warning ini bukan pernyataan versi PostgreSQL pada PRD.

Koneksi session pooler Supabase berhasil diverifikasi secara read-only pada
18 Oktober 2026: `server_version = 17.11`, `server_version_num = 170011`.
Hanya query versi dijalankan; tidak ada migrasi, seed, atau integration test
yang diarahkan ke database ini. Kredensial hanya tersimpan dalam `.env` lokal
yang diabaikan Git. Pengujian lokal 18.4 tidak membuktikan kompatibilitas major 17.

Pemilik project dapat menjalankan query read-only berikut pada SQL Editor
Supabase (tidak menjalankan Flyway atau mengubah data produksi):

```sql
SELECT version();
SHOW server_version;
SHOW server_version_num;
```

Sumber: [panduan resmi Supabase](https://supabase.com/docs/guides/database/postgres/which-version-of-postgres).
Setelah hasil tersedia, gunakan PostgreSQL testing terisolasi dengan major yang
sama dan jalankan `mvn -Ppostgres-it clean install` terhadap database lokal
bernama `*_test`. Jalankan seluruh migrasi V1–V3, pengujian constraint, rollback,
JPA, autentikasi, upload dan generasi sebelum deployment. Jangan arahkan
integration test ke Supabase produksi; guard test memang membatasi loopback
dan suffix `_test`.

Dokumentasi [Flyway terbaru](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)
sudah menyertakan PostgreSQL 18, tetapi itu tidak mengubah versi 11.7.2 yang
terpasang pada project. Upgrade Flyway perlu perubahan dependency dan regresi
tersendiri; tidak dilakukan diam-diam pada checkpoint Step 1–6 ini.

Risiko yang masih terbuka: PostgreSQL produksi 17.11 sudah terverifikasi,
tetapi seluruh integration test saat ini masih dijalankan pada PostgreSQL lokal
18.4. Ulangi suite pada PostgreSQL 17 terisolasi sebelum deployment produksi.
