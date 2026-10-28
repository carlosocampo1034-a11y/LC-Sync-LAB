/* L&C Control ORIGINAL · versión online (QA).
 * Mismas pantallas que lc_lab/web_control.py + templates/control_*.html, con Supabase LC-Sync-QA como backend:
 *   sesión  → Supabase Auth (solo ADMIN/DISPATCHER activos; lc-api lo comprueba en cada petición)
 *   datos   → lc-api (Edge Function): las escrituras son transaccionales y con control de versión
 *   tiempo real → canal postgres_changes de jobs y notifications (RLS del usuario)
 * Todo lo que viene del servidor se escapa antes de pintarlo. */
(() => {
  "use strict";
  const CFG = window.LC_CONFIG;
  const TZ = CFG.zonaHoraria;
  const API = CFG.supabaseUrl + "/functions/v1/lc-api";
  const sb = window.supabase.createClient(CFG.supabaseUrl, CFG.publishableKey, {
    auth: { persistSession: true, autoRefreshToken: true, storageKey: "lc-control-qa" },
  });
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const money = (n) => "$" + Number(n || 0).toFixed(2);

  // jobs.py del Control original
  // Estados de la orden: ids fijos (llevan lógica); el TEXTO y el orden salen de settings order_states.list (editable en Listas editables).
  // Respaldo = valor inicial de la tabla (supabase/migrations/20261028160000_settings.sql).
  const ESTADOS_RESPALDO = [["PENDIENTE", "Pendiente"], ["EN RUTA", "En ruta"], ["LLEGUÉ", "Llegué"], ["EN REPARACIÓN", "En reparación"],
    ["ESPERANDO APROBACIÓN", "Esperando aprobación"], ["PART HOLD", "Falta repuesto"], ["PAUSADO", "Pausado"], ["COMPLETADO", "Completado"], ["CANCELADO", "Cancelado"]]
    .map(([id, label]) => ({ id, label, active: true }));
  let CFG_ESTADOS = null, cfgAt = 0;
  const estadosCfg = () => CFG_ESTADOS || ESTADOS_RESPALDO;
  const etiquetaEstado = (id) => (estadosCfg().find((o) => o.id === id) || {}).label || id;
  const EN_CURSO = new Set(["EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "ESPERANDO APROBACIÓN", "PART HOLD", "PAUSADO"]);
  const ETIQUETAS = [["EN_CURSO", "En curso"], ["HOY", "Hoy"], ["ATRASADOS", "Atrasados"], ["PROXIMOS", "Próximos"],
                     ["SIN_FECHA", "Sin fecha"], ["COMPLETADOS", "Completados"], ["CANCELADOS", "Cancelados"]];

  let yo = null;              // { id, role, email, display_name }
  let tecnicos = [];
  let canal = null;
  let vistaActual = null;     // función que repinta la vista visible (tiempo real)

  // ── utilidades de fecha (zona del negocio, como ZoneInfo en Flask) ───────────────────────────
  const partes = (ms, tz) => Object.fromEntries(new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23",
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" })
    .formatToParts(new Date(ms)).filter((p) => p.type !== "literal").map((p) => [p.type, +p.value]));
  const diaEn = (ms, tz) => { const p = partes(ms, tz); return p.year * 10000 + p.month * 100 + p.day; };
  function fecha(ms, zona) {
    if (!ms) return "—";
    return new Intl.DateTimeFormat("es-US", { timeZone: zona || TZ, weekday: "short", day: "2-digit", month: "short",
      year: "numeric", hour: "2-digit", minute: "2-digit", hour12: true }).format(new Date(Number(ms))).replace(",", " ·");
  }
  function preferencia(v) { return v && /^\d{12,}$/.test(String(v)) ? fecha(Number(v)) : v; }
  /** "2026-10-26" + "10:00" en la zona tz → epoch ms (como datetime(...).replace(tzinfo=ZoneInfo(zona))). */
  function aEpoch(fechaStr, horaStr, tz) {
    const [y, m, d] = fechaStr.split("-").map(Number), [hh, mm] = horaStr.split(":").map(Number);
    const guess = Date.UTC(y, m - 1, d, hh, mm);
    const off = (t) => { const p = partes(t, tz); return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - t; };
    let t = guess - off(guess);
    t = guess - off(t);
    return t;
  }
  const hora12 = (fechaStr, horaStr) => { const [hh, mm] = horaStr.split(":").map(Number);
    return `${((hh + 11) % 12) + 1}:${String(mm).padStart(2, "0")} ${hh < 12 ? "AM" : "PM"}`; };

  function categoriaAgenda(j, ahora = Date.now()) {
    if (EN_CURSO.has(j.estado)) return "EN_CURSO";
    if (j.estado === "COMPLETADO") return "COMPLETADOS";
    if (j.estado === "CANCELADO") return "CANCELADOS";
    if (j.programado_para == null) return "SIN_FECHA";
    const tz = j.zona_horaria || TZ, dia = diaEn(Number(j.programado_para), tz), hoy = diaEn(ahora, tz);
    return dia === hoy ? "HOY" : dia < hoy ? "ATRASADOS" : "PROXIMOS";
  }
  function dentroDeProximos(j, dias, ahora = Date.now()) {
    return j.programado_para != null && Number(j.programado_para) < ahora + (dias + 1) * 86400000;
  }

  // ── API ───────────────────────────────────────────────────────────────────────────────────
  async function api(method, path, body) {
    const { data: { session } } = await sb.auth.getSession();
    if (!session) { mostrarLogin(); throw new Error("sin sesión"); }
    let r;
    try {
      r = await fetch(API + path, { method, headers: { "content-type": "application/json", apikey: CFG.publishableKey,
        authorization: "Bearer " + session.access_token }, body: body ? JSON.stringify(body) : undefined });
    } catch (e) {
      return { status: 0, body: { error: "sin conexión" } };
    }
    let j = null; try { j = await r.json(); } catch (_) {}
    if (r.status === 401) { await sb.auth.signOut(); mostrarLogin("Tu sesión caducó; vuelve a entrar."); }
    if (r.status === 403 && j && j.error === "account_disabled") { await sb.auth.signOut(); mostrarLogin("Cuenta desactivada."); }
    return { status: r.status, body: j };
  }
  /** Descarga la lista de estados (como mucho una vez por minuto). */
  async function cargarCfg(forzar) {
    if (!forzar && Date.now() - cfgAt < 60000) return;
    cfgAt = Date.now();
    const r = await api("GET", "/settings/order_states.list");
    if (r.status === 200 && r.body?.settings?.[0] && Array.isArray(r.body.settings[0].value)) CFG_ESTADOS = r.body.settings[0].value;
  }
  const device = (() => { let d; try { d = localStorage.getItem("lc-control-device"); } catch (_) {}
    if (!d) { d = "control-web-" + crypto.randomUUID().slice(0, 8); try { localStorage.setItem("lc-control-device", d); } catch (_) {} }
    return d; })();
  const patch = (j, cambios) => api("PATCH", "/orders/" + j.id, { ...cambios, version: j.version, device, updatedAt: Date.now() });

  // ── layout ────────────────────────────────────────────────────────────────────────────────
  function alerta(texto, tipo = "success") {
    $("lcAlerts").innerHTML = texto ? `<div class="alert alert-${tipo} alert-dismissible fade show mb-2 rounded-3 border-0 shadow-sm">
      ${esc(texto)}<button type="button" class="btn-close" aria-label="Cerrar"></button></div>` : "";
  }
  $("lcAlerts").addEventListener("click", (e) => { if (e.target.classList.contains("btn-close")) alerta(""); });
  function cabecera(titulo, subtitulo, nav) {
    $("pageTitle").textContent = titulo;
    $("pageSubtitle").textContent = subtitulo ?? ("Actualizado " + fecha(Date.now()));
    document.querySelectorAll(".lc-sidebar a.nav-link").forEach((a) => a.classList.toggle("active", a.dataset.nav === nav));
    cerrarMenu();
  }
  const abrirMenu = () => { $("lcSidebar").classList.add("open"); $("lcOverlay").classList.add("open"); };
  const cerrarMenu = () => { $("lcSidebar").classList.remove("open"); $("lcOverlay").classList.remove("open"); };
  $("btnMenu").addEventListener("click", abrirMenu);
  $("lcOverlay").addEventListener("click", cerrarMenu);
  const badgeEstado = (e) => e === "COMPLETADO" ? "badge-completado" : e === "CANCELADO" ? "badge-cancelado"
    : EN_CURSO.has(e) ? "badge-en-curso" : "badge-pendiente";
  const cssEstado = (e) => e === "COMPLETADO" ? "completado" : e === "CANCELADO" ? "cancelado" : EN_CURSO.has(e) ? "en-curso" : "pendiente";
  const cargando = () => { $("lcBody").innerHTML = `<div class="empty-state"><div class="spinner-border text-danger" role="status"></div><p>Cargando…</p></div>`; };

  // ── TABLERO (control_board.html) ──────────────────────────────────────────────────────────
  async function vistaTablero() {
    cabecera("Dashboard", null, "tablero");
    const r = await api("GET", "/orders");
    if (r.status !== 200) return errorVista(r);
    const dias = 7, grupos = Object.fromEntries(ETIQUETAS.map(([k]) => [k, []]));
    const filas = r.body.slice().sort((a, b) => (a.programado_para ?? a.created_at) - (b.programado_para ?? b.created_at));
    for (const j of filas) {
      const cat = categoriaAgenda(j);
      if (cat === "PROXIMOS" && !dentroDeProximos(j, dias)) continue;
      grupos[cat].push(j);
    }
    const ordenes = ETIQUETAS.flatMap(([k]) => grupos[k]);
    const tarjeta = (o) => `
      <div class="order-card estado-${cssEstado(o.estado)}">
        <div class="order-top">
          <div class="order-service">${esc(o.categoria || (o.descripcion ? o.descripcion.slice(0, 42) : "Servicio General"))}</div>
          <span class="badge-estado ${badgeEstado(o.estado)}"><i class="bi bi-circle-fill" style="font-size:.45rem;"></i> ${esc(etiquetaEstado(o.estado || "PENDIENTE"))}</span>
        </div>
        <div class="order-meta">
          <span><i class="bi bi-person"></i> ${esc(o.cliente || "Sin cliente")}</span>
          <span><i class="bi bi-geo-alt"></i> ${esc((o.direccion || "Sin dirección").slice(0, 32))}</span>
          ${o.hora ? `<span><i class="bi bi-clock"></i> ${esc(o.hora)}</span>` : ""}
          <span><i class="bi bi-wrench"></i> ${o.technician_code ? esc(o.technician_code) : '<em style="color:#f59e0b;">Sin asignar</em>'}</span>
        </div>
        <div class="order-actions">
          <a href="#/jobs/${o.id}" class="btn-outline-sm btn-outline-primary-sm"><i class="bi bi-eye"></i> Ver</a>
          ${o.technician_code ? "" : `<a href="#/jobs/${o.id}" class="btn-outline-sm btn-outline-secondary-sm"><i class="bi bi-person-plus"></i> Asignar</a>`}
        </div>
      </div>`;
    const recientes = ordenes.slice().reverse().slice(0, 10);
    $("lcBody").innerHTML = `
      <div class="d-flex justify-content-between align-items-center mb-5">
        <div>
          <h2 class="fw-bold mb-1" style="font-size:1.35rem;color:#1a1d2e;">Agenda y Despacho</h2>
          <p class="mb-0" style="font-size:.82rem;color:#7b8099;">Gestión de órdenes de servicio en tiempo real</p>
        </div>
        <a href="#/nueva" class="btn-accent"><i class="bi bi-plus-lg"></i> Nueva Orden</a>
      </div>
      <div class="kpi-grid">
        ${[["blue", "bi-lightning-charge-fill", grupos.EN_CURSO.length, "Órdenes Activas"], ["amber", "bi-calendar-x", grupos.SIN_FECHA.length, "Sin Fecha"],
           ["green", "bi-check-circle-fill", grupos.COMPLETADOS.length, "Completados"], ["red", "bi-exclamation-triangle-fill", grupos.ATRASADOS.length, "Atrasados"]]
          .map(([c, i, n, l]) => `<div class="kpi-card"><div class="kpi-icon ${c}"><i class="bi ${i}"></i></div>
            <div class="kpi-info"><div class="kpi-num">${n}</div><div class="kpi-label">${l}</div></div></div>`).join("")}
      </div>
      <div class="board-grid">
        <div class="lc-card">
          <div class="section-header">
            <h3 class="section-title"><i class="bi bi-list-task me-2 text-muted"></i>Órdenes Pendientes</h3>
            <span class="section-count">${ordenes.length} total</span>
          </div>
          ${ordenes.length ? `<div class="order-list">${ordenes.map(tarjeta).join("")}</div>`
            : `<div class="empty-state"><i class="bi bi-inbox"></i><p>No hay órdenes en el sistema.<br>Crea la primera con el botón <strong>Nueva Orden</strong>.</p></div>`}
        </div>
        <div class="lc-card activity-panel">
          <div class="section-header">
            <h3 class="section-title"><i class="bi bi-activity me-2 text-muted"></i>Actividad Reciente</h3>
            <span class="section-count">Últimas ${Math.min(ordenes.length, 10)}</span>
          </div>
          ${recientes.length ? `<table class="activity-table"><thead><tr><th>ID</th><th>Servicio</th><th>Técnico</th><th>Estado</th></tr></thead><tbody>
            ${recientes.map((o) => `<tr>
              <td><a href="#/jobs/${o.id}" style="text-decoration:none;"><span class="job-id">#${String(o.id % 100000).padStart(5, "0")}</span></a></td>
              <td style="max-width:110px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${esc(o.categoria || (o.descripcion ? o.descripcion.slice(0, 20) : "—"))}</td>
              <td>${o.technician_code ? `<span class="tech-pill">${esc(o.technician_code)}</span>` : '<span style="color:#f59e0b;font-size:.72rem;">—</span>'}</td>
              <td><span class="badge-estado ${badgeEstado(o.estado)}">${esc(etiquetaEstado(o.estado || "PENDIENTE"))}</span></td></tr>`).join("")}
            </tbody></table>` : `<div class="empty-state"><i class="bi bi-clock-history"></i><p>Sin actividad reciente.</p></div>`}
        </div>
      </div>`;
  }

  // ── DETALLE (control_job.html) ────────────────────────────────────────────────────────────
  async function vistaDetalle(id, mensaje) {
    cabecera("Dashboard", null, "tablero");
    const [r, t, fr] = await Promise.all([api("GET", `/orders/${id}/detail`), tecnicos.length ? null : api("GET", "/technicians"), api("GET", `/orders/${id}/photos`)]);
    if (t && t.status === 200) tecnicos = t.body;
    if (r.status !== 200) return errorVista(r);
    const { job: j, historial, conflictos, descuentos, propiedad, cliente, factura, pagos, ofertas } = r.body;
    const fotos = fr && fr.status === 200 && Array.isArray(fr.body) ? fr.body : [];
    const info = (l, v) => `<div class="info-row"><span class="info-label">${l}</span><span class="info-value">${v}</span></div>`;
    const tipoProp = j.tipo_propiedad || propiedad?.tipo;
    $("lcBody").innerHTML = `<div class="job-page">
      <div class="d-flex align-items-center gap-3 mb-4">
        <a href="#/" class="btn btn-sm btn-outline-secondary d-flex align-items-center gap-1"><i class="bi bi-arrow-left"></i> Agenda</a>
        <div class="flex-grow-1" style="min-width:0">
          <h4 class="mb-0 fw-bold text-truncate">${esc(j.cliente || "Orden sin nombre")}</h4>
          <span class="text-muted" style="font-size:.82rem">Orden #${j.id} &nbsp;·&nbsp; v${j.version}</span>
        </div>
        <span class="badge badge-${esc(j.estado.toLowerCase().replaceAll(" ", "-"))} px-3 py-2" style="font-size:.8rem">${esc(etiquetaEstado(j.estado))}</span>
      </div>
      ${mensaje ? `<div class="alert ${mensaje.ok === false ? "alert-danger" : "alert-success"} d-flex align-items-center gap-2 mb-3">
        <i class="bi bi-${mensaje.ok === false ? "exclamation-triangle" : "check-circle"}-fill"></i> ${esc(mensaje.texto)}</div>` : ""}
      ${conflictos.length ? `<div class="lc-card mb-4 p-3" style="border-left: 4px solid #dc2626;">
        <div class="d-flex align-items-center gap-2 mb-3"><i class="bi bi-exclamation-triangle-fill text-danger"></i>
          <span class="fw-bold text-danger">${conflictos.length} conflicto(s) pendiente(s) de resolución</span></div>
        ${conflictos.map((c) => `<div class="conflict-card" data-conflicto="${c.id}">
          <div class="fw-semibold mb-1">${esc(c.field)} <span class="text-muted fw-normal" style="font-size:.82rem">&nbsp;· técnico ${esc(c.actor_nombre)} · base v${c.base_version}</span></div>
          <div class="conflict-values">
            <div class="conflict-val"><div class="conflict-val-label">Centro de mando</div><div class="conflict-val-text">${esc(c.server_value || "∅")}</div></div>
            <div class="conflict-val"><div class="conflict-val-label">Técnico</div><div class="conflict-val-text">${esc(c.client_value || "∅")}</div></div>
          </div>
          <div class="d-flex gap-2 mb-2">
            <button class="btn btn-sm btn-outline-danger" data-decision="SERVIDOR">Mantener centro de mando</button>
            <button class="btn btn-sm btn-outline-primary" data-decision="TECNICO">Usar lo del técnico</button>
          </div>
          <textarea class="form-control form-control-sm mb-2" rows="3">${esc((c.server_value || "") + "\n" + (c.client_value || ""))}</textarea>
          <button class="btn btn-sm btn-accent" data-decision="COMBINAR">Guardar combinado</button>
        </div>`).join("")}</div>` : ""}
      <div class="job-grid">
        <div>
          <div class="lc-card mb-4">
            <div class="section-title"><i class="bi bi-person-lines-fill me-1"></i> Información de la orden</div>
            ${j.descripcion ? `<p class="mb-3" style="font-size:.92rem; color:var(--text-main); white-space:pre-line">${esc(j.descripcion)}</p><hr class="my-3" style="border-color:#f0f2f6">` : ""}
            ${info("Dirección", esc(j.direccion || "—"))}
            ${info("Teléfono", esc(j.telefono || cliente?.telefono || "—"))}
            ${info("Cita", esc(fecha(j.programado_para, j.zona_horaria)) + (j.ventana_preferida ? `<span class="text-muted ms-2" style="font-size:.82rem">· preferencia: ${esc(preferencia(j.ventana_preferida))}</span>` : ""))}
            ${info("Equipo", esc(j.equipo || "—"))}
            ${info("Propiedad", esc(tipoProp || "—") + `<span class="text-muted ms-2" style="font-size:.82rem">· cliente presente: —</span>`)}
            ${j.tipo_acceso || propiedad?.tipo_acceso ? info("Acceso", esc(j.tipo_acceso || propiedad.tipo_acceso)) : ""}
            ${j.instrucciones ? info("Instrucciones", esc(j.instrucciones)) : ""}
            ${j.mascota ? info("Mascotas", esc(j.mascota)) : ""}
            ${j.contacto_respaldo ? info("Respaldo", esc(j.contacto_respaldo)) : ""}
            ${cliente ? info("Cliente", `${esc(cliente.nombre)}${cliente.user_id ? ' <span class="badge bg-success-subtle text-success ms-1" style="font-size:.7rem">app</span>' : ""}`) : ""}
            ${info("Técnico", j.technician_code ? `<span class="badge" style="background:#f0f2f8;color:var(--text-main);font-size:.82rem;font-weight:600"><i class="bi bi-person-badge me-1"></i>${esc(j.technician_code)}</span>` : '<span class="text-muted">sin asignar</span>')}
            ${j.notas ? info("Notas", `<span style="white-space:pre-line">${esc(j.notas)}</span>`) : ""}
          </div>
          <div class="lc-card mb-4">
            <div class="d-flex justify-content-between align-items-center mb-3"><div class="section-title mb-0"><i class="bi bi-receipt me-1"></i> Factura</div></div>
            ${factura ? `<div class="mb-2"><span class="fw-bold">${esc(factura.number)}</span>
                <span class="badge ${factura.estado === "PAGADA" ? "bg-success" : "bg-warning text-dark"} ms-2">${esc(factura.estado)}</span></div>
              <div class="table-responsive"><table class="lc-table w-100"><tbody>
                ${(factura.items || []).map((it) => `<tr><td>${esc(it.descripcion)}</td><td class="text-end">${money(it.importe)}</td></tr>`).join("")}
                <tr><td class="text-muted">Subtotal</td><td class="text-end">${money(factura.subtotal)}</td></tr>
                <tr><td class="text-muted">Impuesto (${esc(factura.tax_pct)}%)</td><td class="text-end">${money(factura.tax)}</td></tr>
                <tr><td class="fw-bold">Total</td><td class="text-end fw-bold">${money(factura.total)}</td></tr></tbody></table></div>
              ${(pagos || []).map((p) => `<div class="text-muted mt-2" style="font-size:.82rem"><i class="bi bi-credit-card text-success me-1"></i>
                Pago ${esc(p.method === "TEST" ? "de prueba" : p.method)} · ${money(p.amount)} · ${esc(p.status)} · ${esc(fecha(Number(p.created_at)))}</div>`).join("")}`
              : `<div class="text-center py-3 text-muted"><i class="bi bi-receipt" style="font-size:2rem;opacity:.3"></i>
              <p class="mt-2 mb-0" style="font-size:.88rem">Sin factura registrada</p></div>`}
            <hr class="mt-3" style="border-color:#f0f2f6">
            <div class="text-muted mb-2" style="font-size:.78rem;text-transform:uppercase;letter-spacing:.05em;font-weight:600">Autorizar descuento</div>
            <form id="fDescuento" class="d-flex gap-2 flex-wrap">
              <input type="number" step="0.01" min="0.01" name="monto" class="form-control form-control-sm" style="max-width:130px" placeholder="Monto $" required>
              <input name="motivo" class="form-control form-control-sm flex-grow-1" placeholder="Motivo" required minlength="2" maxlength="300">
              <button class="btn btn-sm btn-accent">Autorizar</button>
            </form>
            ${descuentos.map((d) => `<div class="text-muted mt-2" style="font-size:.82rem"><i class="bi bi-check-circle text-success me-1"></i>
              Descuento ${money(d.monto)} · ${esc(d.motivo)} · ${esc(d.autorizado_nombre)}</div>`).join("")}
          </div>
          <div class="lc-card mb-4">
            <div class="d-flex justify-content-between align-items-center mb-3"><div class="section-title mb-0"><i class="bi bi-camera me-1"></i> Evidencias</div>
              <div class="text-muted" style="font-size:.82rem"><i class="bi bi-image me-1"></i>${fotos.length} foto(s) &nbsp;·&nbsp; <i class="bi bi-journal-text me-1"></i>${j.notas ? 1 : 0} nota(s)</div></div>
            ${fotos.length || j.notas ? `${fotos.length ? `<div class="d-flex gap-2 flex-wrap mb-2">${fotos.map((f) => f.url ? `<a href="${esc(f.url)}" target="_blank" rel="noopener"><img src="${esc(f.url)}" alt="Evidencia ${esc(f.categoria)}" style="width:110px;height:110px;object-fit:cover;border-radius:10px;border:1px solid #e3e5e7"></a>` : "").join("")}</div>` : ""}
              ${j.notas ? `<div class="text-muted" style="font-size:.78rem;text-transform:uppercase;font-weight:600">Diagnóstico del técnico</div><div style="white-space:pre-line">${esc(j.notas)}</div>` : ""}`
              : `<div class="text-center py-2 text-muted" style="font-size:.85rem">Sin evidencias registradas.</div>`}
          </div>
          ${(ofertas || []).length ? `<div class="lc-card mb-4"><div class="section-title"><i class="bi bi-broadcast me-1"></i> Despacho automático · ofertas</div>
            <div class="table-responsive"><table class="lc-table w-100"><thead><tr><th>Técnico</th><th>Estado</th><th>Cuándo</th></tr></thead><tbody>
            ${ofertas.map((o) => `<tr><td>${esc(o.tecnico_nombre)}</td><td>${esc(o.estado)}</td><td class="text-muted">${esc(fecha(Number(o.created_at)))}</td></tr>`).join("")}</tbody></table></div></div>` : ""}
          <div class="lc-card">
            <div class="section-title"><i class="bi bi-clock-history me-1"></i> Historial de cambios</div>
            ${historial.length ? `<div class="table-responsive"><table class="lc-table hist-table w-100">
              <thead><tr><th>v</th><th>Campo</th><th>Nuevo valor</th><th>Actor</th><th>Cuándo</th></tr></thead><tbody>
              ${historial.map((h) => `<tr><td><span class="badge" style="background:#f0f2f8;color:var(--text-muted);font-size:.72rem">v${h.version}</span></td>
                <td class="fw-semibold">${esc(h.field)}</td><td class="text-muted">${esc(h.field === "technician_id" ? (tecnicos.find((t) => t.id === h.new_value)?.technician_code || h.new_value || "—") : (h.new_value || "—"))}</td>
                <td>${esc(h.actor_nombre)}</td><td class="text-muted">${esc(fecha(Date.parse(h.created_at)))}</td></tr>`).join("")}
              </tbody></table></div>` : `<div class="text-center py-2 text-muted" style="font-size:.85rem">Sin historial registrado.</div>`}
          </div>
        </div>
        <div class="control-panel">
          <div class="lc-card">
            <div class="section-title"><i class="bi bi-calendar-event me-1"></i> Programar cita</div>
            <form id="fProgramar">
              <div class="mb-2"><label class="form-label fw-semibold" style="font-size:.82rem">Fecha</label><input type="date" name="fecha" class="form-control form-control-sm" required></div>
              <div class="mb-3"><label class="form-label fw-semibold" style="font-size:.82rem">Hora</label><input type="time" name="hora" class="form-control form-control-sm" required></div>
              <button class="btn btn-accent w-100"><i class="bi bi-calendar-check me-1"></i> Programar</button>
            </form>
          </div>
          <div class="lc-card">
            <div class="section-title"><i class="bi bi-person-badge me-1"></i> Asignar técnico</div>
            <form id="fAsignar">
              <div class="mb-3"><select name="technicianId" class="form-select form-select-sm" aria-label="Técnico">
                <option value="">— sin asignar —</option>
                ${tecnicos.filter((t) => t.active || t.id === j.technician_id).map((t) => `<option value="${esc(t.id)}" ${t.id === j.technician_id ? "selected" : ""}>${esc(t.display_name || t.technician_code)}</option>`).join("")}
              </select></div>
              <button class="btn btn-accent w-100"><i class="bi bi-person-check me-1"></i> Asignar</button>
            </form>
          </div>
          <div class="lc-card">
            <div class="section-title"><i class="bi bi-arrow-repeat me-1"></i> Cambiar estado</div>
            <form id="fEstado">
              <div class="mb-3"><select name="estado" class="form-select form-select-sm" aria-label="Estado">
                ${estadosCfg().map((o) => `<option value="${esc(o.id)}" ${o.id === j.estado ? "selected" : ""}>${esc(o.label)}</option>`).join("")}</select></div>
              <button class="btn btn-accent w-100"><i class="bi bi-check2-circle me-1"></i> Cambiar estado</button>
            </form>
          </div>
          <div class="lc-card" style="background:#fafbfd">
            <div class="section-title"><i class="bi bi-info-circle me-1"></i> Resumen</div>
            <div class="d-flex flex-column gap-2">
              <div class="d-flex justify-content-between" style="font-size:.85rem"><span class="text-muted">Estado actual</span>
                <span class="badge badge-${esc(j.estado.toLowerCase().replaceAll(" ", "-"))}">${esc(etiquetaEstado(j.estado))}</span></div>
              <div class="d-flex justify-content-between" style="font-size:.85rem"><span class="text-muted">Versión</span><span class="fw-semibold">v${j.version}</span></div>
              <div class="d-flex justify-content-between" style="font-size:.85rem"><span class="text-muted">Origen</span><span class="fw-semibold">${esc(j.origin)}</span></div>
            </div>
          </div>
        </div>
      </div></div>`;

    const resultado = async (r, ok) => {
      window.scrollTo({ top: 0, behavior: "smooth" });   // el aviso de resultado está arriba
      if (r.status === 200 || r.status === 201) return vistaDetalle(id, { texto: ok });
      let texto;
      if (r.status === 409) texto = `Conflicto: la orden cambió (ahora v${r.body?.version ?? "?"}); recarga`;
      else if (r.body?.error === "transition_denied") texto = `Transición no permitida: ${r.body.from} → ${r.body.to}`;
      else if (r.body?.error === "invalid_technician") texto = "Técnico no válido";
      else texto = `No se guardó (${r.status} ${r.body?.error || ""})`;
      return vistaDetalle(id, { ok: false, texto });
    };
    const bloquear = (f) => f.querySelectorAll("button").forEach((b) => (b.disabled = true));
    $("fProgramar").addEventListener("submit", async (e) => {
      e.preventDefault(); bloquear(e.target);
      const f = new FormData(e.target);
      resultado(await patch(j, { programado_para: aEpoch(f.get("fecha"), f.get("hora"), TZ), zona_horaria: TZ,
                                 hora: hora12(f.get("fecha"), f.get("hora")) }), "Programada");
    });
    $("fAsignar").addEventListener("submit", async (e) => {
      e.preventDefault(); bloquear(e.target);
      const tid = new FormData(e.target).get("technicianId") || null;
      resultado(await patch(j, { technician_id: tid, estado_dispatch: tid ? "ASSIGNED" : "QUEUE" }), "Asignada");
    });
    $("fEstado").addEventListener("submit", async (e) => {
      e.preventDefault(); bloquear(e.target);
      resultado(await patch(j, { estado: new FormData(e.target).get("estado") }), "Estado actualizado");
    });
    $("fDescuento").addEventListener("submit", async (e) => {
      e.preventDefault(); bloquear(e.target);
      const f = new FormData(e.target);
      resultado(await api("POST", `/orders/${j.id}/discounts`, { monto: Number(f.get("monto")), motivo: f.get("motivo") }), "Descuento autorizado");
    });
    document.querySelectorAll("[data-conflicto] [data-decision]").forEach((b) => b.addEventListener("click", async () => {
      const card = b.closest("[data-conflicto]");
      b.disabled = true;
      const r = await api("POST", `/conflicts/${card.dataset.conflicto}/resolve`, { decision: b.dataset.decision,
        valor: card.querySelector("textarea").value, version: j.version });
      resultado(r.status === 200 ? r : { status: r.status === 409 ? 409 : r.status, body: r.body }, "Conflicto resuelto");
    }));
  }

  // ── NUEVA ORDEN (control_new.html) ────────────────────────────────────────────────────────
  function vistaNueva(error, f = {}) {
    cabecera("Nueva Orden", "Registrar un nuevo servicio", "nueva");
    $("lcBody").innerHTML = `
      <div class="d-flex justify-content-between align-items-center mb-4">
        <div><h2 class="fw-bold text-dark mb-0">Nueva Orden</h2><p class="text-muted small mb-0">Registrar un nuevo servicio en el sistema</p></div>
        <a href="#/" class="btn btn-outline-secondary shadow-sm bg-white"><i class="bi bi-arrow-left me-1"></i> Volver a la Agenda</a>
      </div>
      <div class="row justify-content-center"><div class="col-12 col-lg-10 col-xl-8"><div class="card shadow-sm border-0"><div class="card-body p-4 p-md-5">
        ${error ? `<div class="alert alert-danger"><i class="bi bi-exclamation-triangle me-2"></i>${esc(error)}</div>` : ""}
        <form id="fNueva">
          <div class="row mb-3">
            <div class="col-md-8"><label class="form-label fw-semibold text-muted">Cliente</label>
              <input type="text" name="cliente" class="form-control form-control-lg" placeholder="Nombre completo del cliente" value="${esc(f.cliente)}" required maxlength="200"></div>
            <div class="col-md-4 mt-3 mt-md-0"><label class="form-label fw-semibold text-muted">Teléfono</label>
              <input type="tel" name="telefono" class="form-control form-control-lg" placeholder="Ej. 3055221636" value="${esc(f.telefono)}" maxlength="32"></div>
          </div>
          <div class="mb-3"><label class="form-label fw-semibold text-muted">Dirección del Servicio</label>
            <input type="text" name="direccion" class="form-control form-control-lg" placeholder="Calle, número, ciudad" value="${esc(f.direccion)}" required maxlength="400"></div>
          <div class="row mb-3">
            <div class="col-md-6"><label class="form-label fw-semibold text-muted">Categoría</label>
              <select name="categoria" class="form-select form-select-lg" required>
                <option value="" ${f.categoria ? "" : "selected"} disabled>Selecciona una categoría...</option>
                ${[["HVAC", "HVAC / Aire Acondicionado"], ["Plomería", "Plomería"], ["Electrodomésticos", "Electrodomésticos"], ["General", "Mantenimiento General"]]
                  .map(([v, l]) => `<option value="${v}" ${f.categoria === v ? "selected" : ""}>${l}</option>`).join("")}
              </select></div>
            <div class="col-md-6 mt-3 mt-md-0"><label class="form-label fw-semibold text-muted">Equipo / Modelo (Opcional)</label>
              <input type="text" name="equipo" class="form-control form-control-lg" placeholder="Marca y modelo del equipo" value="${esc(f.equipo)}" maxlength="200"></div>
          </div>
          <div class="mb-4"><label class="form-label fw-semibold text-muted">Descripción del problema</label>
            <textarea name="descripcion" class="form-control form-control-lg" rows="4" placeholder="Describe el problema con el mayor detalle posible (mín. 10 caracteres)" required maxlength="4000">${esc(f.descripcion)}</textarea></div>
          <div class="d-grid mt-4"><button type="submit" class="btn btn-primary-custom btn-lg shadow-sm py-3 fs-5"><i class="bi bi-check2-circle me-2"></i> Guardar y Crear Orden</button></div>
        </form>
      </div></div></div></div>`;
    const form = $("fNueva");
    form.dataset.req = f.req || crypto.randomUUID();   // mismo request_id si se reintenta: nunca duplica
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(form).entries());
      Object.keys(d).forEach((k) => (d[k] = String(d[k]).trim()));
      if (d.descripcion.length < 10) return vistaNueva("descripción muy corta", { ...d, req: form.dataset.req });
      form.querySelector("button").disabled = true;
      const r = await api("POST", "/orders", { ...d, request_id: form.dataset.req });
      if (r.status === 201 || r.status === 200) { location.hash = `#/jobs/${r.body.id}`; setTimeout(() => alerta("Orden creada"), 50); }
      else vistaNueva(`No se creó (${r.status} ${r.body?.error || "sin conexión"}). Pulsa de nuevo para reintentar sin duplicar.`, { ...d, req: form.dataset.req });
    });
  }

  // ── CLIENTES (control_clientes.html) ──────────────────────────────────────────────────────
  async function vistaClientes(mensaje, error, f = {}) {
    cabecera("Clientes", null, "clientes");
    const r = await api("GET", "/customers");
    if (r.status !== 200) return errorVista(r);
    $("lcBody").innerHTML = `
      <div class="d-flex align-items-center justify-content-between mb-4"><h4 class="fw-bold mb-0">Clientes</h4>
        <a href="#agregar" id="irAgregar" class="btn btn-accent btn-sm"><i class="bi bi-person-plus me-1"></i> Agregar cliente</a></div>
      ${mensaje ? `<div class="alert alert-success d-flex align-items-center gap-2 mb-3"><i class="bi bi-check-circle-fill"></i> ${esc(mensaje)}</div>` : ""}
      ${error ? `<div class="alert alert-danger d-flex align-items-center gap-2 mb-3"><i class="bi bi-exclamation-triangle-fill"></i> ${esc(error)}</div>` : ""}
      <div class="lc-card mb-4"><div class="table-responsive"><table class="lc-table w-100">
        <thead><tr><th>#</th><th>Nombre</th><th>Teléfono</th><th>Email</th><th class="text-center">Propiedades</th><th class="text-center">Órdenes</th><th>Alta</th></tr></thead>
        <tbody>${r.body.length ? r.body.map((c) => `<tr>
          <td class="text-muted" style="font-size:.78rem">#${c.id}</td>
          <td><span class="fw-semibold">${esc(c.nombre)}</span>${c.cuenta_app ? ' <span class="badge bg-success-subtle text-success" style="font-size:.65rem">app</span>' : ""}</td>
          <td class="text-muted">${esc(c.telefono || "—")}</td><td class="text-muted">${esc(c.email || "—")}</td>
          <td class="text-center"><span class="badge" style="background:#f0f2f8;color:var(--text-main);font-size:.78rem">${c.n_prop}</span></td>
          <td class="text-center"><span class="badge" style="background:#f0f2f8;color:var(--text-main);font-size:.78rem">${c.n_jobs}</span></td>
          <td class="text-muted" style="font-size:.82rem">${esc(fecha(c.created_at))}</td></tr>`).join("")
          : `<tr><td colspan="7" class="text-center py-4 text-muted"><i class="bi bi-people" style="font-size:2rem;opacity:.3;display:block;margin-bottom:8px"></i>Sin clientes registrados aún.</td></tr>`}
        </tbody></table></div></div>
      <div class="lc-card p-4" id="agregar" style="max-width: 560px">
        <div class="d-flex align-items-center gap-2 mb-4">
          <div style="width:36px;height:36px;border-radius:10px;background:#fff0f1;display:flex;align-items:center;justify-content:center"><i class="bi bi-person-plus" style="color:var(--accent)"></i></div>
          <div><div class="fw-bold" style="font-size:.95rem">Agregar cliente</div><div class="text-muted" style="font-size:.78rem">Los campos de teléfono y email son opcionales</div></div>
        </div>
        <form id="fCliente" autocomplete="off">
          <div class="mb-3"><label class="form-label fw-semibold" style="font-size:.85rem">Nombre completo</label>
            <input name="nombre" class="form-control" placeholder="Ej: Ana García" required minlength="2" maxlength="80" value="${esc(f.nombre)}"></div>
          <div class="row g-3 mb-3">
            <div class="col-sm-6"><label class="form-label fw-semibold" style="font-size:.85rem">Teléfono</label>
              <input name="telefono" class="form-control" placeholder="+1 954 555 0100" maxlength="32" value="${esc(f.telefono)}"></div>
            <div class="col-sm-6"><label class="form-label fw-semibold" style="font-size:.85rem">Email</label>
              <input name="email" type="email" class="form-control" placeholder="cliente@email.com" maxlength="120" value="${esc(f.email)}"></div>
          </div>
          <button class="btn btn-accent w-100"><i class="bi bi-person-check me-2"></i> Guardar cliente</button>
        </form>
        <p class="text-muted mt-3 mb-0" style="font-size:.78rem"><i class="bi bi-info-circle me-1"></i>
          Las propiedades y equipos los registra el cliente en su app; si su correo coincide, la ficha se enlaza sola.</p>
      </div>`;
    $("irAgregar").addEventListener("click", (e) => { e.preventDefault(); $("agregar").scrollIntoView({ behavior: "smooth" }); });
    $("fCliente").addEventListener("submit", async (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(e.target).entries());
      e.target.querySelector("button").disabled = true;
      const r = await api("POST", "/customers", d);
      if (r.status === 201) vistaClientes(`Cliente ${d.nombre} agregado${r.body.cuenta_app ? " y enlazado a su cuenta de la app" : ""}.`);
      else vistaClientes(null, r.body?.error || `No se guardó (${r.status})`, d);
    });
  }

  // ── TÉCNICOS (control_tecnicos.html) ──────────────────────────────────────────────────────
  function qrSvg(texto) {
    const q = window.qrcode(0, "M"); q.addData(texto); q.make();
    return q.createSvgTag({ cellSize: 3, margin: 2, scalable: true });
  }
  async function vistaTecnicos(mensaje, error, clave, f = {}) {
    cabecera("Técnicos", null, "tecnicos");
    const r = await api("GET", "/technicians");
    if (r.status !== 200) return errorVista(r);
    tecnicos = r.body;
    const esAdmin = yo.role === "ADMIN";
    $("lcBody").innerHTML = `
      <div class="d-flex justify-content-between align-items-center mb-4"><div>
        <h2 class="fw-bold mb-1" style="font-size:1.35rem;color:#1a1d2e;">Técnicos</h2>
        <p class="mb-0" style="font-size:.82rem;color:#7b8099;">Gestión de técnicos y acceso a la app</p></div></div>
      ${mensaje ? `<div class="alert alert-success rounded-3 border-0 shadow-sm mb-3">${esc(mensaje)}</div>` : ""}
      ${error ? `<div class="alert alert-danger rounded-3 border-0 shadow-sm mb-3">${esc(error)}</div>` : ""}
      ${clave ? `<div class="lc-card mb-4 p-4" style="border:2px solid #dc3545;background:#fff1f2;"><div class="d-flex align-items-start gap-3">
        <div style="width:44px;height:44px;border-radius:12px;background:#fee2e2;display:flex;align-items:center;justify-content:center;font-size:1.3rem;color:#dc3545;flex-shrink:0;"><i class="bi bi-key"></i></div>
        <div class="flex-grow-1" style="min-width:0"><div class="fw-bold mb-1" style="color:#7f1d1d;">Contraseña inicial — ${esc(clave.usuario)}</div>
          <div style="font-size:1.5rem;font-weight:700;font-family:monospace;letter-spacing:.08em;color:#1a1d2e;word-break:break-all">${esc(clave.valor)}</div>
          <div class="mt-1" style="font-size:.78rem;color:#991b1b;">Se muestra <strong>solo esta vez</strong>. Entrégala al técnico en persona; deberá cambiarla al entrar.</div>
          <div class="mt-2 d-flex align-items-center gap-2"><div style="width:100px;height:100px;border-radius:8px;border:1px solid #fecaca;background:#fff">${qrSvg(location.origin + "/")}</div>
            <span style="font-size:.75rem;color:#991b1b;">Enlace del centro de control</span></div>
        </div></div></div>` : ""}
      <div class="lc-card mb-4">
        <div style="padding:18px 20px 14px;border-bottom:1px solid #f0f2f8;"><h3 class="section-title" style="font-size:.9rem;font-weight:700;color:#1a1d2e;margin:0;"><i class="bi bi-people me-2 text-muted"></i>Lista de técnicos</h3></div>
        <div style="overflow-x:auto;"><table class="lc-table"><thead><tr><th>Nombre</th><th>Usuario</th><th>Código</th><th>Especialidades</th><th>Estado</th><th>Abiertas</th>${esAdmin ? "<th>Acciones</th>" : ""}</tr></thead>
          <tbody>${tecnicos.length ? tecnicos.map((t) => `<tr>
            <td class="fw-semibold">${esc(t.display_name || "—")}</td><td>${esc(t.email || "—")}</td>
            <td><code style="background:#f0f2f8;padding:2px 6px;border-radius:4px;font-size:.8rem;">${esc(t.technician_code)}</code></td>
            <td style="color:#7b8099;font-size:.82rem;">${esc(t.specialties || "—")}</td>
            <td>${t.active ? '<span class="badge-estado badge-completado"><i class="bi bi-circle-fill" style="font-size:.45rem;"></i> ACTIVO</span>'
                           : '<span class="badge-estado badge-cancelado"><i class="bi bi-circle-fill" style="font-size:.45rem;"></i> INACTIVO</span>'}
              ${t.must_change_password ? '<br><span style="font-size:.7rem;color:#f59e0b;">debe cambiar contraseña</span>' : ""}</td>
            <td><span style="font-size:.85rem;">${t.abiertas}</span></td>
            ${esAdmin ? `<td><div class="d-flex flex-wrap gap-2 align-items-center" data-tec="${esc(t.id)}" data-nombre="${esc(t.email || t.technician_code)}">
              <button class="btn-outline-sm ${t.active ? "btn-outline-secondary-sm" : "btn-outline-primary-sm"}" data-accion="${t.active ? "desactivar" : "activar"}">
                <i class="bi bi-${t.active ? "toggle-off" : "toggle-on"}"></i> ${t.active ? "Desactivar" : "Activar"}</button>
              <button class="btn-outline-sm btn-outline-secondary-sm" data-accion="clave"><i class="bi bi-key"></i> Resetear contraseña</button>
              <button class="btn-outline-sm" style="border-color:#f59e0b;color:#b45309;" data-accion="pin"><i class="bi bi-shield-lock"></i> Restablecer PIN</button>
            </div></td>` : ""}</tr>`).join("")
            : `<tr><td colspan="7" style="text-align:center;padding:32px;color:#7b8099;"><i class="bi bi-person-x" style="font-size:2rem;opacity:.4;"></i><p class="mt-2 mb-0">No hay técnicos registrados</p></td></tr>`}
          </tbody></table></div></div>
      ${esAdmin ? `<div class="lc-card p-4"><h3 style="font-size:.9rem;font-weight:700;color:#1a1d2e;margin:0 0 16px;"><i class="bi bi-person-plus me-2 text-muted"></i>Agregar técnico</h3>
        <form id="fTecnico" autocomplete="off"><div class="row g-3">
          ${[["email", "Usuario (correo)", "email", "tecnico@empresa.com", 'required maxlength="120"'], ["display_name", "Nombre visible", "text", "Juan Pérez", 'required minlength="3" maxlength="80"'],
             ["technician_code", "Código técnico", "text", "QA-T03", 'required pattern="[A-Za-z0-9][A-Za-z0-9-]{2,29}" autocapitalize="characters"'],
             ["password", "Contraseña inicial", "password", "Vacío = se genera segura", 'minlength="10" autocomplete="new-password"']]
            .map(([n, l, ty, ph, at]) => `<div class="col-md-6"><label style="font-size:.8rem;font-weight:600;color:#7b8099;text-transform:uppercase;letter-spacing:.04em;">${l}</label>
              <input class="form-control form-control-sm mt-1" name="${n}" type="${ty}" placeholder="${ph}" ${at} value="${n === "password" ? "" : esc(f[n])}"></div>`).join("")}
          <div class="col-12"><label style="font-size:.8rem;font-weight:600;color:#7b8099;text-transform:uppercase;letter-spacing:.04em;">Especialidades (opcional)</label>
            <input class="form-control form-control-sm mt-1" name="specialties" maxlength="200" value="${esc(f.specialties)}" placeholder="HVAC, Lavadoras, Refrigeración"></div>
          <div class="col-12"><button type="submit" class="btn-accent"><i class="bi bi-person-plus-fill"></i> Guardar técnico</button></div>
        </div></form></div>`
      : `<div class="lc-card p-4 text-center" style="color:#7b8099;font-size:.85rem;"><i class="bi bi-lock" style="font-size:1.5rem;opacity:.4;"></i>
          <p class="mt-2 mb-0">Solo un administrador puede dar de alta o desactivar técnicos.</p></div>`}`;
    if (!esAdmin) return;
    document.querySelectorAll("[data-tec] [data-accion]").forEach((b) => b.addEventListener("click", async () => {
      const box = b.closest("[data-tec]"), uid = box.dataset.tec, nombre = box.dataset.nombre, acc = b.dataset.accion;
      if (acc === "pin") return vistaTecnicos(null, "En la plataforma online el técnico entra con correo y contraseña; el PIN rápido del laboratorio no aplica. Usa «Resetear contraseña».");
      if (acc === "clave" && !confirm(`¿Restablecer contraseña de ${nombre}?`)) return;
      b.disabled = true;
      if (acc === "clave") {
        const r = await api("POST", `/technicians/${uid}/password`);
        return r.status === 200 ? vistaTecnicos(`Contraseña de ${nombre} restablecida.`, null, { usuario: nombre, valor: r.body.password })
                                : vistaTecnicos(null, r.body?.error || `Error ${r.status}`);
      }
      const activo = acc === "activar";
      const r = await api("POST", `/technicians/${uid}/active`, { active: activo });
      if (r.status !== 200) return vistaTecnicos(null, r.body?.error || `Error ${r.status}`);
      vistaTecnicos(`${nombre} ${activo ? "activado" : "desactivado"}.` + (!activo && r.body.abiertas ? ` Tiene ${r.body.abiertas} orden(es) abierta(s): reasígnalas.` : ""));
    }));
    $("fTecnico").addEventListener("submit", async (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(e.target).entries());
      e.target.querySelector("button").disabled = true;
      const r = await api("POST", "/technicians", d);
      if (r.status === 201) vistaTecnicos(`Técnico ${r.body.email} (${r.body.technician_code}) creado. Debe cambiar la contraseña al entrar.`, null,
                                          { usuario: r.body.email, valor: r.body.password });
      else { delete d.password; vistaTecnicos(null, r.body?.error || `No se guardó (${r.status})`, null, d); }
    });
  }


  // ── CONFIGURACIÓN: precios y valores (settings) ───────────────────────────────────────────────
  // Todo valor editable vive en public.settings; lc-api valida rol, tipo, rango, piso de precios y versión, y audita.
  const ES_ROL_EDITA = (s) => yo && (yo.role === "ADMIN" || String(s.editable_by).includes(yo.role));
  const NOMBRES_OFICIO = { hvac: "A/C y Calefacción (HVAC)", plumbing: "Plomería", electricity: "Electricidad", general: "General (electrodomésticos y otros)" };
  const NOMBRES_CONCEPTO = { diagnostic: "Visita de diagnóstico (USD)", hourly: "Precio por hora (USD)", emergency_multiplier: "Multiplicador de emergencia (×)" };
  const fmtVal = (v) => (typeof v === "number" ? (Number.isInteger(v) ? v.toFixed(1) : String(v)) : esc(JSON.stringify(v)));

  async function vistaConfig(mensaje) {
    cabecera("Precios y configuración", "Los cambios llegan a las apps en menos de un minuto", "config");
    const r = await api("GET", "/settings/pricing");
    if (r.status !== 200) return errorVista(r);
    const filas = r.body.settings;
    const porOficio = {};
    for (const s of filas) {
      const m = s.key.match(/^pricing\.([a-z_]+)\.([a-z_]+)$/);
      if (m) (porOficio[m[1]] ||= []).push({ s, concepto: m[2] });
    }
    const generales = filas.filter((s) => !/^pricing\.[a-z_]+\.[a-z_]+$/.test(s.key));
    const fila = (s, nombre) => {
      const puede = ES_ROL_EDITA(s);
      return `<tr data-key="${esc(s.key)}" data-version="${s.version}">
        <td class="fw-semibold">${esc(nombre)}<div class="text-muted" style="font-size:.72rem">${esc(s.key)}</div></td>
        <td style="width:170px"><input class="form-control form-control-sm cfg-input" type="number" step="0.01" min="${s.constraints?.min ?? 0}"
            ${s.constraints?.max != null ? `max="${s.constraints.max}"` : ""} value="${esc(s.value)}" ${puede ? "" : "disabled"} aria-label="${esc(nombre)}"></td>
        <td class="text-muted d-none d-md-table-cell" style="font-size:.82rem">${fmtVal(s.default_value)}</td>
        <td class="text-muted d-none d-md-table-cell" style="font-size:.78rem">${esc(fecha(s.updated_at))} · v${s.version}</td>
        <td class="text-end" style="white-space:nowrap">
          ${puede ? `<button class="btn btn-accent btn-sm cfg-guardar">Guardar</button>` : `<span class="text-muted" style="font-size:.75rem"><i class="bi bi-lock"></i> Solo administrador</span>`}
          ${yo.role === "ADMIN" ? `<button class="btn btn-outline-secondary btn-sm cfg-reset" title="Volver al valor inicial">↺</button>
          <button class="btn btn-outline-secondary btn-sm cfg-hist d-none d-md-inline-block" title="Historial">Historial</button>` : ""}
        </td></tr>
        <tr class="cfg-msg" data-for="${esc(s.key)}" hidden><td colspan="5"></td></tr>`;
    };
    const tabla = (titulo, subt, items) => `<div class="lc-card mb-4"><div class="p-3 pb-0"><div class="fw-bold">${esc(titulo)}</div>
        ${subt ? `<div class="text-muted" style="font-size:.78rem">${esc(subt)}</div>` : ""}</div>
        <div class="table-responsive"><table class="lc-table w-100"><thead><tr><th>Concepto</th><th>Valor</th><th class="d-none d-md-table-cell">Inicial</th><th class="d-none d-md-table-cell">Última modificación</th><th></th></tr></thead>
        <tbody>${items.join("")}</tbody></table></div></div>`;
    const orden = ["hvac", "plumbing", "electricity", "general"];
    $("lcBody").innerHTML = `
      ${mensaje ? `<div class="alert alert-success mb-3">${esc(mensaje)}</div>` : ""}
      <div class="alert alert-light border mb-4" style="font-size:.85rem"><i class="bi bi-info-circle me-1"></i>
        Regla Broward County / Florida: <b>$10 menos que la competencia, sin bajar del piso</b>. El servidor rechaza cualquier diagnóstico u hora
        por debajo de <b>${money(filas.find((x) => x.key === "pricing.service_call_floor")?.value ?? 50)}</b>.</div>
      ${tabla("Piso y margen", "Valores generales de precios", generales.map((s) => fila(s,
        s.key === "pricing.service_call_floor" ? "Piso mínimo de visita y hora (USD)" : s.key === "pricing.markup_percent" ? "Margen por defecto en propuestas (%)" : s.description)))}
      ${orden.filter((o) => porOficio[o]).map((o) => tabla(NOMBRES_OFICIO[o] || o, null,
        porOficio[o].map(({ s, concepto }) => fila(s, NOMBRES_CONCEPTO[concepto] || concepto)))).join("")}
      <div id="cfgHist" class="lc-card p-3 mb-4" hidden></div>`;
    const msg = (k, texto, ok) => { const r = document.querySelector(`tr.cfg-msg[data-for="${CSS.escape(k)}"]`); if (!r) return;
      r.hidden = !texto; r.firstElementChild.innerHTML = texto ? `<div class="alert alert-${ok ? "success" : "danger"} py-1 px-2 mb-1" style="font-size:.8rem">${esc(texto)}</div>` : ""; };
    $("lcBody").querySelectorAll("tr[data-key]").forEach((tr) => {
      const k = tr.dataset.key, inp = tr.querySelector(".cfg-input");
      inp.addEventListener("keydown", (ev) => { if (ev.key === "Enter") { ev.preventDefault(); tr.querySelector(".cfg-guardar")?.click(); } });
      tr.querySelector(".cfg-guardar")?.addEventListener("click", async (e) => {
        const v = Number(inp.value);
        if (!Number.isFinite(v)) return msg(k, "Escribe un número", false);
        e.target.disabled = true;
        const rr = await api("PUT", "/settings/" + k, { value: v, expectedVersion: Number(tr.dataset.version), reason: "Control Web" });
        e.target.disabled = false;
        if (rr.status === 200) { tr.dataset.version = rr.body.version; msg(k, `Guardado: ${fmtVal(rr.body.value)} (versión ${rr.body.version})`, true);
          tr.children[3].innerHTML = `${esc(fecha(Date.now()))} · v${rr.body.version}`; }
        else msg(k, rr.body?.mensaje || rr.body?.error || `No se guardó (${rr.status})`, false);
      });
      tr.querySelector(".cfg-reset")?.addEventListener("click", async () => {
        const rr = await api("POST", `/settings/${k}/reset`, {});
        if (rr.status === 200) vistaConfig(`Restablecido: ${k}`); else msg(k, rr.body?.error || "No se pudo restablecer", false);
      });
      tr.querySelector(".cfg-hist")?.addEventListener("click", async () => {
        const rr = await api("GET", `/settings/audit?key=${encodeURIComponent(k)}&limit=20`);
        const box = $("cfgHist"); box.hidden = false;
        box.innerHTML = rr.status !== 200 ? `<div class="text-danger">No se pudo cargar el historial</div>` : `<div class="fw-bold mb-2">Historial · ${esc(k)}</div>
          <table class="lc-table w-100"><thead><tr><th>Cuándo</th><th>Quién</th><th>Antes</th><th>Después</th><th>Origen</th></tr></thead><tbody>
          ${rr.body.map((a) => `<tr><td>${esc(fecha(Date.parse(a.changed_at)))}</td><td>${esc(a.changed_by_name || "—")}</td>
            <td>${fmtVal(a.old_value)}</td><td>${fmtVal(a.new_value)}</td><td>${esc(a.via)}</td></tr>`).join("") || `<tr><td colspan="5">Sin cambios</td></tr>`}
          </tbody></table>`;
        box.scrollIntoView({ behavior: "smooth" });
      });
    });
  }

  // ── LISTAS EDITABLES (desplegables de las apps) ───────────────────────────────────────────────────
  // Agregar, renombrar y desactivar opciones. Nunca se borran (órdenes históricas). Los estados tienen ids fijos.
  const NOMBRES_LISTA = { "categories.list": "Categorías de servicio (Cliente)", "specialties.list": "Especialidades del técnico",
    "order_states.list": "Estados de la orden (solo textos)", "property_types.list": "Tipos de propiedad (Cliente)", "access_types.list": "Formas de acceso (Cliente)",
    "refrigerants.list": "Refrigerantes (Técnico)", "voltages.list": "Voltajes (Técnico)", "phases.list": "Fases eléctricas (Técnico)",
    "system_roles.list": "Parte del sistema (Técnico)", "lifecycle_statuses.list": "Ciclo de vida del equipo (Técnico)",
    "inspection_types.list": "Tipos de medición (Técnico)", "inspection_units.list": "Unidades (Técnico)", "inspection_resolutions.list": "Resoluciones de hallazgo (Técnico)",
    "finding_areas.list": "Áreas de hallazgo (Técnico)", "payment_methods.list": "Métodos de pago (Técnico)",
    "job_actions.list": "Acciones del menú de estado (Técnico, solo textos)", "photo_categories.list": "Categorías de foto (Técnico, solo textos)",
    "photo_sources.list": "Origen de la foto (Técnico, solo textos)", "share_channels.list": "Canales para avisar al cliente (Técnico, solo textos)",
    "work_item_types.list": "Tipos de partida (Técnico, solo textos)" };
  const idDesde = (texto, listaKey) => {
    const base = texto.trim();
    return ["categories.list", "specialties.list"].includes(listaKey)
      ? base.normalize("NFD").replace(/[̀-ͯ]/g, "").toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_|_$/g, "") : base;
  };
  async function vistaListas(clave, mensaje) {
    cabecera("Listas editables", "Los cambios aparecen en las apps sin recompilar", "listas");
    const r = await api("GET", "/settings");
    if (r.status !== 200) return errorVista(r);
    const listas = r.body.settings.filter((s) => s.key.endsWith(".list") && s.key !== "proposal_templates.list");
    const s = listas.find((x) => x.key === clave) || listas.find((x) => x.key === "categories.list") || listas[0];
    if (!s) { $("lcBody").innerHTML = `<div class="empty-state">Sin listas</div>`; return; }
    const fijo = !!s.constraints?.fixedIds, puede = ES_ROL_EDITA(s);
    let opciones = JSON.parse(JSON.stringify(s.value));
    const tieneCust = opciones.some((o) => "customer_label" in o);
    const tienePG = opciones.some((o) => "price_group" in o);   // categorías: de qué grupo de precios cobra
    const GRUPOS = [["hvac", "A/C y Calefacción"], ["plumbing", "Plomería"], ["electricity", "Electricidad"], ["general", "General"]];
    const pintar = (err) => {
      $("lcBody").innerHTML = `
        ${mensaje ? `<div class="alert alert-success mb-3">${esc(mensaje)}</div>` : ""}
        <div class="lc-card p-3 mb-4">
          <label class="form-label fw-semibold" for="selLista">Lista</label>
          <select id="selLista" class="form-select" style="max-width:420px">${listas.map((l) => `<option value="${esc(l.key)}" ${l.key === s.key ? "selected" : ""}>${esc(NOMBRES_LISTA[l.key] || l.key)}</option>`).join("")}</select>
          <div class="text-muted mt-2" style="font-size:.8rem">${esc(s.description)} · versión ${s.version}.
            ${fijo ? "<b>Ids fijos</b>: solo se editan los textos." : "Las opciones <b>no se borran</b>: se desactivan para no romper órdenes históricas."}</div>
        </div>
        <div class="lc-card mb-4"><div class="table-responsive"><table class="lc-table w-100">
          <thead><tr><th>Id (fijo)</th><th>Texto que ve la app</th>${tieneCust ? "<th>Texto para el cliente</th>" : ""}${tienePG ? "<th>Grupo de precio</th>" : ""}<th class="text-center">Activa</th></tr></thead>
          <tbody>${opciones.map((o, i) => `<tr data-i="${i}"><td class="text-muted" style="font-size:.8rem">${esc(o.id)}</td>
            <td><input class="form-control form-control-sm op-label" maxlength="80" value="${esc(o.label)}" ${puede ? "" : "disabled"} aria-label="Texto de ${esc(o.id)}"></td>
            ${tieneCust ? `<td><input class="form-control form-control-sm op-cust" maxlength="80" value="${esc(o.customer_label || "")}" ${puede ? "" : "disabled"}></td>` : ""}
            ${tienePG ? `<td><select class="form-select form-select-sm op-pg" ${puede ? "" : "disabled"} aria-label="Grupo de precio de ${esc(o.id)}">${GRUPOS.map(([v, t]) => `<option value="${v}" ${o.price_group === v ? "selected" : ""}>${t}</option>`).join("")}</select></td>` : ""}
            <td class="text-center"><div class="form-check form-switch d-inline-block"><input class="form-check-input op-act" type="checkbox" ${o.active === false ? "" : "checked"} ${puede && !fijo ? "" : "disabled"} aria-label="Activa ${esc(o.id)}"></div></td></tr>`).join("")}
          </tbody></table></div></div>
        ${puede && !fijo ? `<div class="lc-card p-3 mb-3" style="max-width:640px"><div class="fw-bold mb-2">Agregar opción</div>
          <div class="row g-2"><div class="col-sm-7"><input id="nuevoLabel" class="form-control" maxlength="80" placeholder="Ej: Piscinas" aria-label="Nombre de la nueva opción"></div>
          <div class="col-sm-5"><button id="btnAgregar" class="btn btn-outline-secondary w-100">+ Agregar a la lista</button></div></div></div>` : ""}
        ${err ? `<div class="alert alert-danger" id="listaErr">${esc(err)}</div>` : ""}
        ${puede ? `<button id="btnGuardarLista" class="btn btn-accent"><i class="bi bi-check2 me-1"></i> Guardar lista</button>` : `<div class="text-muted"><i class="bi bi-lock"></i> Solo un administrador edita esta lista.</div>`}`;
      $("selLista").addEventListener("change", (e) => vistaListas(e.target.value));
      const leer = () => $("lcBody").querySelectorAll("tr[data-i]").forEach((tr) => { const o = opciones[+tr.dataset.i];
        o.label = tr.querySelector(".op-label").value.trim(); if (tieneCust) o.customer_label = tr.querySelector(".op-cust").value.trim();
        if (tienePG) o.price_group = tr.querySelector(".op-pg").value;
        if (!fijo) o.active = tr.querySelector(".op-act").checked; });
      $("btnAgregar")?.addEventListener("click", () => {
        leer(); const t = $("nuevoLabel").value.trim();
        if (!t) return pintar("Escribe el nombre de la nueva opción");
        const id = idDesde(t, s.key);
        if (!id) return pintar("El nombre debe tener letras o números");
        if (opciones.some((o) => o.id === id)) return pintar(`Ya existe una opción con id «${id}»`);
        opciones.push(tienePG ? { id, label: t, active: true, price_group: "general" } : { id, label: t, active: true }); pintar();
      });
      $("btnGuardarLista")?.addEventListener("click", async (e) => {
        leer(); e.target.disabled = true;
        const rr = await api("PUT", "/settings/" + s.key, { value: opciones, expectedVersion: s.version, reason: "Listas editables (Control Web)" });
        if (rr.status === 200) vistaListas(s.key, `Lista guardada (versión ${rr.body.version}). Las apps la mostrarán en menos de un minuto.`);
        else { e.target.disabled = false; pintar(rr.body?.mensaje || rr.body?.error || `No se guardó (${rr.status})`); }
      });
    };
    pintar();
  }


  // ── PLANTILLAS DE PROPUESTA (settings proposal_templates.list) ────────────────────────────────────
  // Cada plantilla trae 3 opciones (Good/Better/Best): título, descripción, horas y materiales. En el Técnico, la mano de obra
  // = horas × tarifa por hora del grupo de precios de la orden (pricing.<grupo>.hourly). Las plantillas no se borran: se desactivan.
  async function vistaPlantillas(mensaje) {
    cabecera("Plantillas de propuesta", "Los cambios llegan a los técnicos en menos de un minuto", "plantillas");
    const r = await api("GET", "/settings/proposal_templates.list");
    if (r.status !== 200 || !r.body?.settings?.[0]) return errorVista(r);
    const s = r.body.settings[0], puede = ES_ROL_EDITA(s);
    let plant = JSON.parse(JSON.stringify(s.value));
    const NIV = ["Opción 1 (Good)", "Opción 2 (Better)", "Opción 3 (Best)"];
    const pintar = (err) => {
      $("lcBody").innerHTML = `
        ${mensaje ? `<div class="alert alert-success mb-3">${esc(mensaje)}</div>` : ""}
        <div class="alert alert-light border mb-3" style="font-size:.85rem"><i class="bi bi-info-circle me-1"></i>
          Mano de obra = <b>horas × precio por hora</b> del grupo de la orden (Precios). Los materiales van en dólares. Versión ${s.version}.</div>
        ${plant.map((t, ti) => `<div class="lc-card mb-3 p-3" data-t="${ti}">
          <div class="d-flex gap-2 align-items-center mb-2 flex-wrap">
            <input class="form-control tp-label" style="max-width:340px" maxlength="80" value="${esc(t.label)}" ${puede ? "" : "disabled"} aria-label="Nombre de la plantilla">
            <div class="form-check form-switch"><input class="form-check-input tp-act" type="checkbox" ${t.active === false ? "" : "checked"} ${puede ? "" : "disabled"} aria-label="Activa"><label class="form-check-label" style="font-size:.8rem">Activa</label></div>
            <span class="text-muted" style="font-size:.72rem">id ${esc(t.id)}</span></div>
          <div class="table-responsive"><table class="lc-table w-100"><thead><tr><th>Opción</th><th>Título</th><th>Descripción</th><th>Horas</th><th>Materiales $</th></tr></thead><tbody>
          ${t.options.map((o, oi) => `<tr data-o="${oi}"><td style="white-space:nowrap">${NIV[oi]}</td>
            <td><input class="form-control form-control-sm op-t" maxlength="80" value="${esc(o.title)}" ${puede ? "" : "disabled"}></td>
            <td><input class="form-control form-control-sm op-d" maxlength="300" value="${esc(o.description)}" ${puede ? "" : "disabled"}></td>
            <td style="width:90px"><input class="form-control form-control-sm op-h" type="number" min="0" max="200" step="0.5" value="${esc(o.hours)}" ${puede ? "" : "disabled"}></td>
            <td style="width:110px"><input class="form-control form-control-sm op-m" type="number" min="0" max="100000" step="1" value="${esc(o.materials)}" ${puede ? "" : "disabled"}></td></tr>`).join("")}
          </tbody></table></div></div>`).join("")}
        ${puede ? `<div class="lc-card p-3 mb-3" style="max-width:640px"><div class="fw-bold mb-2">Agregar plantilla</div>
          <div class="row g-2"><div class="col-sm-7"><input id="nuevaPlantilla" class="form-control" maxlength="80" placeholder="Ej: Diagnóstico de fuga" aria-label="Nombre de la nueva plantilla"></div>
          <div class="col-sm-5"><button id="btnAddPlantilla" class="btn btn-outline-secondary w-100">+ Agregar</button></div></div></div>` : ""}
        ${err ? `<div class="alert alert-danger">${esc(err)}</div>` : ""}
        ${puede ? `<button id="btnGuardarPlantillas" class="btn btn-accent"><i class="bi bi-check2 me-1"></i> Guardar plantillas</button>` : `<div class="text-muted"><i class="bi bi-lock"></i> Solo un administrador edita las plantillas.</div>`}`;
      const leer = () => $("lcBody").querySelectorAll("[data-t]").forEach((card) => {
        const t = plant[+card.dataset.t];
        t.label = card.querySelector(".tp-label").value.trim(); t.active = card.querySelector(".tp-act").checked;
        card.querySelectorAll("tr[data-o]").forEach((tr) => { const o = t.options[+tr.dataset.o];
          o.title = tr.querySelector(".op-t").value.trim(); o.description = tr.querySelector(".op-d").value.trim();
          o.hours = Number(tr.querySelector(".op-h").value); o.materials = Number(tr.querySelector(".op-m").value); });
      });
      $("btnAddPlantilla")?.addEventListener("click", () => {
        leer(); const n = $("nuevaPlantilla").value.trim();
        if (!n) return pintar("Escribe el nombre de la plantilla");
        const id = n.normalize("NFD").replace(/[̀-ͯ]/g, "").toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_|_$/g, "");
        if (!id || plant.some((t) => t.id === id)) return pintar("Ya existe una plantilla con ese nombre");
        plant.push({ id, label: n, active: true, options: ["Good", "Better", "Best"].map((nv) => ({ title: `${n} - ${nv}`, description: "", hours: 1, materials: 0 })) });
        pintar();
      });
      $("btnGuardarPlantillas")?.addEventListener("click", async (e) => {
        leer(); e.target.disabled = true;
        const rr = await api("PUT", "/settings/proposal_templates.list", { value: plant, expectedVersion: s.version, reason: "Plantillas de propuesta (Control Web)" });
        if (rr.status === 200) vistaPlantillas(`Plantillas guardadas (versión ${rr.body.version}).`);
        else { e.target.disabled = false; pintar(rr.body?.mensaje || rr.body?.error || `No se guardó (${rr.status})`); }
      });
    };
    pintar();
  }

  function errorVista(r) {
    $("lcBody").innerHTML = `<div class="empty-state"><i class="bi bi-wifi-off"></i><p>No se pudo cargar (${r.status} ${esc(r.body?.error || "")}).<br>
      <a href="#" id="reintentar">Reintentar</a></p></div>`;
    $("reintentar").addEventListener("click", (e) => { e.preventDefault(); enrutar(); });
  }

  // ── notificaciones ────────────────────────────────────────────────────────────────────────
  const visto = () => { try { return Number(localStorage.getItem("lc-control-notif") || 0); } catch (_) { return 0; } };
  async function contarNotif() {
    const r = await api("GET", "/notifications?since=" + visto());
    const n = r.status === 200 ? r.body.length : 0;
    $("notifBadge").hidden = !n; $("notifBadge").textContent = n > 99 ? "99+" : n;
    return r.status === 200 ? r.body : [];
  }
  $("btnNotif").addEventListener("click", async (e) => {
    e.preventDefault();
    const p = $("notifPanel");
    if (!p.hidden) { p.hidden = true; return; }
    const r = await api("GET", "/notifications?since=0");
    const lista = r.status === 200 ? r.body.slice(0, 30) : [];
    p.innerHTML = `<div class="section-header"><h3 class="section-title">Notificaciones</h3></div>` + (lista.length
      ? lista.map((n) => `<a class="notif-item d-block text-decoration-none" href="${n.job_id ? "#/jobs/" + n.job_id : "#/"}">
          <div class="t">${esc(n.titulo)}</div>${n.cuerpo ? `<div class="text-muted">${esc(n.cuerpo)}</div>` : ""}<div class="m">${esc(fecha(n.created_at))}</div></a>`).join("")
      : `<div class="empty-state"><i class="bi bi-bell-slash"></i><p>Sin notificaciones.</p></div>`);
    p.hidden = false;
    try { localStorage.setItem("lc-control-notif", String(Date.now())); } catch (_) {}
    $("notifBadge").hidden = true;
  });
  document.addEventListener("click", (e) => { if (!e.target.closest("#notifPanel, #btnNotif")) $("notifPanel").hidden = true; });

  // ── tiempo real ───────────────────────────────────────────────────────────────────────────
  let repintar = null;
  // Con algo escrito en un formulario no se repinta (antes un cambio en tiempo real vaciaba el alta de técnico)
  let editando = false;
  $("lcBody").addEventListener("input", () => { editando = true; });
  new MutationObserver(() => { editando = false; }).observe($("lcBody"), { childList: true });
  function programarRepintado() {
    clearTimeout(repintar);
    repintar = setTimeout(() => {
      if (vistaActual && !editando && !document.activeElement?.closest("form")) vistaActual();
      contarNotif();
    }, 400);
  }
  function rt(texto, clase) { $("rtEstado").innerHTML = `<i class="bi bi-circle-fill ${clase}" style="font-size:.5rem"></i> ${esc(texto)}`; }
  async function conectarTiempoReal() {
    if (canal) await sb.removeChannel(canal);
    const { data: { session } } = await sb.auth.getSession();
    if (!session) return;
    sb.realtime.setAuth(session.access_token);
    canal = sb.channel("lc-control-" + device)
      .on("postgres_changes", { event: "*", schema: "public", table: "jobs" }, programarRepintado)
      .on("postgres_changes", { event: "INSERT", schema: "public", table: "notifications" }, programarRepintado)
      .subscribe((estado, err) => {
        if (estado === "SUBSCRIBED") { rt("Tiempo real conectado", "text-success"); programarRepintado(); }
        else if (estado === "CHANNEL_ERROR" || estado === "TIMED_OUT") {
          rt("Tiempo real: reconectando…", "text-warning");
          console.warn("realtime", estado, err && err.message);
          setTimeout(conectarTiempoReal, 5000);
        } else if (estado === "CLOSED") rt("Tiempo real desconectado", "text-secondary");
      });
  }

  // Android/Chrome suspende el WebSocket en segundo plano: al volver se recarga y se reconecta si hace falta
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible" || !yo) return;
    if (!canal || canal.state !== "joined") conectarTiempoReal();
    programarRepintado();
  });
  window.addEventListener("online", () => { if (yo) { conectarTiempoReal(); programarRepintado(); } });

  // ── sesión y rutas ────────────────────────────────────────────────────────────────────────
  function mostrarLogin(msg) {
    $("vistaApp").hidden = true; $("vistaLogin").hidden = false;
    const e = $("loginError"); e.hidden = !msg; e.querySelector("span").textContent = msg || "";
    if (canal) { sb.removeChannel(canal); canal = null; }
  }
  async function entrar() {
    const r = await api("GET", "/auth/me");
    if (r.status !== 200 || !["ADMIN", "DISPATCHER"].includes(r.body?.role)) {
      await sb.auth.signOut();
      return mostrarLogin(r.status === 200 ? "este usuario no es de despacho" : "No se pudo verificar tu cuenta.");
    }
    yo = r.body;
    const nombre = yo.display_name || yo.email || "Usuario";
    $("sbNombre").textContent = nombre; $("sbRol").textContent = yo.role;
    $("sbAvatar").textContent = $("hdAvatar").textContent = nombre[0].toUpperCase();
    $("vistaLogin").hidden = true; $("vistaApp").hidden = false;
    conectarTiempoReal(); contarNotif();
    enrutar();
  }
  async function enrutar() {
    if (!yo) return;
    await cargarCfg(false);
    alerta("");
    $("notifPanel").hidden = true;
    const h = location.hash.replace(/^#/, "") || "/";
    let m;
    if ((m = h.match(/^\/jobs\/(\d+)$/))) { cargando(); vistaActual = () => vistaDetalle(Number(m[1])); }
    else if (h === "/nueva") { vistaActual = null; return vistaNueva(); }
    else if (h === "/clientes") { cargando(); vistaActual = () => vistaClientes(); }
    else if (h === "/tecnicos") { cargando(); vistaActual = () => vistaTecnicos(); }
    // pantallas de edición: sin repintado en tiempo real (borraría lo que se está escribiendo)
    else if (h === "/config") { cargando(); vistaActual = null; return vistaConfig(); }
    else if (h === "/listas") { cargando(); vistaActual = null; return vistaListas(); }
    else if (h === "/plantillas") { cargando(); vistaActual = null; return vistaPlantillas(); }
    else { cargando(); vistaActual = () => vistaTablero(); }
    vistaActual();
  }
  window.addEventListener("hashchange", enrutar);
  $("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("loginBtn").disabled = true;
    const { error } = await sb.auth.signInWithPassword({ email: $("loginEmail").value.trim(), password: $("loginPass").value });
    $("loginBtn").disabled = false; $("loginPass").value = "";
    if (error) return mostrarLogin("Usuario o contraseña incorrectos.");
    entrar();
  });
  $("btnSalir").addEventListener("click", async (e) => { e.preventDefault(); yo = null; await sb.auth.signOut(); mostrarLogin(); });
  sb.auth.onAuthStateChange((ev, s) => { if (ev === "TOKEN_REFRESHED" && s) sb.realtime.setAuth(s.access_token); });
  sb.auth.getSession().then(({ data }) => (data.session ? entrar() : mostrarLogin()));
})();
