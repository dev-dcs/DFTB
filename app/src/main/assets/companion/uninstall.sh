#!/system/bin/sh
# {{MODULE_NAME}} — uninstall stage.
# Run by the root manager when the module is removed. Cleans up the root-owned state the module
# created so no DFTB files linger under /data/adb after removal. POSIX sh only; idempotent.
#
# Note: only the module's own boot/journal state is removed. The app's regular app-private data is
# untouched here; it is managed by the OS when the app is uninstalled.

# Remove the boot-resume trigger marker and the service log.
rm -f "{{RESUME_TRIGGER_FILE}}" 2>/dev/null
rm -f "{{SERVICE_LOG}}" 2>/dev/null

# Remove the journal and the state root (safe no-op if already gone).
rm -rf "{{JOURNAL_DIR}}" 2>/dev/null
rmdir "{{DATA_ROOT}}" 2>/dev/null

exit 0
