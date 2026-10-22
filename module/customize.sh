#!/system/bin/sh
# App volume: normal companion APK plus an LSPosed hook for SystemUI.

ui_print "- App volume (crDroid-style SystemUI panel)"
ui_print "- Installing the companion app..."
ui_print "- Enabling the LSPosed hook for SystemUI..."

APK="$MODPATH/AppVolume.apk"
if [ -f "$APK" ]; then
    pm install -r "$APK" >/dev/null 2>&1 \
        && ui_print "- Companion app installed." \
        || ui_print "- Automatic install failed; install AppVolume.apk manually."
fi

sleep 1
APP_APK="$(pm path com.appvolume 2>/dev/null | head -n1 | sed 's/^package://')"
DB=/data/adb/lspd/config/modules_config.db
if [ -n "$APP_APK" ] && [ -f "$DB" ] && command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$DB" \
        "INSERT OR REPLACE INTO modules(module_pkg_name,apk_path) VALUES('com.appvolume','$APP_APK');\
         INSERT OR IGNORE INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) VALUES('com.appvolume',0,1,0);\
         INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('com.appvolume','com.android.systemui',0);" \
        >/dev/null 2>&1 \
        && ui_print "- LSPosed: module enabled for SystemUI." \
        || ui_print "- LSPosed: could not update the module database; enable it manually."
else
    ui_print "- LSPosed: enable the module for SystemUI manually."
fi

pm grant com.appvolume android.permission.DUMP >/dev/null 2>&1 || true
pm grant com.appvolume android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
settings put system show_app_volume 1 >/dev/null 2>&1 || true

ui_print "- Reboot to load the SystemUI hook."
