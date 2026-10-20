/* L&C Cliente (web): mismo backend y mismos contratos que L&C Customer Android (/api/v2/customer/…).
   Sin innerHTML con datos (todo se construye con nodos de texto). Token solo en este navegador. */
"use strict";

const API = "/api/v2";
const NIVELES = ["GOOD", "BETTER", "BEST"];
const CATEGORIAS = ["ELECTRODOMESTICOS", "HVAC", "PLOMERIA", "ELECTRICIDAD", "OTRO"];
const TIPOS_PROP = ["Casa", "Apartamento", "Oficina", "Hotel", "Otro"];
const ACCESOS = ["Ninguno", "Código puerta", "Garaje", "Lockbox", "Vecino", "Llave escondida"];
const ESTADOS = {
  "PENDIENTE": "Recibida", "EN RUTA": "Técnico en camino", "LLEGUÉ": "Técnico en el lugar",
  "EN REPARACIÓN": "En reparación", "ESPERANDO APROBACIÓN": "Esperando tu aprobación",
  "PART HOLD": "Esperando repuesto", "PAUSADO": "Pausada", "COMPLETADO": "Completada", "CANCELADO": "Cancelada"
};

// ── almacenamiento (puede fallar en modo privado) ─────────────────────────
const S = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* solo esta sesión */ } },
  del(k) { try { localStorage.removeItem(k); } catch (e) { /* nada */ } }
};
let memToken = null;
const token = () => memToken || S.get("lc_token");

// ── utilidades de DOM ──────────────────────────────────────────────────────
function el(tag, attrs, ...hijos) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") n.className = v;
    else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v === true ? "" : v);
  }
  for (const h of hijos.flat()) if (h !== null && h !== undefined && h !== false) n.append(h.nodeType ? h : String(h));
  return n;
}
const vista = () => document.getElementById("vista");
function pintar(...nodos) { const v = vista(); v.replaceChildren(...nodos); window.scrollTo(0, 0); }
function aviso(t) {
  const a = document.getElementById("aviso"); a.textContent = t; a.hidden = false;
  clearTimeout(aviso._t); aviso._t = setTimeout(() => { a.hidden = true; }, 3500);
}
const dinero = n => (n === null || n === undefined) ? "—" : "$" + Number(n).toFixed(2);
// las horas se muestran en la zona de la orden (la de la casa), no en la del navegador
let zonaOrden = null;
function fecha(ms) {
  if (!ms) return "—";
  const o = { weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" };
  if (zonaOrden) o.timeZone = zonaOrden;
  try { return new Date(Number(ms)).toLocaleString("es-US", o); }
  catch (e) { delete o.timeZone; return new Date(Number(ms)).toLocaleString("es-US", o); }
}
function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
}
const idFoto = () => 1 + Math.floor(Math.random() * 9e15);   // número por foto: el reintento no la duplica

// ── API ────────────────────────────────────────────────────────────────────
class ErrorApi extends Error { constructor(m, code) { super(m); this.code = code; } }

async function api(metodo, ruta, cuerpo, crudo) {
  const h = {};
  if (token()) h.Authorization = "Bearer " + token();
  let body;
  if (cuerpo instanceof FormData) body = cuerpo;
  else if (cuerpo !== undefined) { h["Content-Type"] = "application/json"; body = JSON.stringify(cuerpo); }
  let r;
  try {
    r = await fetch(API + ruta, { method: metodo, headers: h, body, cache: "no-store" });
  } catch (e) {
    throw new ErrorApi("Sin conexión con L&C. Revisa la Wi-Fi e inténtalo de nuevo.", 0);
  }
  if (r.status === 401 && ruta !== "/auth/login") { salir(); throw new ErrorApi("Tu sesión terminó. Vuelve a entrar.", 401); }
  if (crudo) { if (!r.ok) throw new ErrorApi("No disponible (" + r.status + ")", r.status); return r; }
  let j = {};
  try { j = await r.json(); } catch (e) { /* sin cuerpo */ }
  if (!r.ok) throw new ErrorApi(j.error || ("Error " + r.status), r.status);
  return j;
}

function entrarCon(j) {
  if (j.role !== "CUSTOMER") throw new ErrorApi("Este portal es para clientes. Usa la app de técnico o L&C Control.", 403);
  memToken = j.token; S.set("lc_token", j.token); S.set("lc_nombre", j.displayName || "");
}
function salir() {
  memToken = null; S.del("lc_token"); S.del("lc_nombre");
  if (location.hash !== "#/entrar") location.hash = "#/entrar";
}

