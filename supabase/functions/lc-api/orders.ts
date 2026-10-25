// Operaciones de órdenes para lc-api. Puerto TypeScript del contrato ya probado en el backend
// Flask (40/40 en CI): versión obligatoria, merge de 3 vías, conflictos persistentes, idempotencia.
// NO DESPLEGADO. Requiere pruebas con sesión LAB antes de sustituir la versión activa.
export type Rol = "ADMIN" | "DISPATCHER" | "TECHNICIAN" | "CUSTOMER" | "SYNC";

export interface Job {
  id: number; version: number; estado: string; technician_code?: string | null;
  notas?: string | null; prioridad?: string | null; updated_at: number;
}

/** Campos que un rol puede escribir. Lista EXPLÍCITA: todo lo demás se ignora. */
export const PERMITIDOS: Record<Rol, ReadonlyArray<string>> = {
  ADMIN: ["estado", "notas", "prioridad", "horas", "programado_para", "technician_id", "technician_code"],
  DISPATCHER: ["estado", "notas", "prioridad", "horas", "programado_para", "technician_id", "technician_code"],
  TECHNICIAN: ["estado", "notas", "horas"],
  CUSTOMER: [],
  SYNC: ["estado", "notas", "prioridad", "horas", "programado_para"],
};

/** Nunca escribibles por API, para ningún rol. */
export const PROTEGIDOS: ReadonlyArray<string> = ["id", "customer_id", "version", "created_by", "created_at", "origin"];

/** Reglas de transición por rol: el técnico no reasigna ni reprograma. */
export function puedeEditar(rol: Rol, campo: string): boolean {
  if (PROTEGIDOS.includes(campo)) return false;
  return PERMITIDOS[rol]?.includes(campo) ?? false;
}

/** Versión obligatoria: sin esto un cliente saltaría el control de concurrencia. */
export function versionValida(v: unknown): v is number {
  return typeof v === "number" && Number.isInteger(v) && v >= 1;
}

export interface Resultado {
  status: "ACCEPTED" | "CONFLICT" | "REJECTED";
  version?: number;
  conflictFields?: string[];
  motivo?: string;
}

/**
 * Merge de 3 vías. `base` es la versión que el cliente leyó; `server` la actual.
 * - Campo no tocado por el cliente (igual a su base) → se queda el del servidor.
 * - Tocado solo por el cliente → se aplica.
 * - Tocado por ambos con valores distintos → CONFLICT, no se aplica y se registra.
 */
export function aplicar(
  server: Job,
  base: number,
  cambiosCliente: Record<string, unknown>,
  tocadosPorOtros: Set<string>,
  rol: Rol,
): { resultado: Resultado; cambios: Record<string, unknown>; conflictos: string[] } {
  if (!versionValida(base)) {
    return { resultado: { status: "REJECTED", motivo: "versión del servidor obligatoria (entero >= 1)" }, cambios: {}, conflictos: [] };
  }
  if (base > server.version) {
    return { resultado: { status: "CONFLICT", version: server.version, motivo: "versión futura" }, cambios: {}, conflictos: [] };
  }
  const cambios: Record<string, unknown> = {};
  const conflictos: string[] = [];
  for (const [campo, valor] of Object.entries(cambiosCliente)) {
    if (!puedeEditar(rol, campo)) continue;
    if (tocadosPorOtros.has(campo) && (server as any)[campo] !== valor) { conflictos.push(campo); continue; }
    if ((server as any)[campo] !== valor) cambios[campo] = valor;
  }
  if (conflictos.length) {
    return { resultado: { status: "CONFLICT", version: server.version, conflictFields: conflictos.sort(), motivo: "campos disputados" }, cambios, conflictos };
  }
  return { resultado: { status: "ACCEPTED", version: server.version + (Object.keys(cambios).length ? 1 : 0) }, cambios, conflictos };
}

/** Clave de idempotencia de recibos: mismo dispositivo + orden + marca del cliente = mismo recibo. */
export function receiptKey(deviceId: string, jobId: number, clientUpdatedAt: number): string {
  return `${deviceId}:${jobId}:${clientUpdatedAt}`;
}

/** Rutas que la función debe exponer (hoy ausentes: /orders → 404). */
export const RUTAS = ["POST /orders", "GET /orders/:id", "GET /orders?assigned=me", "PATCH /orders/:id"] as const;
