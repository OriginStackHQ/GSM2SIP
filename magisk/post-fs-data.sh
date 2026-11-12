#!/system/bin/sh
# Remove skip_mount if it exists — this script runs even when skip_mount
# is present, so it guarantees the module's filesystem overlay is active
# on the next boot.
MODDIR="${0%/*}"
rm -f "$MODDIR/skip_mount"

# Apply sepolicy.rule live: without a preinit partition Magisk cannot
# persist it, and the app would lose root. Log the result for debugging.
LOG="$MODDIR/post-fs-data.log"
echo "--- $(date) ---" > "$LOG"
if [ -x "$(command -v magiskpolicy)" ]; then
    if magiskpolicy --live --apply "$MODDIR/sepolicy.rule" 2>>"$LOG"; then
        echo "gsm2sip: sepolicy.rule applied live" >> "$LOG"
    else
        echo "gsm2sip: magiskpolicy --apply failed" >> "$LOG"
    fi
else
    echo "gsm2sip: magiskpolicy not in PATH" >> "$LOG"
fi
