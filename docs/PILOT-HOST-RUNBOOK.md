# Pilot host runbook

One virtual machine in London running the compose stack for one customer (two stores), supervised.
This is what to set up once, what to look at every day, and what to do when something is wrong. It
assumes the release path in [RELEASE-PROCESS.md](RELEASE-PROCESS.md) and the backups in
[BACKUP-AND-RESTORE.md](BACKUP-AND-RESTORE.md); [vps-deployment.md](vps-deployment.md) has the longer
background (DNS, TLS, the Caddy edge).

## What is promised, and what is not

Promised during the pilot: in-store selling on the tills with shelf prices and VAT shown the way the shop
prints it, stock, purchasing, the importer, daily cash-up, backups and a restore that has been rehearsed.
**Not promised:** an uptime figure, offline scanning, integrated card terminals (the customer's own
machines are used and each card sale is recorded with the machine's receipt reference), the online
storefront (kept closed), or a go-live date. Staff run the old till system beside this one until the
customer says otherwise.

## Before any real data

- The data processing agreement (UK GDPR Art. 28) is signed. Until it is, the stack holds test data only.
- The accountant has said how the business accounts for VAT (the retail scheme it uses), and the
  customer's VAT number and legal name are in the business's profile.
- A restore has been done from the **off-site** copy onto a clean machine, and the time it took is written
  down (`scripts/backup-drill.sh` appends the drill to [RESTORE-REHEARSAL.md](RESTORE-REHEARSAL.md)).

## The host, once

| | |
|---|---|
| Where | a UK region (London), with a second small disk or volume for backups |
| Size to start | 4 vCPU, 16 GB RAM, 200 GB SSD, plus the backup volume; grow before the second store trades |
| OS | Ubuntu LTS, unattended security updates on, time sync on, SSH by key only, no password login |
| Firewall | 80 and 443 in; nothing else (every other port is bound to 127.0.0.1 by the compose file) |
| DNS | the app and API names point at the host before the first start (Caddy gets its certificates then) |
| Docker | Docker Engine and the Compose plugin; the deploy user is in the `docker` group |

1. `git clone` the repository at the release tag (for the compose files and scripts; the images come from GHCR).
2. `cp .env.example .env`, then set: `STOREQL_ENV=prod`, `STOREQL_TAG=<the release, for example 0.1.0>`, every
   `*_PASSWORD` and `STOREQL_*` secret (generate them; never reuse the development defaults), the Caddy
   address, `STOREQL_BACKUP_RECIPIENT` (below), and the Alertmanager receivers (below). `.env` is never committed.
3. `docker volume create storeql_pgdata storeql_backups` once. The production overlay declares them
   external, so a `down -v` cannot remove them.
4. **The backup key.** `docker compose run --rm backup age-keygen` prints a key pair. The public key goes
   in `STOREQL_BACKUP_RECIPIENT`; the **identity** (the secret line) is stored off the machine in two
   places (the owner's password manager and a sealed copy with the solicitor or accountant). Without it no
   backup can be read, by anyone, including you.
5. `scripts/deploy-release.sh --check <release>` pulls the images and checks the host; then
   `scripts/deploy-release.sh <release>` for the first deploy.
6. Create the platform administrator (`docs/vps-deployment.md` §7), then onboard the business, its two
   stores and zones, its staff and roles, and a store-default putaway rule per store (so opening stock is
   placed, not queued as a task per item).
7. **Off-site backup.** Define an rclone remote for a bucket in another provider's account (write and list,
   not delete) and put `OFFSITE_REMOTE=offsite:<bucket>/<prefix>` in the deploy user's crontab at 03:15 UTC:
   `15 3 * * * cd /opt/storeql && OFFSITE_REMOTE=... scripts/offsite-backup.sh >> /var/log/storeql-offsite.log 2>&1`.
8. **Alerts.** Copy `infra/alertmanager/alertmanager.yml`, set a real receiver (the owner's and the
   developer's addresses, through the mail provider's SMTP), and point `ALERTMANAGER_CONFIG_FILE` and
   `ALERTMANAGER_SECRETS_DIR` at it. Send a test alert and confirm it arrives **in a person's inbox**.

## Every day (five minutes)

- The gateway answers (`/health`), and every container is `healthy` (`docker compose ps`).
- Last night's dump exists and the off-site heartbeat is from today:
  `cat .storeql-deploy/offsite.last` and `docker compose exec backup ls -t /backups/dumps | head -3`.
- Grafana's overview: no firing alert, disk under 70%, no service restarting.
- The tills' **system health** screen (an owner or manager sees it): nothing waiting, nothing failing.
- A cash-up exists for yesterday for each store, and the day report is settled.

## Every month

- Restore the newest **off-site** dump onto a throwaway Postgres and compare the manifest
  (`storeql-backup restore`), then `scripts/backup-drill.sh` on the stack. Write the time in
  [RESTORE-REHEARSAL.md](RESTORE-REHEARSAL.md). A backup nobody has restored is a hope.
- Rotate nothing without a plan: changing `STOREQL_JWT_SECRET` strands the token signing keys.
- Read the dependency scan's open findings and the unreleased changes; decide whether a release is due.

## Upgrading, and going back

In a closed window, with the customer told: `scripts/deploy-release.sh --check <release>`, then
`scripts/deploy-release.sh <release>`. It takes and **verifies** a backup before it stops anything, runs
each service's migrations on the new images, and records the release only when everything is up and a
smoke request passes. If it fails it leaves the edge stopped and prints what to run: **going back is
running the previous release** (`scripts/deploy-release.sh --rollback`), because every migration since the
first tag is additive; a release that carries a `storeql:contract` migration is the one exception and goes
back by restoring the backup it printed. Never `down -v`, never `--wipe-data`: the second refuses to run
here.

## When something is wrong

| What you see | First thing to do |
|---|---|
| A till cannot sell | Check the gateway and `order-svc`/`pricing-svc` health; the till can hold a sale and carry on offline for sales it can ring from what it has already loaded, but it cannot scan a new barcode offline. The shop uses its old till meanwhile. |
| A card sale will not record | The tender needs the machine's receipt reference; look at the error words on screen. Never record a card the machine did not approve. |
| A service restarting | `docker compose logs --tail 200 <service>`; a failed migration stops the service on purpose: do not force it, go back. |
| Disk filling | `docker system df`, the WAL archive (`backups`), Loki/Tempo volumes; free space before anything else restarts. |
| The database is damaged or lost | Stop the edge, restore the newest verified dump (point-in-time if needed) per [BACKUP-AND-RESTORE.md](BACKUP-AND-RESTORE.md), start the services, check the manifest counts, then tell the customer exactly how much time was lost. |
| A customer's data request (access, erasure) | The business's owner makes it from the app (privacy tools); do not touch the database by hand. |

Keep a short incident log (what, when, who knew, what changed) in the repository's `docs/` after each one.
