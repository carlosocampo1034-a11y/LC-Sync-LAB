#!/usr/bin/env python3
"""Comprueba que los valores de RESPALDO escritos en el código (Cliente, Técnico, Control Web) coinciden con los
VALORES INICIALES de la tabla settings (migraciones SQL). Sin secretos ni red: solo lee archivos. Sale con código 1 si algo difiere."""
import json, re, sys, pathlib

R = pathlib.Path(__file__).resolve().parent.parent
MIG = sorted((R / "supabase/migrations").glob("*.sql"))

def uni(s):  # á → á
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)

# ── 1) Valores iniciales: de las migraciones, en orden (las posteriores pisan a las anteriores)
listas, numeros = {}, {}
for f in MIG:
    t = f.read_text(encoding="utf-8")
    for k, v in re.findall(r"\('([a-z_]+\.list)',\s*'(\[.*?\])',\s*'", t, re.S):
        listas[k] = json.loads(v)
    for m in re.finditer(r"update public\.settings set\s+value = '(\[.*?\])'::jsonb.*?where key = '([a-z_.]+)'", t, re.S):
        listas[m.group(2)] = json.loads(m.group(1))
    for k, v in re.findall(r"\('(pricing\.[a-z_.]+)',\s*'([0-9.]+)',\s*'number'", t):
        numeros[k] = float(v)

# actualizaciones puntuales de textos para el cliente: when 'ID' then o || '{"customer_label":"TEXTO"}'::jsonb
for f in MIG:
    t = f.read_text(encoding="utf-8")
    if "order_states.list" in t and "jsonb_agg" in t:
        for sid, txt in re.findall(r"when '([^']+)' then o \|\| '\{\"customer_label\":\"([^\"]+)\"\}'::jsonb", t):
            for o in listas.get("order_states.list", []):
                if o["id"] == sid: o["customer_label"] = txt

def activos(k): return [o for o in listas[k] if o.get("active", True)]

# ── 2) Utilidades para leer arreglos Java/JS
def llaves(txt, ini):
    """texto balanceado desde la '{' en ini"""
    d = 0
    for i in range(ini, len(txt)):
        if txt[i] == "{": d += 1
        elif txt[i] == "}":
            d -= 1
            if d == 0: return txt[ini:i + 1]
    return ""

STR = r'"((?:[^"\\]|\\.)*)"'
def filas(cuerpo):
    out = []
    for m in re.finditer(r"\{\s*" + STR + r"(?:\s*,\s*" + STR + r")*\s*\}", cuerpo):
        out.append([uni(x) for x in re.findall(STR, m.group(0))])
    return out

resultados = []
def comparar(origen, clave, esperado, obtenido):
    ok = esperado == obtenido
    resultados.append((ok, origen, clave, esperado, obtenido))

# ── 3) Cliente: Ajustes.java
aj = (R / "customer_app/app/src/main/java/com/lc/customer/Ajustes.java").read_text(encoding="utf-8")
def arr_java(txt, nombre):
    m = re.search(r"String\[\]\[\]\s+" + nombre + r"\s*=\s*\{", txt)
    return filas(llaves(txt, m.end() - 1)) if m else []
for nombre, clave, cols in [("CATEGORIAS", "categories.list", ("id", "label", "customer_label", "price_group")),
                            ("TIPOS_PROPIEDAD", "property_types.list", ("id", "label")), ("ACCESOS", "access_types.list", ("id", "label")),
                            ("ESTADOS", "order_states.list", ("id", "label", "customer_label"))]:
    esp = [[o.get(c, "") for c in cols] for o in activos(clave)]
    obt = [r[:len(cols)] + [""] * (len(cols) - len(r)) for r in arr_java(aj, nombre)]
    if clave == "categories.list": obt = [[r[0], r[1], "", r[3] if len(r) > 3 else ""] for r in arr_java(aj, nombre)]
    comparar("Cliente/Ajustes.java " + nombre, clave, esp, obt)