// ── fotos: el iPhone entrega HEIC/JPEG grandes; se convierten a JPEG ≤ 1600 px en el navegador ──
async function aJpeg(archivo) {
  const url = URL.createObjectURL(archivo);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    const lado = Math.max(img.naturalWidth, img.naturalHeight);
    const f = lado > 1600 ? 1600 / lado : 1;
    const c = document.createElement("canvas");
    c.width = Math.round(img.naturalWidth * f); c.height = Math.round(img.naturalHeight * f);
    c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
    const blob = await new Promise(ok => c.toBlob(ok, "image/jpeg", 0.85));
    if (!blob) throw new Error("no se pudo convertir");
    return blob;
  } finally { URL.revokeObjectURL(url); }
}

async function miniatura(fotoId) {
  const img = el("img", { alt: "Foto del servicio" });
  try {
    const r = await api("GET", "/photos/" + fotoId, undefined, true);
    img.src = URL.createObjectURL(await r.blob());
  } catch (e) { img.alt = "Foto no disponible"; }
  return img;
}

// ── firma táctil ───────────────────────────────────────────────────────────
function lienzoFirma() {
  const c = el("canvas", { class: "firma", "aria-label": "Firma con el dedo" });
  let trazos = 0, dibujando = false, ctx;
  const ajustar = () => {
    const r = c.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    c.width = Math.max(1, Math.round(r.width * dpr)); c.height = Math.max(1, Math.round(r.height * dpr));
    ctx = c.getContext("2d"); ctx.scale(dpr, dpr); ctx.lineWidth = 2.5; ctx.lineCap = "round"; ctx.strokeStyle = "#111";
    ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, r.width, r.height); trazos = 0;
  };
  const punto = e => { const r = c.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
  c.addEventListener("pointerdown", e => { e.preventDefault(); try { c.setPointerCapture(e.pointerId); } catch (x) { /* sin captura */ } dibujando = true; ctx.beginPath(); ctx.moveTo(...punto(e)); });
  c.addEventListener("pointermove", e => { if (!dibujando) return; e.preventDefault(); ctx.lineTo(...punto(e)); ctx.stroke(); trazos++; });
  const fin = () => { dibujando = false; };
  c.addEventListener("pointerup", fin); c.addEventListener("pointercancel", fin);
  requestAnimationFrame(ajustar);
  return {
    nodo: c,
    vacia: () => trazos < 5,
    limpiar: ajustar,
    png: () => c.toDataURL("image/png").split(",")[1]
  };
}

// ── pantallas ──────────────────────────────────────────────────────────────
function campo(etiqueta, attrs) {
  const id = "c" + Math.random().toString(36).slice(2);
  const tag = attrs && attrs.tag || "input";
  const a = Object.assign({ id }, attrs); delete a.tag;
  const input = el(tag, a);
  return { nodo: el("div", {}, el("label", { for: id, text: etiqueta }), input), input };
}
function selector(etiqueta, opciones, valor) {
  const id = "s" + Math.random().toString(36).slice(2);
  const s = el("select", { id }, opciones.map(o => {
    const [v, t] = Array.isArray(o) ? o : [o, o];
    return el("option", { value: v, selected: String(v) === String(valor) }, t);
  }));
  return { nodo: el("div", {}, el("label", { for: id, text: etiqueta }), s), input: s };
}

function pantallaEntrar() {
  const u = campo("Usuario", { autocomplete: "username", autocapitalize: "none", autocorrect: "off", spellcheck: "false" });
  const p = campo("Contraseña", { type: "password", autocomplete: "current-password" });
  const err = el("p", { class: "error" });
  const btn = el("button", { type: "button", text: "Entrar" });
  btn.addEventListener("click", async () => {
    err.textContent = "";
    if (!u.input.value.trim() || !p.input.value) { err.textContent = "Escribe usuario y contraseña"; return; }
    btn.disabled = true;
    try {
      entrarCon(await api("POST", "/auth/login", { username: u.input.value.trim(), password: p.input.value }));
      p.input.value = ""; location.hash = "#/";
    } catch (e) { err.textContent = e.code === 401 ? "Usuario o contraseña incorrectos" : e.message; }
    btn.disabled = false;
  });
  pintar(el("h1", { text: "Servicio técnico a domicilio" }),
    el("p", { class: "sec", text: "Inicia sesión para solicitar un servicio y seguir tus órdenes." }),
    u.nodo, p.nodo, err, btn,
    el("button", { type: "button", class: "sec2", text: "Crear cuenta", onclick: () => { location.hash = "#/registro"; } }));
}

