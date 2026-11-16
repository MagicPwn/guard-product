# Guard NAS deployment

Persistent Guard API and PostgreSQL deployment for the NAS.

## Runtime layout

- Deployment directory: `/volume2/workspace/service/guard-product`
- API: `http://192.168.68.147:18080`
- PostgreSQL data: `data/postgres`
- Secrets: `secrets/` with directory mode `0700` and file mode `0444`
- Server distribution: `app/`
- Rollback copies: `backups/`
- Incoming update archives: `incoming/`

The full Caddy/PWA/LiveKit stack is intentionally not included because NAS ports
`80` and `443` are already occupied. This deployment exposes only the API on
`18080`.

## Initial install

1. Extract the latest `guard-*.zip` server distribution into `app/` so that
   `app/bin/guard-server` exists.
2. Create `.env` from `.env.example`.
3. Generate the PostgreSQL password, JWT secret, and policy key pair once. Keep
   these files backed up; replacing the policy key changes the APK trust identity.
4. Build, start, and verify the API.
5. Install the systemd unit so the stack starts at boot.

`GUARD_DEBIAN_MIRROR` controls the Debian package mirror used while building the
small Java runtime image. It defaults to Aliyun because the NAS reaches that
mirror much faster than `deb.debian.org`; change it in `.env` if needed. The
HTTP mirror is safe here because APT still verifies the signed package indexes.

```sh
cd /volume2/workspace/service/guard-product
./scripts/generate-secrets.sh
docker compose up -d --build
curl -fsS http://127.0.0.1:18080/healthz
./scripts/install-systemd.sh
```

## Service management

```sh
systemctl status guard-product
systemctl restart guard-product
systemctl stop guard-product
docker compose -f /volume2/workspace/service/guard-product/docker-compose.yml logs -f server
```

The systemd unit is enabled at boot and the containers also use Docker's
`restart: unless-stopped` policy.

## Update

1. Place the new `guard-server-*.zip` in `incoming/`.
2. Run the update script with the archive path.
3. The script backs up the current `app/`, rebuilds the image, waits for
   `/healthz`, and rolls back if the new server does not become healthy.

```sh
cd /volume2/workspace/service/guard-product
./scripts/update.sh incoming/guard-server-0.1.0.zip
```

The update script keeps the PostgreSQL data directory and secrets unchanged.
