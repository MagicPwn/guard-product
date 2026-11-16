#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SECRETS="$ROOT/secrets"

mkdir -p "$SECRETS"
chmod 700 "$SECRETS"

for name in postgres_password jwt_secret policy_private.pem policy_public.pem; do
    if [ -e "$SECRETS/$name" ]; then
        echo "Secret already exists: $SECRETS/$name" >&2
        echo "Refusing to replace existing secrets. Back them up and remove them explicitly to rotate." >&2
        exit 1
    fi
done

command -v openssl >/dev/null
umask 077

tmp="$(mktemp "$SECRETS/.policy-key.XXXXXX")"
der="$(mktemp "$SECRETS/.policy-public.XXXXXX")"
trap 'rm -f "$tmp" "$der"' EXIT INT TERM

openssl ecparam -name prime256v1 -genkey -noout -out "$tmp"
openssl pkcs8 -topk8 -nocrypt -in "$tmp" -out "$SECRETS/policy_private.pem"
openssl ec -in "$tmp" -pubout -out "$SECRETS/policy_public.pem"
openssl pkey -pubin -in "$SECRETS/policy_public.pem" -outform DER -out "$der"
openssl rand -base64 32 > "$SECRETS/postgres_password"
openssl rand -base64 48 > "$SECRETS/jwt_secret"

chmod 444 "$SECRETS/postgres_password" "$SECRETS/jwt_secret" \
    "$SECRETS/policy_private.pem" "$SECRETS/policy_public.pem"

key_id="$(sha256sum "$der" | cut -d' ' -f1)"
public_key="$(base64 -w0 "$der")"
printf 'Policy key id:     %s\nPolicy public key: %s\n' "$key_id" "$public_key"
printf '\nAPK build flags:\n-PguardPolicyKeyId=%s\n-PguardPolicyPublicKey=%s\n' "$key_id" "$public_key"