function pantallaRegistro() {
  const n = campo("Nombre completo", { autocomplete: "name" });
  const t = campo("Teléfono (10 dígitos, opcional)", { type: "tel", inputmode: "tel", autocomplete: "tel" });
  const u = campo("Usuario (3-40: letras, números, . _ -)", { autocomplete: "username", autocapitalize: "none", autocorrect: "off", spellcheck: "false" });
  const p = campo("Contraseña (mínimo 8)", { type: "password", autocomplete: "new-password" });
  const p2 = campo("Repite la contraseña", { type: "password", autocomplete: "new-password" });
  const err = el("p", { class: "error" });
  const btn = el("button", { type: "button", text: "Crear cuenta" });
  btn.addEventListener("click", async () => {
    err.textContent = "";
    if (p.input.value !== p2.input.value) { err.textContent = "Las contraseñas no coinciden"; return; }
    btn.disabled = true;
    try {
      const j = await api("POST", "/auth/register", { username: u.input.value.trim(), password: p.input.value,
        nombre: n.input.value.trim(), telefono: t.input.value.trim() });
      entrarCon(j); p.input.value = p2.input.value = ""; aviso("Cuenta creada"); location.hash = "#/propiedades";
    } catch (e) { err.textContent = e.message; }
    btn.disabled = false;
  });
  pintar(el("h1", { text: "Crear cuenta" }),
    el("p", { class: "sec", text: "Laboratorio: usa datos ficticios, no tus datos reales." }),
    n.nodo, t.nodo, u.nodo, p.nodo, p2.nodo, err, btn,
    el("button", { type: "button", class: "enlace", text: "Ya tengo cuenta", onclick: () => { location.hash = "#/entrar"; } }));
}

async function pantallaInicio() {
  zonaOrden = "America/New_York";
  const lista = el("div", {}, el("p", { class: "sec", text: "Cargando tus solicitudes…" }));
  pintar(el("h1", { text: "Hola, " + ((S.get("lc_nombre") || "").split(" ")[0] || "cliente") }),
    el("p", { class: "sec", text: "Tus servicios con L&C" }),
    el("button", { type: "button", text: "Solicitar servicio", onclick: () => { location.hash = "#/solicitar"; } }),
    el("button", { type: "button", class: "sec2", text: "Mis propiedades y equipos", onclick: () => { location.hash = "#/propiedades"; } }),
    el("h2", { text: "Mis solicitudes" }), lista,
    el("button", { type: "button", class: "enlace", text: "Cerrar sesión", onclick: salir }));
  try {
    const { requests } = await api("GET", "/customer/requests");
    if (!requests.length) { lista.replaceChildren(el("p", { class: "sec", text: "Aún no tienes solicitudes." })); return; }
    lista.replaceChildren(...requests.map(r => el("div", { class: "tarjeta click", role: "button", tabindex: "0",
        onclick: () => { location.hash = "#/orden/" + r.id; } },
      el("div", { class: "fila" }, el("strong", { text: "Nº " + r.id }),
        el("span", { class: "chip" + (r.estado === "COMPLETADO" ? " ok" : ""), text: ESTADOS[r.estado] || r.estado })),
      el("div", { text: r.descripcion }),
      el("div", { class: "sec", text: (r.programadoPara ? "Cita: " + fecha(r.programadoPara) : "Cita por confirmar") + " · " + (r.direccion || "") }))));
  } catch (e) { lista.replaceChildren(el("p", { class: "error", text: e.message })); }
}

