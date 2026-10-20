# L&C — backend de laboratorio
- Solo datos ficticios. Escucha únicamente en 127.0.0.1:5055 (no LAN, sin túneles).
- Independiente de `~/LC/server` (histórico, sin tocar; copia de referencia en `referencia_historica_server/`).
- Arranque: `python3 run_lab.py` · Pruebas: `python3 -m pytest -q tests`
- `instance/` (permisos 700): `lab.db`, `uploads/`, `lab_config.json` (claves), `credenciales_lab.txt` (600).
