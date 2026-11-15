#!/bin/sh
set -eu
root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
secrets="$root/secrets"
mkdir -p "$secrets"
command -v openssl >/dev/null

tmp="$secrets/.policy-key.tmp"
der="$secrets/.policy-public.der"
trap 'rm -f "$tmp" "$der"' EXIT INT TERM
openssl ecparam -name prime256v1 -genkey -noout -out "$tmp"
openssl pkcs8 -topk8 -nocrypt -in "$tmp" -out "$secrets/policy_private.pem"
openssl ec -in "$tmp" -pubout -out "$secrets/policy_public.pem"
openssl pkey -pubin -in "$secrets/policy_public.pem" -outform DER -out "$der"

openssl rand -base64 32 > "$secrets/postgres_password"
openssl rand -base64 48 > "$secrets/jwt_secret"
printf 'API%s' "$(openssl rand -base64 18 | tr '+/' 'AB' | tr -d '\n')" > "$secrets/livekit_api_key"
openssl rand -base64 32 > "$secrets/livekit_api_secret"
chmod 700 "$secrets"
chmod 444 "$secrets"/*

key_id="$(sha256sum "$der" | cut -d' ' -f1)"
public_key="$(base64 -w0 "$der")"
printf 'Policy key id:     %s\nPolicy public key: %s\n' "$key_id" "$public_key"
printf '\nBuild flags:\n-PguardPolicyKeyId=%s\n-PguardPolicyPublicKey=%s\n' "$key_id" "$public_key"
