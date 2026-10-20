package com.lc.technician.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lc.technician.model.JobEntity;
import com.lc.technician.sync.SyncModels;

import org.junit.Test;

/** Copia del servidor sobre la orden local (pull / conflicto). */
public class SyncV2MergeTest {

    private static JobEntity local() {
        JobEntity e = new JobEntity();
        e.id = 8000000000002L;
        e.codigoAcceso = "1357";
        e.ubicacionLlave = "Bajo maceta ficticia";
        e.customerId = 6L;      // id local: el servidor usa otros ids
        e.propertyId = 6L;
        e.estado = "EN RUTA";
        e.clientePresente = true;
        return e;
    }

    @Test public void reasignada_borra_secretos_locales() {
        JobEntity e = local();
        SyncModels.JobV2Dto s = new SyncModels.JobV2Dto();
        s.estado = "EN RUTA";
        s.technicianId = "tec-lab-02";
        s.codigoAccesoProtegido = true;
        s.ubicacionLlaveProtegido = true;
        SyncV2.aplicarServidor(e, s);
        assertEquals("", e.codigoAcceso);
        assertEquals("", e.ubicacionLlave);
        assertEquals("tec-lab-02", e.technicianId);
    }

    @Test public void dato_null_sin_proteccion_no_borra_lo_local() {
        JobEntity e = local();
        SyncModels.JobV2Dto s = new SyncModels.JobV2Dto();
        s.estado = "LLEGUÉ";
        SyncV2.aplicarServidor(e, s);
        assertEquals("1357", e.codigoAcceso);
        assertEquals("Bajo maceta ficticia", e.ubicacionLlave);
        assertEquals("LLEGUÉ", e.estado);
    }

    @Test public void conserva_ids_locales_y_presencia_si_no_viene() {
        JobEntity e = local();
        SyncModels.JobV2Dto s = new SyncModels.JobV2Dto();
        s.codigoAcceso = "2468";
        SyncV2.aplicarServidor(e, s);
        assertEquals(Long.valueOf(6L), e.customerId);
        assertEquals(Long.valueOf(6L), e.propertyId);
        assertTrue(e.clientePresente);
        assertEquals("2468", e.codigoAcceso);
        assertEquals("EN RUTA", e.estado);
    }

    @Test public void marcas_de_envio_unicas_y_crecientes() {
        long a = SyncV2.nuevaMarca(), b = SyncV2.nuevaMarca(), c = SyncV2.nuevaMarca();
        assertTrue(a < b && b < c);
    }
}
