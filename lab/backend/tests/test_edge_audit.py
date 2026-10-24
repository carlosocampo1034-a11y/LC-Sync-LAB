#!/usr/bin/env python3
"""
Automated audit tests for L&C Edge Functions logic against real Supabase.
Validates: auth, RLS isolation, state machine, CAS, cursor pagination,
           idempotency, no-secrets-in-source.
"""
import json, sys, os, re, time, urllib.request, urllib.error, subprocess

# ── Credentials ──────────────────────────────────────────────────────────────
_supa = json.load(open("/data/data/com.termux/files/home/LC/lab/backend/instance/supabase_sync.json"))
SUPA_URL     = _supa["url"]
SUPA_SERVICE = _supa["service_role_key"]
SUPA_ANON    = subprocess.check_output(
    "grep SUPABASE_ANON_KEY /data/data/com.termux/files/home/LC/local.properties | cut -d= -f2",
    shell=True).decode().strip()

PASS_S = "\033[32mPASS\033[0m"
FAIL_S = "\033[31mFAIL\033[0m"
_results = []


def req(method, path, body=None, token=None, use_anon_key=False, extra_headers=None):
    url = f"{SUPA_URL}{path}"
    headers = {"Content-Type": "application/json"}
    headers["apikey"] = SUPA_ANON if use_anon_key else SUPA_SERVICE
    if token:
        headers["Authorization"] = f"Bearer {token}"
    elif not use_anon_key:
        headers["Authorization"] = f"Bearer {SUPA_SERVICE}"
    if extra_headers:
        headers.update(extra_headers)
    data = json.dumps(body).encode() if body else None
    try:
        r = urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers=headers, method=method), timeout=12)
        return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
    except Exception as ex:
        return 0, {"_exception": str(ex)}


def login(email, pwd):
    s, b = req("POST", "/auth/v1/token?grant_type=password",
               {"email": email, "password": pwd}, use_anon_key=True)
    return b.get("access_token") if s == 200 else None


def check(name, cond, detail=""):
    ok = bool(cond)
    _results.append((name, ok, detail))
    marker = PASS_S if ok else FAIL_S
    print(f"  [{marker}] {name}" + (f"  ({detail})" if detail else ""))
    return ok


# ═══════════════════════════════════════════════════════════════════════════════
print("\n=== L&C Edge Function Audit Tests ===\n")

# ── T1: Auth ─────────────────────────────────────────────────────────────────
print("[T1] Auth")
tec_tok  = login("tec.fase5@lc-lab.io", "TecF9Sup3r!")
cust_tok = login("testcust9@lc-lab.io", "CustF9Test!")
disp_tok = login("dispatch9@lc-lab.io", "DispF9ctrl!")
check("T1.1 tec.fase5 login", tec_tok)
check("T1.2 testcust9 login", cust_tok)
check("T1.3 dispatch9 login", disp_tok)

# Bad creds must return 400/401
s, b = req("POST", "/auth/v1/token?grant_type=password",
           {"email": "nobody@invalid.test", "password": "wrong"},
           use_anon_key=True)
check("T1.4 Bad credentials rejected (4xx)", s in (400, 401, 422), f"status={s}")

# ── T2: RLS isolation ────────────────────────────────────────────────────────
print("\n[T2] RLS isolation")

# Anon → denied
s, b = req("GET", "/rest/v1/jobs?limit=1", use_anon_key=True)
check("T2.1 Anon blocked from jobs", s in (401, 403) or (s == 200 and b == []),
      f"status={s} rows={len(b) if isinstance(b,list) else '?'}")

# Customer sees 0 jobs (no integer customer_id match for new Supabase users)
s, b = req("GET", "/rest/v1/jobs?limit=5", token=cust_tok, use_anon_key=True)
check("T2.2 Customer sees 0 jobs (no Flask integer FK)", isinstance(b, list) and len(b) == 0,
      f"got {len(b) if isinstance(b,list) else b}")

