# L&C Control ORIGINAL · QA online

Copia QA del Control original (`lab/backend/lc_lab/templates/control_*.html`, mismo diseño blanco/rojo y mismos
módulos) sin Flask ni SQLite: el backend es Supabase LC-Sync-QA (Auth + lc-api + Realtime).

- `public/`: página estática (index.html, control.css copiado de las plantillas, app.js, config.js, _headers).
- `config.js` lleva un marcador; `deploy.sh` copia `public/` a `dist/` con la clave PUBLICABLE de QA y publica
  en Cloudflare Workers (archivos estáticos, gratis) → https://lc-control-qa.<cuenta>.workers.dev
- Acceso: solo cuentas ADMIN/DISPATCHER activas de LC-Sync-QA; lc-api valida rol en cada petición.