precios_cli = dict((m.group(1), float(m.group(2))) for m in re.finditer(r'case "([a-z]+\.[a-z_]+)"(?:: case "[a-z]+\.[a-z_]+")*:\s*resp\s*=\s*([0-9.]+)', aj))
for m in re.finditer(r'((?:case "[a-z]+\.[a-z_]+":\s*)+)resp\s*=\s*([0-9.]+)', aj):
    for k in re.findall(r'case "([a-z]+\.[a-z_]+)"', m.group(1)): precios_cli[k] = float(m.group(2))
for k, v in precios_cli.items(): comparar("Cliente/Ajustes.java precio()", "pricing." + k, numeros.get("pricing." + k), v)

# ── 4) Técnico: Listas.lista(this, "clave", new String[][]{...}, vacio) en todos los .java
vistos = set()
for f in (R / "app/src/main/java").rglob("*.java"):
    t = f.read_text(encoding="utf-8")
    for m in re.finditer(r'Listas\.lista\(\s*[A-Za-z.]+\s*,\s*"([a-z_.]+)"\s*,\s*new String\[\]\[\]\s*\{', t):
        clave = m.group(1)
        pares = [r[:2] for r in filas(llaves(t, m.end() - 1))]
        if clave in listas:
            comparar("Técnico/" + f.name, clave, [[o["id"], o["label"]] for o in activos(clave)], pares)
            vistos.add(clave)
# acciones del menú (arreglo estático)
jd = (R / "app/src/main/java/com/lc/technician/JobDetailActivity.java").read_text(encoding="utf-8")
m = re.search(r"ACCIONES_RESPALDO\s*=\s*\{", jd)
comparar("Técnico/JobDetailActivity ACCIONES_RESPALDO", "job_actions.list", [[o["id"], o["label"]] for o in activos("job_actions.list")], [r[:2] for r in filas(llaves(jd, m.end() - 1))])
# tarifas y márgenes de respaldo
ls = (R / "app/src/main/java/com/lc/technician/sync/Listas.java").read_text(encoding="utf-8")
for grupo_c, v in re.findall(r'case "([a-z]+\.[a-z_]+)"[^;]*?resp\s*=\s*([0-9.]+)', ls):
    comparar("Técnico/Listas.precio()", "pricing." + grupo_c, numeros.get("pricing." + grupo_c), float(v))
pa = (R / "app/src/main/java/com/lc/technician/ProposalActivity.java").read_text(encoding="utf-8")
for k in ("pricing.markup_percent", "pricing.tax_percent"):
    m = re.search(r'numero\(this,\s*"' + re.escape(k) + r'",\s*([0-9.]+)\)', pa)
    comparar("Técnico/ProposalActivity", k, numeros.get(k), float(m.group(1)) if m else None)

# ── 5) Control Web: estados de respaldo
js = (R / "lab/control-online/public/app.js").read_text(encoding="utf-8")
m = re.search(r"ESTADOS_RESPALDO\s*=\s*\[(.*?)\]\s*\.map", js, re.S)
pares = re.findall(r'\["([^"]+)",\s*"([^"]+)"\]', m.group(1)) if m else []
comparar("Control/app.js ESTADOS_RESPALDO", "order_states.list", [[o["id"], o["label"]] for o in activos("order_states.list")], [list(p) for p in pares])

# ── 6) Informe
mal = [r for r in resultados if not r[0]]
for ok, origen, clave, esp, obt in resultados:
    print(("PASS " if ok else "FAIL ") + f"{clave:34} ← {origen}")
    if not ok: print(f"     esperado (settings): {esp}\n     obtenido (código):   {obt}")
print(f"\n{len(resultados) - len(mal)}/{len(resultados)} respaldos coinciden con los valores iniciales de settings")
sys.exit(1 if mal else 0)
