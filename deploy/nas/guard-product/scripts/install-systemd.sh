#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
UNIT=/etc/systemd/system/guard-product.service

ln -sfn "$ROOT/systemd/guard-product.service" "$UNIT"
systemctl daemon-reload
systemctl enable --now guard-product.service
systemctl --no-pager --full status guard-product.service