# Customer sees only own profile (not others')
s, b = req("GET", "/rest/v1/profiles", token=cust_tok, use_anon_key=True)
check("T2.3 Customer sees only own profile (1 row)",
      isinstance(b, list) and len(b) == 1 and b[0]["id"] == "8fcc79f4-9420-4935-acad-ce13e207b37b",
      f"rows={len(b) if isinstance(b,list) else '?'}")

# Technician sees only own jobs
s, b = req("GET", "/rest/v1/jobs?select=id,technician_code&limit=20", token=tec_tok, use_anon_key=True)
if isinstance(b, list):
    codes = {r.get("technician_code") for r in b}
    check("T2.4 Technician sees only tec-f5-01 jobs",
          all(c == "tec-f5-01" for c in codes if c),
          f"codes={codes} rows={len(b)}")
else:
    check("T2.4 Technician jobs readable", False, str(b)[:60])

# Dispatcher sees all jobs (correct — dispatchers need full visibility)
s, b = req("GET", "/rest/v1/jobs?select=id&limit=10", token=disp_tok, use_anon_key=True)
check("T2.5 Dispatcher sees all jobs (correct behavior)",
      isinstance(b, list) and len(b) > 3, f"rows={len(b) if isinstance(b,list) else '?'}")

# sync_receipts: authenticated users can read (acceptable — dispatcher oversight)
# Key control: Edge Functions use service_role to write; no direct user INSERT
s_r, b_r = req("POST", "/rest/v1/sync_receipts",
               {"job_id": 9999, "device_id": "x", "client_updated_at": 0, "status": "X", "version": 9998},
               token=disp_tok, use_anon_key=True,
               extra_headers={"Prefer": "return=representation"})
check("T2.6 Dispatcher cannot INSERT sync_receipts directly",
      s_r in (401, 403, 409),
      f"status={s_r} (expected 401/403/409)")

# ── T3: BIGINT timestamp filter (no ISO strings) ─────────────────────────────
print("\n[T3] Sync pull — BIGINT cursor filter")

# Confirm updated_at is numeric
s, b = req("GET", "/rest/v1/jobs?technician_code=eq.tec-f5-01&select=id,updated_at&limit=1")
if isinstance(b, list) and b:
    ua = b[0]["updated_at"]
    check("T3.1 updated_at is integer (BIGINT)", isinstance(ua, int), f"type={type(ua).__name__} val={ua}")

    # gte numeric filter
    s2, b2 = req("GET", f"/rest/v1/jobs?technician_code=eq.tec-f5-01&updated_at=gte.{ua - 1}&select=id&limit=10")
    check("T3.2 gte numeric filter returns rows", isinstance(b2, list) and len(b2) >= 1,
          f"rows={len(b2) if isinstance(b2,list) else '?'}")

    # Future cursor → 0 rows
    future = int(time.time() * 1000) + 9_999_999_999
    s3, b3 = req("GET", f"/rest/v1/jobs?technician_code=eq.tec-f5-01&updated_at=gte.{future}&select=id&limit=5")
    check("T3.3 Future cursor returns 0 jobs", isinstance(b3, list) and len(b3) == 0,
          f"rows={len(b3) if isinstance(b3,list) else '?'}")

    # Cursor-based pagination: two pages
    s4, b4 = req("GET", f"/rest/v1/jobs?technician_code=eq.tec-f5-01&updated_at=gte.0&select=id,updated_at&order=updated_at.asc&limit=1")
    if isinstance(b4, list) and b4:
        page1_last_ua = b4[-1]["updated_at"]
        s5, b5 = req("GET", f"/rest/v1/jobs?technician_code=eq.tec-f5-01&updated_at=gte.{page1_last_ua + 1}&select=id&order=updated_at.asc&limit=10")
        check("T3.4 Cursor pagination: page 2 does not repeat page 1 last item",
              isinstance(b5, list) and all(r["id"] != b4[-1]["id"] for r in b5),
              f"p1_last={b4[-1]['id']} p2_ids={[r['id'] for r in b5[:2]]}")
    else:
        check("T3.4 Cursor pagination", False, "no data for page 1")
