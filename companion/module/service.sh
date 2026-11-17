#!/system/bin/sh
# DFTB Companion - late_start service. Runs late in boot, off the critical path.
# Two jobs: resume backup jobs the reboot interrupted, and (if turned on) check
# for a module update. POSIX sh only; idempotent.

MODDIR=${0%/*}
DATA=/data/adb/dftb
LOG=$DATA/service.log
CONF=$DATA/companion.conf

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >>"$LOG" 2>/dev/null; }

export PATH="$MODDIR/system/bin:/system/bin:/system/xbin:$PATH"

# run a command in the global (init) mount namespace so it sees the same /data
# the engines snapshot; falls back to running in place when nsenter is absent.
global_ns() {
  if command -v nsenter >/dev/null 2>&1; then nsenter -t 1 -m "$@"; else "$@"; fi
}

mkdir -p "$DATA/journal" 2>/dev/null
chmod 0700 "$DATA" "$DATA/journal" 2>/dev/null
log "service start v$(sed -n 's/^version=v//p' "$MODDIR/module.prop")"

# --- reboot resume -------------------------------------------------------------
pending=0
for f in "$DATA"/journal/*.json; do
  [ -f "$f" ] || continue
  global_ns grep -q '"resumeAfterReboot":[ ]*true' "$f" 2>/dev/null || continue
  global_ns grep -Eq '"status":[ ]*"(running|interrupted)"' "$f" 2>/dev/null || continue
  pending=$((pending + 1))
done

if [ "$pending" -gt 0 ]; then
  log "$pending resumable job(s); waking the app"
  { echo "triggeredAt=$(date '+%s')"; echo "pending=$pending"; } >"$DATA/resume.trigger" 2>/dev/null
  chmod 0600 "$DATA/resume.trigger" 2>/dev/null
  i=0; while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 60 ]; do sleep 2; i=$((i+1)); done
  i=0; while [ "$(getprop sys.user.0.ce_available)" != true ] && [ "$i" -lt 300 ]; do sleep 2; i=$((i+1)); done
  if command -v am >/dev/null 2>&1; then
    am broadcast -a ca.devdcs.backup.action.RESUME_JOBS \
      -n ca.devdcs.backup/ca.devdcs.backup.companion.BootResumeTrigger >/dev/null 2>&1 \
      && log "resume broadcast sent" || log "WARN resume broadcast failed; app BOOT_COMPLETED will cover it"
  fi
else
  rm -f "$DATA/resume.trigger" 2>/dev/null
  log "no resumable jobs"
fi

# --- update check (opt-in) -----------------------------------------------------
if grep -q '^update_check=1' "$CONF" 2>/dev/null && [ -f "$MODDIR/libexec/update-check.sh" ]; then
  ( until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 10; done
    sh "$MODDIR/libexec/update-check.sh" --boot ) </dev/null >/dev/null 2>&1 &
fi

log "service done"
