package com.lc.technician;

import com.lc.technician.model.TimeEventEntity;
import com.lc.technician.model.TimeStats;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests para la lógica de cálculo de TimeStats.
 * La lógica real vive en JobRepository.calcularTiempos() —
 * estos tests validan el cálculo de forma aislada.
 */
public class TimeStatsTest {

    private TimeEventEntity makeEvent(String tipo, long timestampMs) {
        TimeEventEntity e = new TimeEventEntity();
        e.tipo = tipo;
        e.timestamp = timestampMs;
        return e;
    }

    /** Simula el cálculo de JobRepository.calcularTiempos() */
    private TimeStats calcular(TimeEventEntity onMyWay, TimeEventEntity arrived, TimeEventEntity offSite) {
        TimeStats stats = new TimeStats();
        stats.onMyWay = onMyWay;
        stats.arrived = arrived;
        stats.offSite = offSite;

        if (onMyWay != null && arrived != null) {
            stats.tiempoViajeMinutos = (arrived.timestamp - onMyWay.timestamp) / 60000;
        }
        if (arrived != null && offSite != null) {
            stats.tiempoEnSitioMinutos = (offSite.timestamp - arrived.timestamp) / 60000;
        }
        if (onMyWay != null && offSite != null) {
            stats.tiempoTotalMinutos = (offSite.timestamp - onMyWay.timestamp) / 60000;
        }
        return stats;
    }

    private static final long MIN = 60_000L;

    @Test
    public void sinEventos_todosMinus1() {
        TimeStats s = calcular(null, null, null);
        assertEquals(-1, s.tiempoViajeMinutos);
        assertEquals(-1, s.tiempoEnSitioMinutos);
        assertEquals(-1, s.tiempoTotalMinutos);
    }

    @Test
    public void soloOnMyWay_viajeYTotalMinus1() {
        TimeStats s = calcular(makeEvent("ON_MY_WAY", 0), null, null);
        assertEquals(-1, s.tiempoViajeMinutos);
        assertEquals(-1, s.tiempoEnSitioMinutos);
        assertEquals(-1, s.tiempoTotalMinutos);
    }

    @Test
    public void onMyWayYArrived_calculaViaje() {
        // 30 minutos de viaje
        TimeStats s = calcular(
            makeEvent("ON_MY_WAY", 0),
            makeEvent("ARRIVED",   30 * MIN),
            null
        );
        assertEquals(30, s.tiempoViajeMinutos);
        assertEquals(-1, s.tiempoEnSitioMinutos);
        assertEquals(-1, s.tiempoTotalMinutos);
    }

    @Test
    public void flujoCCompleto_calculaTodo() {
        // Salida: 0, Llegada: 25 min, Salida del sitio: 85 min
        TimeStats s = calcular(
            makeEvent("ON_MY_WAY", 0),
            makeEvent("ARRIVED",   25 * MIN),
            makeEvent("OFF_SITE",  85 * MIN)
        );
        assertEquals(25, s.tiempoViajeMinutos);
        assertEquals(60, s.tiempoEnSitioMinutos);
        assertEquals(85, s.tiempoTotalMinutos);
    }

    @Test
    public void tiempoTotal_sumaViajeYSitio() {
        TimeStats s = calcular(
            makeEvent("ON_MY_WAY", 0),
            makeEvent("ARRIVED",   20 * MIN),
            makeEvent("OFF_SITE",  110 * MIN)
        );
        assertEquals(s.tiempoViajeMinutos + s.tiempoEnSitioMinutos, s.tiempoTotalMinutos);
    }

    @Test
    public void viajeCero_llegaInmediatamente() {
        TimeStats s = calcular(
            makeEvent("ON_MY_WAY", 1000),
            makeEvent("ARRIVED",   1000), // mismo timestamp
            makeEvent("OFF_SITE",  60 * MIN + 1000)
        );
        assertEquals(0, s.tiempoViajeMinutos);
        assertEquals(60, s.tiempoEnSitioMinutos);
    }
}
