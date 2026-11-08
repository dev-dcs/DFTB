#!/system/bin/sh
# {{MODULE_NAME}} v{{VERSION_NAME}} — late_start service stage.
#
# Runs once, late in boot, in a background process that does NOT block boot. Its job is to notice
# backup/restore jobs that were interrupted by the reboot and ask the DFTB app to resume them.
#
# Flow:
#   1. Make the bundled CLI tools reachable on PATH.
#   2. Ensure the root-owned journal tree exists.
#   3. Scan the journal for jobs flagged resume-after-reboot that did not finish.
#   4. If any are found, drop a trigger file and broadcast an intent to the app so it re-enqueues
#      its WorkManager resume worker. The app itself also runs on BOOT_COMPLETED; the two paths
#      converge on one unique work item, so firing both is safe.
#
# POSIX sh only; no bashisms. Guarded and idempotent — safe to run again by hand.

MODDIR=${0%/*}
LOG="{{SERVICE_LOG}}"

log() {
  # Append a timestamped line to the service log; never fail boot if logging fails.
  echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >>"$LOG" 2>/dev/null
}

# --- 1. PATH --------------------------------------------------------------------------------------
# The module's /system/bin is already overlaid onto the live /system/bin by the root manager, but
# prepend the module's own copy too so the tools resolve even before the overlay settles.
export PATH="{{MODULE_DIR}}/system/bin:/system/bin:/system/xbin:$PATH"

# --- global mount namespace helper ----------------------------------------------------------------
# For a CONSISTENT view of /data (e.g. when the engines snapshot /data or block partitions), work
# must happen in the global (init, pid 1) mount namespace, because the root daemon runs in an
# isolated namespace by default. Run a command there with:  dftb_global_ns <cmd> [args...]
# This mirrors Command.requireGlobalMountNamespace on the Kotlin side.
dftb_global_ns() {
  if [ "{{USE_GLOBAL_NS}}" = "1" ] && command -v nsenter >/dev/null 2>&1; then
    nsenter -t 1 -m "$@"
  else
    "$@"
  fi
}

log "service.sh start (v{{VERSION_NAME}}, boot_resume={{BOOT_RESUME_ENABLED}}, global_ns={{USE_GLOBAL_NS}})"

# --- 2. journal tree ------------------------------------------------------------------------------
mkdir -p "{{JOURNAL_DIR}}" 2>/dev/null
chmod 0700 "{{DATA_ROOT}}" "{{JOURNAL_DIR}}" 2>/dev/null

# Boot-resume may be disabled when a tools-only module was built.
if [ "{{BOOT_RESUME_ENABLED}}" != "1" ]; then
  log "boot resume disabled in this module; nothing to do"
  exit 0
fi

# --- 3. scan journal ------------------------------------------------------------------------------
# The app (CompanionJournalMirror, in companion-module mode) writes one JSON state file per job
# under the journal dir. A file describing an unfinished,
# resume-after-reboot job contains both markers below. Matching is substring-based so it does not
# depend on a JSON parser being present.
pending=0
if [ -d "{{JOURNAL_DIR}}" ]; then
  for f in "{{JOURNAL_DIR}}"/*.json; do
    [ -f "$f" ] || continue
    if dftb_global_ns grep -q '"resumeAfterReboot":[ ]*true' "$f" 2>/dev/null; then
      if dftb_global_ns grep -Eq '"status":[ ]*"(running|interrupted)"' "$f" 2>/dev/null; then
        pending=$((pending + 1))
        log "found resumable job: $(basename "$f")"
      fi
    fi
  done
fi

if [ "$pending" -eq 0 ]; then
  log "no resumable jobs in journal; exiting"
  # Clear any stale trigger so the app does not act on an old marker.
  rm -f "{{RESUME_TRIGGER_FILE}}" 2>/dev/null
  exit 0
fi

log "$pending resumable job(s) pending; triggering app"

# --- 4. trigger the app ---------------------------------------------------------------------------
# Durable marker (root-readable) recording that this boot saw resumable work.
{
  echo "triggeredAt=$(date '+%s')"
  echo "pending=$pending"
} >"{{RESUME_TRIGGER_FILE}}" 2>/dev/null
chmod 0600 "{{RESUME_TRIGGER_FILE}}" 2>/dev/null

# Wait (bounded) for the framework to finish booting so `am` can reach Activity/Package managers.
i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$i" -lt 60 ]; do
  sleep 2
  i=$((i + 1))
done

# On file-based-encrypted devices with a lock-screen credential, boot completes before the user
# unlocks, and the app's receiver (not direct-boot aware) cannot run until then. Wait (bounded,
# ~10 min) for credential-encrypted storage so the broadcast is not dropped.
i=0
while [ "$(getprop sys.user.0.ce_available 2>/dev/null)" != "true" ] && [ "$i" -lt 300 ]; do
  sleep 2
  i=$((i + 1))
done

# Explicitly target the app's resume receiver. Running as root, `am` can deliver to it directly.
if command -v am >/dev/null 2>&1; then
  if am broadcast -a "{{RESUME_ACTION}}" -n "{{RESUME_COMPONENT}}" >/dev/null 2>&1; then
    log "broadcast {{RESUME_ACTION}} sent to {{RESUME_COMPONENT}}"
  else
    log "WARN: am broadcast failed; the app will still resume on its own BOOT_COMPLETED receiver"
  fi
else
  log "WARN: am not found; relying on the app's BOOT_COMPLETED receiver"
fi

log "service.sh done"
exit 0
