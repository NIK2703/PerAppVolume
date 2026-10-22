#!/system/bin/sh
# Remove the setting, the LSPosed registration and the companion app.

i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
    i=$((i + 1))
    [ "$i" -gt 150 ] && break
done

settings delete system show_app_volume >/dev/null 2>&1

DB=/data/adb/lspd/config/modules_config.db
if [ -f "$DB" ] && command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$DB" \
        "DELETE FROM scope WHERE module_pkg_name='com.appvolume';\
         DELETE FROM modules_state WHERE module_pkg_name='com.appvolume';\
         DELETE FROM modules WHERE module_pkg_name='com.appvolume';" \
        >/dev/null 2>&1
fi

pm uninstall com.appvolume >/dev/null 2>&1
