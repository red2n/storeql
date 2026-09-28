# storeql-backup: the stack's Postgres backed up on a schedule, verified, restored, and recovered to a
# moment (the rehearsed backup-restore drill). The Postgres image's own tools — pg_dump, pg_restore,
# pg_basebackup, pg_archivecleanup, psql — plus age to encrypt a backup to a key the backup host cannot
# read with, jq to write the manifests, bash for the script and tzdata for the schedule.
FROM postgres:16-alpine
RUN apk add --no-cache age bash jq tzdata
COPY infra/backup/storeql-backup.sh /usr/local/bin/storeql-backup
COPY infra/postgres-init-roles.sql /usr/local/share/storeql/postgres-init-roles.sql
RUN chmod 0755 /usr/local/bin/storeql-backup && mkdir -p /backups && chown postgres:postgres /backups
USER postgres
ENV STOREQL_BACKUP_ROOT=/backups
ENTRYPOINT ["/usr/local/bin/storeql-backup"]
CMD ["schedule"]