async function pantallaPropiedades() {
  const cont = el("div", {}, el("p", { class: "sec", text: "Cargando…" }));
  pintar(el("button", { type: "button", class: "enlace", text: "← Volver", onclick: () => { location.hash = "#/"; } }),
    el("h1", { text: "Mis propiedades" }), cont);
  let props;
  try { props = (await api("GET", "/customer/properties")).properties; }
  catch (e) { cont.replaceChildren(el("p", { class: "error", text: e.message })); return; }
  const nodos = props.map(p => {
    const eqs = (p.equipos || []).map(e => el("li", { text: [e.nombre, e.marca, e.modelo].filter(Boolean).join(" · ") }));
    const nombre = campo("Equipo (ej. Nevera de la cocina)");
    const marca = campo("Marca (opcional)"), modelo = campo("Modelo (opcional)");
    const tipo = selector("Tipo", CATEGORIAS, "ELECTRODOMESTICOS");
    const err = el("p", { class: "error" });
    const form = el("div", { hidden: true }, nombre.nodo, tipo.nodo, marca.nodo, modelo.nodo, err);
    const btn = el("button", { type: "button", class: "sec2", text: "+ Agregar equipo" });
    btn.addEventListener("click", async () => {
      if (form.hidden) { form.hidden = false; btn.textContent = "Guardar equipo"; return; }
      btn.disabled = true; err.textContent = "";
      try {
        await api("POST", "/customer/properties/" + p.id + "/equipment", { nombre: nombre.input.value.trim(),
          tipo: tipo.input.value, marca: marca.input.value.trim() || null, modelo: modelo.input.value.trim() || null });
        aviso("Equipo guardado"); pantallaPropiedades();
      } catch (e) { err.textContent = e.message; btn.disabled = false; }
    });
    return el("div", { class: "tarjeta" }, el("strong", { text: p.direccion }),
      el("div", { class: "sec", text: (p.tipoPropiedad || "") + " · acceso: " + (p.tipoAcceso || "—") + (p.tieneCodigoAcceso ? " (código guardado)" : "") }),
      el("div", { class: "sec", text: "Equipos:" }), eqs.length ? el("ul", {}, eqs) : el("p", { class: "sec", text: "Sin equipos" }),
      form, btn);
  });
  // alta de propiedad
  const dir = campo("Dirección completa", { autocomplete: "street-address" });
  const tipo = selector("Tipo de propiedad", TIPOS_PROP, "Casa");
  const acc = selector("Acceso", ACCESOS, "Ninguno");
  const cod = campo("Código de acceso (opcional; se guarda cifrado)", { autocomplete: "off" });
  const ins = campo("Instrucciones (opcional)");
  const mas = campo("Mascotas (opcional)");
  const err = el("p", { class: "error" });
  const btn = el("button", { type: "button", text: "Guardar propiedad" });
  btn.addEventListener("click", async () => {
    btn.disabled = true; err.textContent = "";
    try {
      await api("POST", "/customer/properties", { direccion: dir.input.value.trim(), tipoPropiedad: tipo.input.value,
        tipoAcceso: acc.input.value, codigoAcceso: cod.input.value.trim(), instrucciones: ins.input.value.trim() || null,
        mascota: mas.input.value.trim() || null });
      aviso("Propiedad guardada"); pantallaPropiedades();
    } catch (e) { err.textContent = e.message; btn.disabled = false; }
  });
  cont.replaceChildren(...(nodos.length ? nodos : [el("p", { class: "sec", text: "Aún no tienes propiedades." })]),
    el("h2", { text: "Agregar propiedad" }), el("p", { class: "sec", text: "Laboratorio: usa una dirección ficticia." }),
    dir.nodo, tipo.nodo, acc.nodo, cod.nodo, ins.nodo, mas.nodo, err, btn);
}

// estado del formulario de solicitud (sobrevive a errores de red: mismo clientRequestId y mismos ids de foto)
let borrador = null;

