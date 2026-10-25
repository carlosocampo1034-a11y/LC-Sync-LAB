import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { aplicar, puedeEditar, versionValida, receiptKey, type Job } from "./orders.ts";

const job = (v = 1): Job => ({ id: 1, version: v, estado: "PENDIENTE", notas: null, technician_code: "T-A", updated_at: 0 });

Deno.test("versión ausente o inválida se rechaza", () => {
  assertEquals(versionValida(0), false);
  assertEquals(versionValida(-3), false);
  assertEquals(versionValida(1.5), false);
  assertEquals(aplicar(job(), 0 as any, { notas: "x" }, new Set(), "DISPATCHER").resultado.status, "REJECTED");
});

Deno.test("versión futura no se aplica", () => {
  const r = aplicar(job(3), 9, { notas: "x" }, new Set(), "DISPATCHER");
  assertEquals(r.resultado.status, "CONFLICT");
  assertEquals(r.cambios, {});
});

Deno.test("campos distintos con base vieja se aplican sin conflicto", () => {
  const r = aplicar(job(5), 3, { notas: "nueva" }, new Set(["estado"]), "DISPATCHER");
  assertEquals(r.resultado.status, "ACCEPTED");
  assertEquals(r.cambios, { notas: "nueva" });
});

Deno.test("mismo campo tocado por otro no se pisa", () => {
  const r = aplicar(job(5), 3, { estado: "CANCELADO" }, new Set(["estado"]), "DISPATCHER");
  assertEquals(r.resultado.status, "CONFLICT");
  assertEquals(r.resultado.conflictFields, ["estado"]);
  assertEquals(r.cambios, {});
});

Deno.test("el técnico no puede reasignar ni reprogramar", () => {
  assertEquals(puedeEditar("TECHNICIAN", "notas"), true);
  assertEquals(puedeEditar("TECHNICIAN", "technician_code"), false);
  assertEquals(puedeEditar("TECHNICIAN", "programado_para"), false);
  assertEquals(puedeEditar("CUSTOMER", "estado"), false);
});

Deno.test("campos protegidos no son editables por ningún rol", () => {
  for (const rol of ["ADMIN", "DISPATCHER", "TECHNICIAN", "CUSTOMER", "SYNC"] as const) {
    for (const campo of ["id", "customer_id", "version", "created_by", "created_at", "origin"]) {
      assertEquals(puedeEditar(rol, campo), false, `${rol} no debe poder escribir ${campo}`);
    }
  }
  assertEquals(puedeEditar("ADMIN", "technician_id"), true);
  assertEquals(puedeEditar("TECHNICIAN", "technician_id"), false);
});

Deno.test("idempotencia del recibo", () => {
  assertEquals(receiptKey("dev-a", 7, 1000), "dev-a:7:1000");
});
