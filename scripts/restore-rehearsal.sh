#!/usr/bin/env bash
# Kept for the name the docs and the readiness review used: the restore rehearsal is the first half
# of scripts/backup-drill.sh, which also recovers to a moment. Runs the whole drill.
exec "$(dirname "$0")/backup-drill.sh" "$@"