async function pantallaSolicitar() {
  if (!borrador) borrador = { crid: uuid(), fotos: [], jobId: null };
  const cont = el("div", {}, el("p", { class: "sec", text: "Cargando…" }));
  pintar(el("button", { type: "button", class: "enlace", text: "← Volver", onclick: () => { location.hash = "#/"; } }),
    el("h1", { text: "Solicitar servicio" }), el("p", { class: "sec", text: "Cuéntanos qué necesitas" }), cont);
  let props;
  try { props = (await api("GET", "/customer/properties")).properties; }
  catch (e) { cont.replaceChildren(el("p", { class: "error", text: e.message })); return; }
  if (!props.length) {
    cont.replaceChildren(el("p", { text: "Primero registra tu propiedad." }),
      el("button", { type: "button", text: "Registrar propiedad", onclick: () => { location.hash = "#/propiedades"; } }));
    return;
  }
  const prop = selector("¿Dónde?", props.map(p => [p.id, p.direccion]), props[0].id);
  const eq = selector("¿Qué equipo?", [["", "Sin especificar"]], "");
  const llenarEquipos = () => {
    const p = props.find(x => String(x.id) === prop.input.value);
    eq.input.replaceChildren(el("option", { value: "" }, "Sin especificar"),
      ...(p.equipos || []).map(e => el("option", { value: e.id }, [e.nombre, e.marca, e.modelo].filter(Boolean).join(" · "))));
  };
  prop.input.addEventListener("change", llenarEquipos); llenarEquipos();
  const cat = selector("Tipo de servicio", CATEGORIAS, "ELECTRODOMESTICOS");
  const desc = campo("Describe el problema (mín. 10 caracteres)", { tag: "textarea", placeholder: "Ej. La nevera no enfría desde ayer" });
  const fechaPref = campo("Fecha y hora preferidas (opcional)", { type: "datetime-local" });
  const ventana = campo("Otra disponibilidad (ej. tardes después de las 3)");
  const presente = selector("¿Habrá alguien en casa?", [["", "No sé"], ["true", "Sí"], ["false", "No"]], "");
  // fotos: cámara o archivos del iPhone
  const miniaturas = el("div", { class: "fotos" });
  const contador = el("p", { class: "sec" });
  const repintarFotos = () => {
    miniaturas.replaceChildren(...borrador.fotos.map(f => el("img", { src: f.url, alt: "Foto adjunta" })));
    contador.textContent = borrador.fotos.length ? borrador.fotos.length + " foto(s) lista(s)" : "Sin fotos (opcional, máx. 6)";
  };
  const agregar = async archivos => {
    for (const a of archivos) {
      if (borrador.fotos.length >= 6) { aviso("Máximo 6 fotos"); break; }
      try {
        const blob = await aJpeg(a);
        borrador.fotos.push({ blob, url: URL.createObjectURL(blob), cpid: idFoto(), subida: false });
      } catch (e) { aviso("No se pudo leer una foto"); }
    }
    repintarFotos();
  };
  const camara = el("input", { type: "file", accept: "image/*", capture: "environment", hidden: true });
  const galeria = el("input", { type: "file", accept: "image/*", multiple: true, hidden: true });
  for (const i of [camara, galeria]) i.addEventListener("change", () => { agregar(Array.from(i.files)); i.value = ""; });
  repintarFotos();
  const err = el("p", { class: "error" });
  const enviar = el("button", { type: "button", text: "Enviar solicitud" });
  enviar.addEventListener("click", async () => {
    err.textContent = ""; enviar.disabled = true;
    try {
      if (!borrador.jobId) {
        const cuerpo = { propertyId: Number(prop.input.value), descripcion: desc.input.value.trim(), categoria: cat.input.value,
          clientRequestId: borrador.crid, zonaHoraria: Intl.DateTimeFormat().resolvedOptions().timeZone };
        if (eq.input.value) cuerpo.equipmentId = Number(eq.input.value);
        if (fechaPref.input.value) cuerpo.fechaPreferida = new Date(fechaPref.input.value).getTime();
        if (ventana.input.value.trim()) cuerpo.ventanaPreferida = ventana.input.value.trim();
        if (presente.input.value) cuerpo.clientePresente = presente.input.value === "true";
        borrador.jobId = (await api("POST", "/customer/requests", cuerpo)).id;
      }
      for (const f of borrador.fotos) {
        if (f.subida) continue;
        const fd = new FormData();
        fd.append("foto", f.blob, "foto.jpg"); fd.append("categoria", "CLIENTE"); fd.append("clientPhotoId", String(f.cpid));
        await api("POST", "/jobs/" + borrador.jobId + "/photos", fd);
        f.subida = true;
      }
      const jid = borrador.jobId, n = borrador.fotos.length;
      borrador.fotos.forEach(f => URL.revokeObjectURL(f.url)); borrador = null;
      pintar(el("h1", { text: "Solicitud enviada" }),
        el("div", { class: "tarjeta" }, el("div", { class: "sec", text: "Tu número de orden" }),
          el("div", { class: "precio", text: "Nº " + jid }), el("p", { text: "Estado: Recibida por L&C. " + n + " foto(s) enviada(s)." }),
          el("p", { class: "sec", text: "La cita la confirma L&C; te avisaremos aquí." })),
        el("button", { type: "button", text: "Ver mi solicitud", onclick: () => { location.hash = "#/orden/" + jid; } }),
        el("button", { type: "button", class: "sec2", text: "Inicio", onclick: () => { location.hash = "#/"; } }));
    } catch (e) {
      err.textContent = e.message + (borrador && borrador.jobId ? " · La solicitud Nº " + borrador.jobId + " ya está creada; vuelve a tocar Enviar para terminar las fotos (no se duplica)." : "");
      enviar.disabled = false;
      enviar.textContent = "Reintentar envío";
    }
  });
  cont.replaceChildren(prop.nodo, eq.nodo, cat.nodo, desc.nodo, fechaPref.nodo, ventana.nodo,
    el("p", { class: "sec", text: "La cita la confirma L&C; te avisaremos." }), presente.nodo,
    el("h2", { text: "Fotos del equipo y del área" }), contador, miniaturas, camara, galeria,
    el("button", { type: "button", class: "sec2", text: "📷 Tomar foto", onclick: () => camara.click() }),
    el("button", { type: "button", class: "sec2", text: "🖼 Elegir de fotos o archivos", onclick: () => galeria.click() }),
    err, enviar);
}

// ── detalle de la orden ───────────────────────────────────────────────────
// numeración fija por casilla (GOOD=1, BETTER=2, BEST=3), la misma que ven el técnico y despacho
function tituloOpcion(o) { return "OPCIÓN " + (NIVELES.indexOf(o.nivel) + 1) + " — " + (o.titulo || "Sin título"); }

