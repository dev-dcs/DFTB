#!/system/bin/sh
# {{MODULE_NAME}} — post-fs-data stage.
# Runs early in boot, BEFORE Zygote, and BLOCKS boot while it runs: keep it fast and side-effect
# free. Its only job is to guarantee DFTB's root-owned journal tree exists with tight permissions
# so the late-boot service.sh and the root-mode engines have somewhere to read/write.
# POSIX sh only; no bashisms. Idempotent.

MODDIR=${0%/*}

# Create the state root and journal dir if missing (mkdir -p is a no-op when they exist).
mkdir -p "{{JOURNAL_DIR}}" 2>/dev/null

# Owner-only: this tree holds job state for the device owner's own backups.
chmod 0700 "{{DATA_ROOT}}" 2>/dev/null
chmod 0700 "{{JOURNAL_DIR}}" 2>/dev/null
