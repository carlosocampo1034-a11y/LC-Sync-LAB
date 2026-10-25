# L&C Control QA

Consola de despacho para LC-Sync-QA sin el servidor Flask: Supabase Auth (cuenta DISPATCHER/ADMIN),
lc-api para leer y escribir, Realtime sobre `jobs` (RLS) con recuperación por `?since=` al reconectar.

    cp config.example.js config.js   # poner la clave publicable del proyecto QA
    python3 -m http.server 8099 --bind 127.0.0.1

Abrir http://127.0.0.1:8099/ . `config.js` está en .gitignore.