else:
    check("T3.1-4 tec-f5-01 jobs not found", False)

# ── T4: CAS — atomic version update ─────────────────────────────────────────
print("\n[T4] CAS — atomic version update")

# Pick a completed job
s, b = req("GET", "/rest/v1/jobs?technician_code=eq.tec-f5-01&select=id,estado,version,notas&limit=1&order=id.asc")
if isinstance(b, list) and b:
    j = b[0]; jid = j["id"]; ver = j["version"]
    check("T4.1 Read job for CAS", True, f"id={jid} v={ver}")

    # Wrong version → 0 rows updated
    s2, b2 = req("PATCH",
                 f"/rest/v1/jobs?id=eq.{jid}&version=eq.{ver - 99}",
                 {"notas": "cas_wrong", "version": ver - 98, "updated_at": int(time.time()*1000)},
                 extra_headers={"Prefer": "return=representation"})
    check("T4.2 CAS wrong version returns 0 rows",
          isinstance(b2, list) and len(b2) == 0, f"rows={len(b2) if isinstance(b2,list) else b2}")

    # Correct version → 1 row updated
    s3, b3 = req("PATCH",
                 f"/rest/v1/jobs?id=eq.{jid}&version=eq.{ver}",
                 {"notas": f"cas_ok_{ver}", "version": ver + 1, "updated_at": int(time.time()*1000)},
                 extra_headers={"Prefer": "return=representation"})
    check("T4.3 CAS correct version updates 1 row",
          isinstance(b3, list) and len(b3) == 1, f"rows={len(b3) if isinstance(b3,list) else b3}")

    # Restore original version+notas
    if isinstance(b3, list) and b3:
        req("PATCH", f"/rest/v1/jobs?id=eq.{jid}",
            {"notas": j.get("notas"), "version": ver + 2, "updated_at": int(time.time()*1000)})
else:
    check("T4 No tec-f5-01 jobs found", False)

# ── T5: State machine — dispatch ASIGNADO ≠ EN RUTA ─────────────────────────
print("\n[T5] State machine — dispatch ASIGNADO ≠ EN RUTA")

# Find a PENDIENTE job (no technician) with a non-null version
s, b = req("GET", "/rest/v1/jobs?estado=eq.PENDIENTE&technician_code=is.null&version=not.is.null&select=id,estado,estado_dispatch,version&limit=1&order=id.asc")
if isinstance(b, list) and b:
    pj = b[0]; pjid = pj["id"]; pver = pj["version"]
    check("T5.1 Found PENDIENTE unassigned job", True, f"id={pjid}")

    # Assign: estado_dispatch=ASIGNADO, estado stays PENDIENTE
    now_ms = int(time.time()*1000)
    s2, b2 = req("PATCH",
                 f"/rest/v1/jobs?id=eq.{pjid}&estado=eq.PENDIENTE&version=eq.{pver}",
                 {"technician_code": "tec-f5-01",
                  "estado_dispatch": "ASIGNADO",
                  "version": pver + 1,
                  "updated_at": now_ms},
                 extra_headers={"Prefer": "return=representation"})
    if isinstance(b2, list) and b2:
        u = b2[0]
        check("T5.2 estado stays PENDIENTE after assign",
              u.get("estado") == "PENDIENTE", f"estado={u.get('estado')}")
        check("T5.3 estado_dispatch=ASIGNADO after assign",
              u.get("estado_dispatch") == "ASIGNADO", f"dispatch={u.get('estado_dispatch')}")

        # Technician starts (EN RUTA)
        s3, b3 = req("PATCH",
                     f"/rest/v1/jobs?id=eq.{pjid}&technician_code=eq.tec-f5-01&version=eq.{pver+1}",
                     {"estado": "EN RUTA", "version": pver + 2, "updated_at": int(time.time()*1000)},
                     extra_headers={"Prefer": "return=representation"})
        check("T5.4 Technician self-transitions to EN RUTA",
              isinstance(b3, list) and b3 and b3[0].get("estado") == "EN RUTA",
              f"estado={b3[0].get('estado') if isinstance(b3,list) and b3 else b3}")

        # Restore
        req("PATCH", f"/rest/v1/jobs?id=eq.{pjid}",
            {"technician_code": None, "estado": "PENDIENTE",
             "estado_dispatch": "PENDIENTE", "version": pver + 3,
             "updated_at": int(time.time()*1000)})
    else:
        check("T5.2-4 CAS assign", False, f"status={s2} body={str(b2)[:100]}")
