#!/bin/sh
set -eu
key="$(cat /run/secrets/livekit_api_key)"
secret="$(cat /run/secrets/livekit_api_secret)"
cp /etc/livekit-base.yaml /tmp/livekit.yaml
printf '\nkeys:\n  %s: %s\n' "$key" "$secret" >> /tmp/livekit.yaml
exec /livekit-server --config /tmp/livekit.yaml
