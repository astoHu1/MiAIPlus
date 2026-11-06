#!/data/data/com.termux/files/usr/bin/bash
# All paths and arguments come from the module's own provisioning code.
set -eu
umask 077
mode=$1 app=$2 port=$3 uid=$4 version=$5
shift 5
prefix=/data/data/com.termux/files/usr
[ "$(/system/bin/id -u)" = "$uid" ] && [ "$uid" != 0 ] || exit 1
base="$prefix/var/lib/miaiplus-mcp/$app/$port"
runtime="$base/runtime-$version"
export PATH="$prefix/bin:/system/bin"
if [ "$mode" = setup ]; then
    url=$1 token=$2 launcher=$3 archive_hash=$4
    mkdir -p "$base"
    exec 9>"$base/setup.lock"
    /system/bin/flock -n 9 || exit 1
    stage=$(mktemp -d "$base/.setup.XXXXXXXX")
    trap 'rm -rf -- "$stage"' EXIT
    if [ ! -f "$runtime/launch.py" ]; then
        curl --fail --silent --show-error --noproxy '*' --max-time 15 "$url" > "$stage/upstream.tar.gz"
        printf '%s  %s\n' "$archive_hash" "$stage/upstream.tar.gz" | sha256sum -c -
        mkdir -p "$stage/runtime/upstream"
        tar -xzf "$stage/upstream.tar.gz" -C "$stage/runtime/upstream"
        printf '%s' "$launcher" | base64 -d > "$stage/runtime/launch.py"
        mv -T "$stage/runtime" "$runtime"
    fi
    if [ ! -x "$prefix/bin/python" ]; then
        # Only explicit setup may install Python. Do not upgrade other packages.
        timeout 60 apt-get update
        timeout 110 apt-get install -y python
    fi
    printf '%s\n' "$uid" "$port" "$token" > "$stage/credentials"
    mv -T "$stage/credentials" "$base/credentials"
    rm -rf -- "$stage"
    trap - EXIT
    exec 9>&-
fi
[ -x "$prefix/bin/python" ] && [ -f "$runtime/launch.py" ] || exit 1
exec "$prefix/bin/python" -I "$runtime/launch.py" "$base"
