#!/usr/bin/env python3
"""
Recuperación del historial que el espejo se saltó.

Contexto: cuando Supabase respondía 404 en `job_history`, `supabase_mirror._historial()` avanzaba el
cursor y continuaba, así que esas filas **nunca se replicaron** y el cursor ya pasó de largo. Las filas
siguen intactas en el SQLite del LAB; este útil las vuelve a empujar preservando su `id` original.

Por qué es seguro:
  - No toca el cursor del espejo.
  - Envía con `on_conflict=id` + `resolution=ignore-duplicates`: lo que ya exista NO se modifica.
  - No borra ni actualiza nada, ni local ni remoto.
  - Por defecto sólo SIMULA (dry-run). Escribir requiere `--apply`.

Uso:
    python3 tools/backfill_job_history.py <ruta/instance/lab.db> [--apply] [--lote 200]
"""
import argparse
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lab", "backend"))

LOTE = 200


def _filas(c, desde_id, lote, jobs_seq):
    """Mismas condiciones que el espejo, pero sin cursor: todo el historial hasta el último job replicado."""
    return c.execute(
        "SELECT h.* FROM job_history h JOIN jobs j ON j.id = h.job_id "
        "WHERE h.id > ? AND j.seq <= ? ORDER BY h.id LIMIT ?",
        (desde_id, jobs_seq, lote)).fetchall()


def _payload(h):
    return {"id": h["id"], "job_id": h["job_id"], "version": h["version"], "field": h["field"],
            "old_value": h["old_value"], "new_value": h["new_value"],
            "source": f"{h['source']}:{h['actor']}", "ts": h["ts"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--apply", action="store_true", help="escribir en Supabase (sin esto sólo simula)")
    ap.add_argument("--lote", type=int, default=LOTE)
    args = ap.parse_args()

    if not os.path.exists(args.db):
        sys.exit(f"no existe la base: {args.db}")

    c = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    c.row_factory = sqlite3.Row
    jobs_seq = (c.execute("SELECT value FROM supabase_mirror_state WHERE name='jobs_seq'").fetchone()
                or {"value": 0})["value"] if _tabla(c) else 0

    total = c.execute("SELECT COUNT(*) n FROM job_history").fetchone()["n"]
    replicables = c.execute("SELECT COUNT(*) n FROM job_history h JOIN jobs j ON j.id=h.job_id "
                            "WHERE j.seq <= ?", (jobs_seq,)).fetchone()["n"]
    pendientes = c.execute("SELECT COUNT(*) n FROM job_history h JOIN jobs j ON j.id=h.job_id "
                           "WHERE j.seq > ?", (jobs_seq,)).fetchone()["n"]
    print(f"historial local      : {total} filas")
    print(f"de órdenes ya espejadas (replicables): {replicables}")
    print(f"de órdenes aún sin espejar (quedan para el espejo): {pendientes}")
    print(f"jobs_seq             : {jobs_seq}")

    if not args.apply:
        muestra = _filas(c, 0, 5, jobs_seq)
        print("\nSIMULACIÓN (dry-run). Primeras filas que se enviarían:")
        for h in muestra:
            print("  ", json.dumps(_payload(h), ensure_ascii=False)[:160])
        print("\nPara escribir de verdad: --apply  (requiere instance/supabase_sync.json)")
        return 0

    from lc_lab.supabase_mirror import Espejo, leer_config   # import tardío: sólo si se aplica
    base = os.path.dirname(os.path.dirname(os.path.abspath(args.db)))
    cfg = leer_config(base, os.path.join(base, "..", "local.properties"))
    if cfg is None:
        sys.exit("falta instance/supabase_sync.json (url, publishable_key, email, password)")

    esp = Espejo(args.db, cfg)
    enviados, errores, cursor = 0, 0, 0
    while True:
        filas = _filas(c, cursor, args.lote, jobs_seq)
        if not filas:
            break
        lote = [_payload(h) for h in filas]
        st, r = esp.sb.insertar("job_history", lote, "id")
        if st not in (200, 201, 204):
            errores += 1
            print(f"  lote {filas[0]['id']}..{filas[-1]['id']} → HTTP {st}: {str(r)[:120]}")
            if errores >= 3:
                sys.exit("demasiados errores seguidos: se detiene sin tocar nada más")
        else:
            enviados += len(lote)
            print(f"  lote {filas[0]['id']}..{filas[-1]['id']} → OK ({len(lote)})")
        cursor = filas[-1]["id"]

    print(f"\nreenviadas: {enviados} · lotes con error: {errores}")
    print("verificación: en Supabase, select count(*), min(id), max(id) from job_history;")
    return 0


def _tabla(c):
    return c.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='supabase_mirror_state'"
                     ).fetchone() is not None


if __name__ == "__main__":
    sys.exit(main())
