# Status kompatibilitas PostgreSQL sebelum Step 7

Diperiksa 18 Oktober 2026. Database testing lokal memakai PostgreSQL **18.4**.
Dependency project saat ini adalah Flyway **11.7.2** (dikelola Spring Boot
3.5.16). Versi Flyway tersebut memperingatkan bahwa pengujiannya baru sampai
PostgreSQL 17. Warning ini bukan pernyataan versi PostgreSQL pada PRD.

Versi PostgreSQL project Supabase pengguna **belum diketahui**: tidak ada
project ref/koneksi Supabase atau `DB_URL` produksi yang tersedia pada checkout
ini. Jangan menyimpulkan versinya 15/16/17/18 dari versi lokal atau dari default
layanan. Pengujian lokal 18.4 tidak membuktikan kompatibilitas major lain.

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

Risiko yang masih terbuka: major PostgreSQL produksi belum terverifikasi dan
belum diuji secara cocok. Status ini harus ditutup sebelum deployment produksi.
