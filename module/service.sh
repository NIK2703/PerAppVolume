#!/system/bin/sh
# App volume module: finish setup after Android has booted.
MODDIR=${0%/*}
LOG="$MODDIR/service.log"
PACKAGE=com.appvolume
DB=/data/adb/lspd/config/modules_config.db

echo "--- $(date) boot ---" >> "$LOG"

i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
    i=$((i + 1))
    [ "$i" -gt 150 ] && exit 0
done

# PackageManager and SettingsProvider are often not ready when
# sys.boot_completed is set. Give the framework time, then tolerate failures:
# customize.sh already performs the same setup during installation.
sleep 25
i=0
while [ "$i" -lt 60 ]; do
    if pm path android >/dev/null 2>&1 \
            && settings get global boot_count >/dev/null 2>&1; then
        break
    fi
    sleep 2
    i=$((i + 1))
done

if ! pm path "$PACKAGE" >/dev/null 2>&1; then
    pm install -r "$MODDIR/AppVolume.apk" >> "$LOG" 2>&1 || true
fi

pm grant "$PACKAGE" android.permission.DUMP >> "$LOG" 2>&1 || true
pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >> "$LOG" 2>&1 || true
settings put system show_app_volume 1 >> "$LOG" 2>&1 || true

APP_APK="$(pm path "$PACKAGE" 2>/dev/null | head -n1 | sed 's/^package://')"
if [ -n "$APP_APK" ] && [ -f "$DB" ] && command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$DB" "UPDATE modules SET apk_path='$APP_APK' WHERE module_pkg_name='$PACKAGE' AND apk_path!='$APP_APK';" >> "$LOG" 2>&1 || true
    sqlite3 "$DB" "INSERT OR IGNORE INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) VALUES('$PACKAGE',0,1,0);" >> "$LOG" 2>&1 || true
    sqlite3 "$DB" "INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('$PACKAGE','com.android.systemui',0);" >> "$LOG" 2>&1 || true
fi

# Start the session reporter if the app is installed. It stops itself when
# there are no per-app volumes.
if pm path "$PACKAGE" >/dev/null 2>&1; then
    am start-foreground-service -n "$PACKAGE/.VolumeService" >> "$LOG" 2>&1 || true
fi

{
    echo "package:"
    pm path "$PACKAGE" 2>&1
    dumpsys package "$PACKAGE" 2>/dev/null | grep -E "versionName|versionCode|android.permission.DUMP" | head -20
} >> "$LOG" 2>&1

tail -n 300 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