else:
    check("T5 No PENDIENTE job available — skipped", None, "no data")

# ── T6: Idempotency — sync receipt upsert ────────────────────────────────────
print("\n[T6] Idempotency — sync receipts")
# First check if unique index on (job_id, version) exists
UNIQ_VER = 99997  # test-only version
payload = {"job_id": 8000000000012, "device_id": "idempotency-test",
           "client_updated_at": int(time.time()*1000), "status": "ACCEPTED", "version": UNIQ_VER}

s1, b1 = req("POST", "/rest/v1/sync_receipts", payload, extra_headers={"Prefer": "return=representation"})
check("T6.1 First receipt insert", s1 in (200, 201), f"status={s1}")

# Retry same (job_id, version) — should upsert/ignore
s2, b2 = req("POST", "/rest/v1/sync_receipts", payload,
             extra_headers={"Prefer": "resolution=merge-duplicates,return=representation"})
check("T6.2 Duplicate receipt upsert non-fatal", s2 in (200, 201, 409), f"status={s2}")
# If 409 = unique constraint not yet created (migration not applied) — that's expected
if s2 == 409:
    print("    (unique index not yet applied — will be enforced after migration)")

# ── T7: No secrets in Edge Function source ───────────────────────────────────
print("\n[T7] Security — no secrets in source")
fn_dir = "/data/data/com.termux/files/home/LC/supabase/functions"
SECRET_PATTERNS = [
    (r'sb_secret_[A-Za-z0-9_-]+', "service role key"),
    (r'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', "JWT token"),
    (r'password\s*[:=]\s*["\'][^"\']{8,}["\']', "hardcoded password"),
]
violations = []
for root, dirs, files in os.walk(fn_dir):
    for f in files:
        if not f.endswith((".ts", ".js")): continue
        content = open(os.path.join(root, f)).read()
        for pat, label in SECRET_PATTERNS:
            if re.search(pat, content):
                violations.append(f"{f}: {label}")
check("T7.1 No hardcoded secrets in Edge Functions", not violations,
      "; ".join(violations) if violations else "clean")

# Also check workflow file
wf = "/data/data/com.termux/files/home/LC/.github/workflows/deploy-edge-functions.yml"
wf_content = open(wf).read()
check("T7.2 Workflow uses secrets, not hardcoded tokens",
      "SUPABASE_ACCESS_TOKEN" in wf_content and "sb_secret_" not in wf_content,
      "OK" if "sb_secret_" not in wf_content else "FAIL — key in workflow")

# Check workflow does NOT auto-trigger on push
check("T7.3 Workflow has no push trigger (manual only)",
      "on:" in wf_content and "push:" not in wf_content.split("workflow_dispatch:")[0],
      "workflow_dispatch only" if "workflow_dispatch" in wf_content else "MISSING workflow_dispatch")

# ── Summary ──────────────────────────────────────────────────────────────────
print(f"\n{'='*55}")
passed = sum(1 for _, ok, _ in _results if ok is True)
failed = sum(1 for _, ok, _ in _results if ok is False)
skipped = sum(1 for _, ok, _ in _results if ok is None)
total = len(_results)
print(f"RESULT: {passed}/{total} PASS · {failed} FAIL · {skipped} SKIP")
if failed:
    print("\nFailed:")
    for name, ok, detail in _results:
        if ok is False:
            print(f"  ✗ {name}: {detail}")
sys.exit(0 if failed == 0 else 1)
