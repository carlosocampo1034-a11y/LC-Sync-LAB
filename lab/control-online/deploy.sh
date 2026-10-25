#!/usr/bin/env bash
# Publica el Control QA en Cloudflare Workers (archivos estáticos). Uso: ./deploy.sh <fichero-con-clave-publicable>
set -euo pipefail
cd "$(dirname "$0")"
KEY=$(tr -d '[:space:]' < "${1:?fichero con la clave publicable de LC-Sync-QA}")
case "$KEY" in sb_publishable_*|eyJ*) ;; *) echo "clave no reconocida"; exit 1;; esac
rm -rf dist && cp -r public dist
sed -i "s|__QA_PUBLISHABLE_KEY__|$KEY|" dist/config.js
# claves reales, no la palabra: sb_secret_… o un JWT (las claves antiguas eran JWT; la publicable QA no lo es)
grep -qE 'sb_secret_[A-Za-z0-9]|eyJ[A-Za-z0-9_-]{10,}\.eyJ' dist/* && { echo "secreto detectado en dist"; exit 1; }
npx -y wrangler@4.45.0 deploy
