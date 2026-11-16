#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ARCHIVE="${1:-$ROOT/incoming/guard-server.zip}"
COMPOSE="docker compose -f $ROOT/docker-compose.yml --env-file $ROOT/.env"
API_PORT="$(sed -n 's/^GUARD_API_PORT=//p' "$ROOT/.env" 2>/dev/null | tail -n 1)"
API_PORT="${API_PORT:-18080}"
STAMP="$(date +%Y%m%d-%H%M%S)"
STAGING="$ROOT/.update-$STAMP"
BACKUP="$ROOT/backups/app-$STAMP"

cleanup() {
    rm -rf "$STAGING"
}
trap cleanup EXIT INT TERM

if [ ! -f "$ARCHIVE" ]; then
    echo "Server distribution not found: $ARCHIVE" >&2
    echo "Usage: $0 /path/to/guard-server-*.zip" >&2
    exit 1
fi

mkdir -p "$ROOT/backups" "$STAGING"
case "$ARCHIVE" in
    *.zip) unzip -q "$ARCHIVE" -d "$STAGING" ;;
    *.tar.gz|*.tgz) tar -xzf "$ARCHIVE" -C "$STAGING" ;;
    *) echo "Unsupported archive type: $ARCHIVE" >&2; exit 1 ;;
esac

SOURCE="$(find "$STAGING" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
if [ -z "$SOURCE" ] || [ ! -f "$SOURCE/bin/guard-server" ]; then
    echo "Archive does not contain a Guard server distribution" >&2
    exit 1
fi
chmod +x "$SOURCE/bin/guard-server"

if [ -d "$ROOT/app" ]; then
    cp -a "$ROOT/app" "$BACKUP"
fi

rm -rf "$ROOT/app.next"
cp -a "$SOURCE" "$ROOT/app.next"
chmod +x "$ROOT/app.next/bin/guard-server"

rm -rf "$ROOT/app"
mv "$ROOT/app.next" "$ROOT/app"

rollback() {
    echo "Update failed; restoring the previous server distribution." >&2
    if [ -d "$BACKUP" ]; then
        rm -rf "$ROOT/app"
        cp -a "$BACKUP" "$ROOT/app"
        $COMPOSE build server || true
        $COMPOSE up -d --remove-orphans || true
    fi
}

if ! $COMPOSE build server; then
    rollback
    exit 1
fi

if ! $COMPOSE up -d --remove-orphans; then
    rollback
    exit 1
fi

attempt=0
while [ "$attempt" -lt 60 ]; do
    if curl -fsS "http://127.0.0.1:${API_PORT}/healthz" | grep -q '"database":true'; then
        echo "Guard server is healthy on port ${API_PORT}."
        exit 0
    fi
    attempt=$((attempt + 1))
    sleep 2
done

rollback
exit 1