function bloquePropuesta(p, recargar) {
  const ops = (p.opciones || []).slice().sort((a, b) => NIVELES.indexOf(a.nivel) - NIVELES.indexOf(b.nivel));
  const caja = el("div", { class: "tarjeta" }, el("h2", { text: "Propuesta del técnico · versión " + p.revision }));
  if (p.estado !== "PENDIENTE") {
    const i = ops.findIndex(o => o.nivel === p.opcionElegida);
    caja.append(el("p", { class: p.estado === "APROBADA" ? "ok" : "sec",
      text: p.estado === "APROBADA" ? "✓ Aprobaste " + (i >= 0 ? tituloOpcion(ops[i], i) : "") + " · " + dinero(p.totalAceptado) + (p.firmante ? " · firmó " + p.firmante : "")
        : ({ RECHAZADA: "Rechazaste esta propuesta", SUPERADA: "Reemplazada por una versión nueva", VENCIDA: "Venció; pide una nueva al técnico" }[p.estado] || p.estado) }));
    return caja;
  }
  let elegida = ops.length === 1 ? ops[0].nivel : null;
  const tarjetas = ops.map((o, i) => {
    const t = el("div", { class: "opcion" + (elegida === o.nivel ? " elegida" : ""), role: "radio", tabindex: "0" },
      el("div", { class: "fila" }, el("span", { class: "titulo", text: tituloOpcion(o, i) }), el("span", { class: "precio", text: dinero(o.total) })),
      o.descripcion ? el("p", { text: o.descripcion }) : null,
      o.equipoPropuesto ? el("p", { class: "sec", text: "Equipo propuesto: " + o.equipoPropuesto }) : null,
      o.especificaciones ? el("p", { class: "sec", text: "Especificaciones: " + o.especificaciones }) : null,
      o.garantia ? el("p", { class: "sec", text: "Garantía: " + o.garantia }) : null);
    t.addEventListener("click", () => { elegida = o.nivel; tarjetas.forEach((x, k) => x.classList.toggle("elegida", ops[k].nivel === elegida)); resumen(); });
    return t;
  });
  const res = el("p", { class: "sec" });
  const resumen = () => {
    const i = ops.findIndex(o => o.nivel === elegida);
    res.textContent = i < 0 ? "Elige una opción para firmar." : "Vas a autorizar " + tituloOpcion(ops[i], i) + " por " + dinero(ops[i].total) + " (versión " + p.revision + ").";
  };
  resumen();
  const nombre = campo("Tu nombre completo", { autocomplete: "name" });
  const firma = lienzoFirma();
  const err = el("p", { class: "error" });
  const aprobar = el("button", { type: "button", text: "Aprobar y firmar" });
  const rechazar = el("button", { type: "button", class: "sec2", text: "Rechazar" });
  const decidir = async decision => {
    err.textContent = "";
    const cuerpo = { decision, contenidoSha256: p.contenidoSha256 };
    if (decision === "APROBADA") {
      if (!elegida) { err.textContent = "Elige una opción"; return; }
      if (nombre.input.value.trim().length < 3) { err.textContent = "Escribe tu nombre completo"; return; }
      if (firma.vacia()) { err.textContent = "Firma en el recuadro"; return; }
      Object.assign(cuerpo, { opcion: elegida, firmante: nombre.input.value.trim(), firmaPngBase64: firma.png() });
    } else if (!confirm("¿Rechazar la propuesta?")) return;
    aprobar.disabled = rechazar.disabled = true;
    try { await api("POST", "/customer/proposals/" + p.id + "/decide", cuerpo); aviso(decision === "APROBADA" ? "Propuesta aprobada" : "Propuesta rechazada"); recargar(); }
    catch (e) { err.textContent = e.message; aprobar.disabled = rechazar.disabled = false; }
  };
  aprobar.addEventListener("click", () => decidir("APROBADA"));
  rechazar.addEventListener("click", () => decidir("RECHAZADA"));
  caja.append(el("p", { class: "sec", text: "Vence: " + fecha(p.expiresAt) }), ...tarjetas, res, nombre.nodo,
    el("label", { text: "Firma aquí con el dedo" }), firma.nodo,
    el("button", { type: "button", class: "enlace", text: "Borrar firma", onclick: () => firma.limpiar() }), err, aprobar, rechazar);
  return caja;
}

