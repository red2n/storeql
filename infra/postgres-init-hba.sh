#!/bin/sh
# On a fresh volume: let the backup job take base backups for point-in-time recovery. pg_basebackup
# speaks the replication protocol, which the official image's pg_hba.conf allows from the local host
# only; the backup job is another container. Against an existing volume docker-compose.yml's Postgres
# command appends the same line at start. The marker word keeps both idempotent.
set -e
HBA="${PGDATA:-/var/lib/postgresql/data}/pg_hba.conf"
grep -q storeql-backup "$HBA" || printf 'host replication all all scram-sha-256 # storeql-backup: base backups for point-in-time recovery\n' >> "$HBA"
