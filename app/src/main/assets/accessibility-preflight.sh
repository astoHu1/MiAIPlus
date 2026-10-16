#!/system/bin/sh
# A bounded root helper, invoked only before a new screen task. No resident loop.
PATH=/system/bin:/system/xbin
export PATH
USER_ID=$1
RUN_ID=$2
case "$USER_ID" in ''|*[!0-9]*) exit 2 ;; esac
case "$RUN_ID" in ''|*[!a-f0-9-]*) exit 2 ;; esac
[ "$(id -u)" = 0 ] || exit 3
[ "$(am get-current-user)" = "$USER_ID" ] || exit 4
[ "$(am get-started-user-state "$USER_ID")" = RUNNING_UNLOCKED ] || exit 4
COMPONENT=io.github.miaiplus/io.github.miaiplus.ScreenService
SHORT_COMPONENT=io.github.miaiplus/.ScreenService
health() {
    report=$(timeout 3 content call --user "$USER_ID" --uri content://io.github.miaiplus.bridge --method accessibility_health 2>/dev/null) || return 1
    case "$report" in *preflight_enabled=true*) ;; *) return 1 ;; esac
    case "$report" in *"preflight_id=$RUN_ID"*) ;; *) return 1 ;; esac
    case "$report" in *preparing=true*) ;; *) return 1 ;; esac
    case "$report" in *busy=true*) return 1 ;; esac
}
without_module() {
    echo "$1" | tr ':' '\n' | while IFS= read -r service; do
        case "$service" in ''|null|"$COMPONENT"|"$SHORT_COMPONENT") continue ;; esac
        echo "$service"
    done | paste -sd ':' -
}
health || exit 5
case "$report" in *connected=true*) exit 0 ;; esac
before=$(settings --user "$USER_ID" get secure enabled_accessibility_services) || exit 6
others=$(without_module "$before")
health || exit 5
settings --user "$USER_ID" put secure enabled_accessibility_services "$others" || exit 6
sleep 1
# Read again to preserve other services changed while our service was unbinding.
health || exit 5
latest=$(settings --user "$USER_ID" get secure enabled_accessibility_services) || exit 6
others=$(without_module "$latest")
if [ -n "$others" ]; then next="$others:$COMPONENT"; else next=$COMPONENT; fi
health || exit 5
settings --user "$USER_ID" put secure enabled_accessibility_services "$next" || exit 6
settings --user "$USER_ID" put secure accessibility_enabled 1 || exit 6
# Wait only for this preflight's rebind. Exit before the first screen action.
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    health || exit 5
    case "$report" in *connected=true*) exit 0 ;; esac
    sleep 1
done
exit 7
