package com.lc.technician;

import static org.junit.Assert.assertEquals;

import com.lc.technician.model.Job;
import com.lc.technician.model.JobEntity;

import org.junit.Test;

/**
 * Reescribir la fila con job.toEntity() (asignar cobertura, escanear membresía) no debe borrar
 * los datos de despacho ni la ubicación de la solicitud, y debe quedar pendiente de sincronizar.
 */
public class JobToEntityPreservaTest {

    private static Job solicitud() {
        Job j = new Job(1791974954591L, "Cliente QA", "(305) 555-0177", "200 Avenida Ficticia QA",
                "7:00 AM", "Equipo ficticio QA", "Por asignar", Job.STATUS_PENDING, "Casa", true,
                "Garaje", "", "", "", "", "", "");
        j.prioridad = "NORMAL";
        j.programadoPara = 1_792_000_000_000L;
        j.estadoDispatch = "QUEUE";
        j.latitud = 25.7617;
        j.longitud = -80.1918;
        j.ubicacionFuente = "GEOCODER";
        return j;
    }

    @Test public void toEntity_conservaCamposDeDespachoYUbicacion() {
        Job j = solicitud();
        j.coverageTypeId = 3L;  // el cambio que provoca la reescritura
        JobEntity e = j.toEntity();
        assertEquals("NORMAL", e.prioridad);
        assertEquals(Long.valueOf(1_792_000_000_000L), e.programadoPara);
        assertEquals("QUEUE", e.estadoDispatch);
        assertEquals(25.7617, e.latitud, 0.0);
        assertEquals(-80.1918, e.longitud, 0.0);
        assertEquals("GEOCODER", e.ubicacionFuente);
        assertEquals(Long.valueOf(3L), e.coverageTypeId);
    }

    @Test public void toEntity_quedaPendienteDeSincronizar() {
        assertEquals(0, solicitud().toEntity().synced);
    }

    @Test public void toEntity_sinUbicacion_quedaNula() {
        Job j = solicitud();
        j.latitud = null;
        j.longitud = null;
        j.ubicacionFuente = null;
        JobEntity e = j.toEntity();
        assertEquals(null, e.latitud);
        assertEquals(null, e.ubicacionFuente);
    }
}