function bloqueVariacion(v, recargar) {
  const sube = v.diferencia > 0;
  const caja = el("div", { class: "tarjeta" }, el("h2", { text: "⚠ Aprobación adicional requerida" }),
    el("div", { class: "franja " + (sube ? "roja" : "naranja"),
      text: "Aprobaste " + dinero(v.aprobado) + "; el trabajo realizado suma " + dinero(v.facturado) + " (" + (sube ? "+" : "") + dinero(v.diferencia).replace("$-", "-$") + ")." }),
    el("table", {}, (v.items || []).map(i => el("tr", {}, el("td", { text: i.cantidad + " × " + i.descripcion }), el("td", { class: "num", text: dinero(i.importe) })))));
  if (v.estado !== "PENDIENTE") { caja.append(el("p", { class: "sec", text: "Decisión: " + v.estado })); return caja; }
  const nombre = campo("Tu nombre completo", { autocomplete: "name" });
  const firma = lienzoFirma();
  const err = el("p", { class: "error" });
  const ok = el("button", { type: "button", text: "Aprobar la diferencia y firmar" });
  const no = el("button", { type: "button", class: "sec2", text: "No apruebo la diferencia" });
  const decidir = async decision => {
    const cuerpo = { decision, itemsSha256: v.itemsSha256 };
    if (decision === "APROBADA") {
      if (nombre.input.value.trim().length < 3) { err.textContent = "Escribe tu nombre completo"; return; }
      if (firma.vacia()) { err.textContent = "Firma en el recuadro"; return; }
      Object.assign(cuerpo, { firmante: nombre.input.value.trim(), firmaPngBase64: firma.png() });
    }
    ok.disabled = no.disabled = true;
    try { await api("POST", "/customer/variances/" + v.id + "/decide", cuerpo); aviso("Decisión enviada"); recargar(); }
    catch (e) { err.textContent = e.message; ok.disabled = no.disabled = false; }
  };
  ok.addEventListener("click", () => decidir("APROBADA"));
  no.addEventListener("click", () => decidir("RECHAZADA"));
  caja.append(nombre.nodo, el("label", { text: "Firma aquí con el dedo" }), firma.nodo,
    el("button", { type: "button", class: "enlace", text: "Borrar firma", onclick: () => firma.limpiar() }), err, ok, no);
  return caja;
}

function bloqueFactura(o) {
  const f = o.factura;
  const caja = el("div", { class: "tarjeta" }, el("h2", { text: "Factura " + (f.numero || "") }));
  const c = f.comparacion;
  if (c && c.tipo && c.tipo !== "IGUAL") {
    const sube = c.diferencia > 0;
    const aprobadaVar = c.variacionEstado === "APROBADA";
    caja.append(el("div", { class: "franja " + (aprobadaVar ? "verde" : sube ? "roja" : "naranja"),
      text: (sube ? "⚠ " + dinero(c.diferencia) + " por encima" : "ℹ " + dinero(Math.abs(c.diferencia)) + " por debajo") +
        " de lo aprobado (" + dinero(c.aprobado) + ")" + (aprobadaVar ? " · aprobaste la diferencia" : "") }));
  }
  caja.append(el("table", {},
    (f.items || []).map(i => el("tr", {}, el("td", { text: i.cantidad + " × " + i.descripcion }), el("td", { class: "num", text: dinero(i.importe) }))),
    el("tr", {}, el("td", { text: "Subtotal" }), el("td", { class: "num", text: dinero(f.subtotal) })),
    f.descuento ? el("tr", {}, el("td", { text: "Descuento autorizado" }), el("td", { class: "num", text: "-" + dinero(f.descuento) })) : null,
    el("tr", {}, el("td", { text: "Impuesto" }), el("td", { class: "num", text: dinero(f.impuesto) })),
    el("tr", { class: "total" }, el("td", { text: "Total" }), el("td", { class: "num", text: dinero(f.total) }))));
  const estado = f.estado === "PAGADA" ? "PAGADA (pago confirmado)" : f.estado === "EMITIDA" ? "Emitida · pendiente de pago" : f.estado;
  caja.append(el("p", {}, el("span", { class: "chip" + (f.estado === "PAGADA" ? " ok" : ""), text: estado })),
    el("p", { class: "sec", text: "Pagado " + dinero(f.pagado) + " · saldo " + dinero(f.saldo) }));
  for (const p of o.pagos || []) caja.append(el("p", { class: "sec", text: dinero(p.monto) + " · " + p.metodo + " · " + (p.estado === "CONFIRMADO" ? "confirmado" : p.estado === "REPORTADO" ? "en revisión" : p.estado) }));
  if (f.tienePdf) {
    const b = el("button", { type: "button", class: "sec2", text: "Ver factura (PDF)" });
    b.addEventListener("click", async () => {
      b.disabled = true;
      try {
        const r = await api("GET", "/customer/requests/" + o.id + "/factura.pdf", undefined, true);
        const url = URL.createObjectURL(new Blob([await r.blob()], { type: "application/pdf" }));
        location.href = url;            // Safari abre su visor de PDF (todas las páginas); "atrás" vuelve aquí
      } catch (e) { aviso(e.message); }
      b.disabled = false;
    });
    caja.append(b);
  }
  return caja;
}

