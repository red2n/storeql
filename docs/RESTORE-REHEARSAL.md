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
