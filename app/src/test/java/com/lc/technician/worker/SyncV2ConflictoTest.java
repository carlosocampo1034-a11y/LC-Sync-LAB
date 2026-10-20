package com.lc.technician.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lc.technician.model.JobEntity;
import com.lc.technician.sync.SyncModels;

import java.util.Arrays;

import org.junit.Test;

/** Conflicto: el teléfono adopta la copia del servidor; el valor del técnico queda registrado y recuperable. */
public class SyncV2ConflictoTest {

    private static SyncModels.JobResultV2 conflicto(String... campos) {
        SyncModels.JobResultV2 c = new SyncModels.JobResultV2();
        c.id = 1L;
        c.status = "CONFLICT";
        c.version = 5;
        c.conflictFields = Arrays.asList(campos);
        c.conflictIds = Arrays.asList(42L);
        c.server = new SyncModels.JobV2Dto();
        c.server.estado = "EN RUTA";          // campo sin conflicto ya aplicado por el servidor
        c.server.notas = "DESPACHO: llamar antes";
        c.server.descripcion = "Descripción de despacho";
        return c;
    }

    private static JobEntity local() {
        JobEntity e = new JobEntity();
        e.id = 1L;
        e.estado = "EN RUTA";
        e.notas = "TECNICO: compresor revisado";
        e.descripcion = "Descripción del técnico";
        e.synced = 2;
        e.serverVersion = 4;
        return e;
    }

    @Test public void adopta_servidor_sin_concatenar_y_registra_valor_del_tecnico() {
        JobEntity e = local();
        SyncModels.JobResultV2 c = conflicto("notas");
        SyncV2.resolverConflicto(e, c, 1000L);
        assertEquals("DESPACHO: llamar antes", e.notas);
        assertEquals(1, e.synced);
        assertEquals(Integer.valueOf(5), e.serverVersion);
        String audit = SyncV2.descartadosTexto(e, c);
        assertTrue(audit, audit.contains("TECNICO: compresor revisado") && audit.contains("42") && audit.contains("pendiente"));
    }

    @Test public void otro_campo_registra_valor_local() {
        JobEntity e = local();
        SyncModels.JobResultV2 c = conflicto("descripcion");
        SyncV2.resolverConflicto(e, c, 1000L);
        assertEquals("Descripción de despacho", e.descripcion);
        assertTrue(SyncV2.descartadosTexto(e, c).contains("descripcion=Descripción del técnico"));
    }

    @Test public void secretos_nunca_en_claro_en_la_auditoria() {
        JobEntity e = local();
        e.codigoAcceso = "1357";
        SyncModels.JobResultV2 c = conflicto("codigo_acceso_enc");
        SyncV2.resolverConflicto(e, c, 1000L);
        String audit = SyncV2.descartadosTexto(e, c);
        assertTrue(!audit.contains("1357") && audit.contains("•••"));
    }
}