let temporizador = null;
async function pantallaOrden(jid) {
  const cuerpo = el("div", {}, el("p", { class: "sec", text: "Cargando…" }));
  const actualizar = el("button", { type: "button", class: "sec2", text: "Actualizar" });
  pintar(el("button", { type: "button", class: "enlace", text: "← Volver", onclick: () => { location.hash = "#/"; } }),
    el("h1", { text: "Tu solicitud" }), el("p", { class: "sec", text: "Nº " + jid }), cuerpo, actualizar);
  let firmando = false;
  const recargar = () => { firmando = false; return cargar(); };   // tras decidir, siempre repintar
  const cargar = async () => {
    if (firmando) return;                      // no repintar mientras la clienta firma
    let o;
    try { o = await api("GET", "/customer/requests/" + jid); }
    catch (e) { cuerpo.replaceChildren(el("p", { class: "error", text: e.message })); return; }
    zonaOrden = o.zonaHoraria || "America/New_York";
    const fotos = el("div", { class: "fotos" });
    const nodos = [
      el("p", {}, el("span", { class: "chip" + (o.estado === "COMPLETADO" ? " ok" : ""), text: ESTADOS[o.estado] || o.estado })),
      el("div", { class: "tarjeta" },
        el("div", { class: "sec", text: "Problema" }), el("div", { text: o.descripcion }),
        el("div", { class: "sec", text: "Cita" }), el("div", { text: o.programadoPara ? fecha(o.programadoPara) : "Por confirmar" + (o.ventanaPreferida ? " · tu preferencia: " + (/^\d{12,}$/.test(o.ventanaPreferida) ? fecha(o.ventanaPreferida) : o.ventanaPreferida) : "") }),
        el("div", { class: "sec", text: "Técnico" }), el("div", { text: o.tecnicoNombre || "Por asignar" }),
        el("div", { class: "sec", text: "Dirección" }), el("div", { text: o.direccion || "—" }),
        o.equipo ? el("div", { class: "sec", text: "Equipo: " + o.equipo }) : null),
      el("h2", { text: "Fotos (" + (o.fotos || []).length + ")" }), fotos
    ];
    for (const p of o.propuestas || []) if (p.estado !== "SUPERADA" || (o.propuestas.length === 1)) nodos.push(bloquePropuesta(p, recargar));
    for (const v of o.variaciones || []) if (v.estado === "PENDIENTE" || !o.factura) nodos.push(bloqueVariacion(v, recargar));
    if ((o.trabajoRealizado || []).length && !o.factura)
      nodos.push(el("div", { class: "tarjeta" }, el("h2", { text: "Trabajo realizado" }),
        el("table", {}, o.trabajoRealizado.map(w => el("tr", {}, el("td", { text: w.cantidad + " × " + w.descripcion }), el("td", { class: "num", text: dinero(w.total) }))))));
    if (o.factura) nodos.push(bloqueFactura(o));
    nodos.push(el("h2", { text: "Historial" }), el("ul", { class: "historial" },
      (o.historial || []).map(h => el("li", { text: fecha(h.ts) + " · " + h.campo + (h.campo === "Técnico asignado" ? "" : ": " +
        (h.campo === "Estado" ? (ESTADOS[h.valor] || h.valor) : h.campo === "Cita" ? fecha(h.valor) : h.valor)) }))));
    cuerpo.replaceChildren(...nodos);
    // mientras haya una firma a medias no se recarga sola
    cuerpo.querySelectorAll("canvas.firma").forEach(c => c.addEventListener("pointerdown", () => { firmando = true; }, { once: true }));
    for (const f of o.fotos || []) fotos.append(await miniatura(f.id));
  };
  actualizar.addEventListener("click", recargar);
  await cargar();
  clearInterval(temporizador);
  temporizador = setInterval(() => { if (!document.hidden && location.hash === "#/orden/" + jid) cargar(); }, 20000);
}

// ── enrutador ─────────────────────────────────────────────────────────────
function ruta() {
  clearInterval(temporizador);
  const h = location.hash || "#/";
  const u = document.getElementById("usuario");
  u.textContent = token() ? (S.get("lc_nombre") || "") : "";
  if (!token()) return h === "#/registro" ? pantallaRegistro() : pantallaEntrar();
  if (h === "#/entrar" || h === "#/registro") { location.hash = "#/"; return; }
  if (h === "#/propiedades") return pantallaPropiedades();
  if (h === "#/solicitar") return pantallaSolicitar();
  const m = h.match(/^#\/orden\/(\d+)$/);
  if (m) return pantallaOrden(m[1]);
  return pantallaInicio();
}
window.addEventListener("hashchange", ruta);
document.addEventListener("DOMContentLoaded", ruta);
