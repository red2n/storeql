# Backup and restore drills

Each entry is appended by `scripts/backup-drill.sh` (`scripts/restore-rehearsal.sh` runs the same): a
backup taken by the stack's backup job, verified as stored, restored into a fresh Postgres with the
per-service roles re-applied and every table compared with the backup's own manifest; then a base
backup, two markers, and the archived WAL recovered to the moment between them. The first entry is the
older rehearsal, a live dump restored, from before the backup job existed. How it all works:
[BACKUP-AND-RESTORE.md](BACKUP-AND-RESTORE.md).

## 2026-09-15 11:24 UTC

- Source: `storeql-postgres`, database `storeql`; restored on PostgreSQL 16.15
- 255 tables, 91031 rows, a 5691630-byte custom-format dump
- Dump 1.2s, copy 0.4s, fresh server up 2.7s, restore 3.2s, roles 0.2s, verify 0.3s: **7.9s in all**
- Result: every table matched

## 2026-09-23 10:28 UTC

- Drill: `scripts/backup-drill.sh` against `storeql-postgres`/`storeql`; the backup taken by the stack's backup job, restored on PostgreSQL 16.15
- Backup `storeql-20260923T102825Z.dump.age`: 367 tables, 7522 rows, 1469159 bytes, encrypted yes; taken in 1.8s, verified as stored (checksum, decryption, every entry) in 1.0s
- Full restore: fresh server up 1.5s, restored with the roles re-applied and every table compared in 3.8s: **every table matched: 367 tables, 7522 rows, restore 3.0s**
- Point in time: base backup 3.0s, WAL archived within 4.7s of the switch, recovery to `2026-09-23 10:28:38.382122+00` served in 1.6s: **the earlier write back, the later one not** (3 marker rows on the recovered server; archive_timeout 5min bounds the data at risk)
- 17.6s in all

## 2026-10-01 05:47 UTC

- Drill: `scripts/backup-drill.sh` against `storeql-postgres`/`storeql`; the backup taken by the stack's backup job, restored on PostgreSQL 16.15
- Backup `storeql-20261001T054732Z.dump`: 430 tables, 45827 rows, 3724312 bytes, encrypted no; taken in 2.5s, verified as stored (checksum, decryption, every entry) in 1.3s
- Full restore: fresh server up 2.7s, restored with the roles re-applied and every table compared in 7.7s: **every table matched: 430 tables, 45827 rows, restore 6.0s**
- Point in time: base backup 4.1s, WAL archived within 4.8s of the switch, recovery to `2026-10-01 05:47:52.181732+00` served in 2.9s: **the earlier write back, the later one not** (1 marker rows on the recovered server; archive_timeout 5min bounds the data at risk)
- 26.3s in all

## 2026-10-07 09:19 UTC

- Drill: `scripts/backup-drill.sh` against `storeql-postgres`/`storeql`; the backup taken by the stack's backup job, restored on PostgreSQL 16.15
- Backup `storeql-20261007T091912Z.dump`: 438 tables, 1011 rows, 1302930 bytes, encrypted no; taken in 2.2s, verified as stored (checksum, decryption, every entry) in 1.0s
- Full restore: fresh server up 2.6s, restored with the roles re-applied and every table compared in 5.4s: **every table matched: 438 tables, 1011 rows, restore 4.0s**
- Point in time: base backup 3.8s, WAL archived within 4.7s of the switch, recovery to `2026-10-07 09:19:28.752035+00` served in 2.9s: **the earlier write back, the later one not** (1 marker rows on the recovered server; archive_timeout 5min bounds the data at risk)
- 23.1s in all

## 2026-10-07 15:30 UTC

- Drill: `scripts/backup-drill.sh` against `storeql-postgres`/`storeql`; the backup taken by the stack's backup job, restored on PostgreSQL 16.15
- Backup `storeql-20261007T152956Z.dump`: 438 tables, 26203 rows, 3001244 bytes, encrypted no; taken in 1.7s, verified as stored (checksum, decryption, every entry) in 1.0s
- Full restore: fresh server up 1.4s, restored with the roles re-applied and every table compared in 4.5s: **every table matched: 438 tables, 26203 rows, restore 4.0s**
- Point in time: base backup 3.6s, WAL archived within 4.6s of the switch, recovery to `2026-10-07 15:30:09.793456+00` served in 1.6s: **the earlier write back, the later one not** (1 marker rows on the recovered server; archive_timeout 5min bounds the data at risk)
- 18.8s in all

## 2026-10-09 11:32 UTC

- Drill: `scripts/backup-drill.sh` against `storeql-postgres`/`storeql`; the backup taken by the stack's backup job, restored on PostgreSQL 16.15
- Backup `storeql-20261009T113226Z.dump`: 447 tables, 114548 rows, 6792221 bytes, encrypted no; taken in 2.7s, verified as stored (checksum, decryption, every entry) in 1.1s
- Full restore: fresh server up 2.6s, restored with the roles re-applied and every table compared in 5.1s: **every table matched: 447 tables, 114548 rows, restore 4.0s**
- Point in time: base backup 4.3s, WAL archived within 4.7s of the switch, recovery to `2026-10-09 11:32:43.087846+00` served in 2.7s: **the earlier write back, the later one not** (1 marker rows on the recovered server; archive_timeout 5min bounds the data at risk)
- 23.6s in all
