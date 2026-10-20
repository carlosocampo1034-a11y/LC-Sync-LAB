package com.lc.technician;

import com.lc.technician.model.Job;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests para constantes de estado del Job.
 * Garantizan que los valores string no cambien accidentalmente
 * durante la integración BFS (son usados en migraciones y en Room).
 */
public class JobStatusTest {

    @Test
    public void estadoPendiente_valorExacto() {
        assertEquals("PENDIENTE", Job.STATUS_PENDING);
    }

    @Test
    public void estadoEnRuta_valorExacto() {
        assertEquals("EN RUTA", Job.STATUS_EN_ROUTE);
    }

    @Test
    public void estadoLlegue_valorExacto() {
        // Este valor tiene tilde — crítico para queries SQL
        assertEquals("LLEGUÉ", Job.STATUS_ARRIVED);
    }

    @Test
    public void estadoEsperandoAprobacion_valorExacto() {
        assertEquals("ESPERANDO APROBACIÓN", Job.STATUS_AWAITING_APPROVAL);
    }

    @Test
    public void estadoEnReparacion_valorExacto() {
        assertEquals("EN REPARACIÓN", Job.STATUS_EN_REPARACION);
    }

    @Test
    public void estadoPartHold_valorExacto() {
        assertEquals("PART HOLD", Job.STATUS_PART_HOLD);
    }

    @Test
    public void estadoPausado_valorExacto() {
        assertEquals("PAUSADO", Job.STATUS_ON_HOLD);
    }

    @Test
    public void estadoCompletado_valorExacto() {
        assertEquals("COMPLETADO", Job.STATUS_DONE);
    }

    @Test
    public void ochoEstadosDistintos() {
        String[] estados = {
            Job.STATUS_PENDING, Job.STATUS_EN_ROUTE, Job.STATUS_ARRIVED,
            Job.STATUS_AWAITING_APPROVAL, Job.STATUS_EN_REPARACION,
            Job.STATUS_PART_HOLD, Job.STATUS_ON_HOLD, Job.STATUS_DONE
        };
        // Verificar que no hay duplicados
        for (int i = 0; i < estados.length; i++) {
            for (int j = i + 1; j < estados.length; j++) {
                assertNotEquals(
                    "Estados duplicados: " + estados[i] + " == " + estados[j],
                    estados[i], estados[j]
                );
            }
        }
    }
}
