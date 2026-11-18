# Guard NAS deployment

Persistent Guard API and PostgreSQL deployment for the NAS.

## Runtime layout

- Deployment directory: `/volume2/workspace/service/guard-product`
- API: `http://192.168.68.147:18080`
- Parent console: `http://192.168.68.147:18081`
- Parent console static files: `web/`
- Parent console reverse proxy: `web-nginx.conf`
- PostgreSQL data: `data/postgres`
- Secrets: `secrets/` with directory mode `0700` and file mode `0444`
- Server distribution: `app/`
- Rollback copies: `backups/`
- Incoming update archives: `incoming/`

The full Caddy/LiveKit stack is intentionally not included because NAS ports
`80` and `443` are already occupied. The API is exposed on `18080`. The web
container serves the parent console on `18081` and proxies `/api/*` to the API
container, keeping browser requests same-origin. The console is intended for
LAN or routed VPN access and does not publish itself to the internet.

## PWA installation and HTTPS

Browsers only allow a parent console PWA to be installed from a secure
context. On the plain-HTTP LAN address Edge offers "Create shortcut" but not
"Install app". This is a browser requirement, not a Guard or network-provider
dependency. Install the PWA from the public HTTPS deployment, or place the
console behind any trusted HTTPS reverse proxy.

## Initial install

1. Extract the latest `guard-*.zip` server distribution into `app/` so that
   `app/bin/guard-server` exists.
2. Create `.env` from `.env.example`.
3. Generate the PostgreSQL password, JWT secret, and policy key pair once. Keep
   these files backed up; replacing the policy key changes the APK trust identity.
4. Build the parent console and place its production output in `web/`.
5. Build, start, and verify the API and web console.
6. Install the systemd unit so the stack starts at boot.

`GUARD_DEBIAN_MIRROR` controls the Debian package mirror used while building the
small Java runtime image. It defaults to Aliyun because the NAS reaches that
mirror much faster than `deb.debian.org`; change it in `.env` if needed. The
HTTP mirror is safe here because APT still verifies the signed package indexes.

```sh
cd /volume2/workspace/service/guard-product
./scripts/generate-secrets.sh
docker compose up -d --build
curl -fsS http://127.0.0.1:18080/healthz
curl -fsS http://127.0.0.1:18081/manifest.webmanifest
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
