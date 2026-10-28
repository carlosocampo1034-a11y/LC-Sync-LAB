package com.lc.technician.data;

import android.os.Handler;
import com.lc.technician.model.JobEntity;
import java.util.Collections;
import java.util.List;

/**
 * Variantes release e internal: la base local arranca y permanece vacía (sin datos históricos ni de clientes).
 * Mismos métodos que SeedData de debug/release, pero no inserta nada; las órdenes
 * llegan solo desde Supabase.
 */
final class SeedData {

    private final Handler mainHandler;

    SeedData(JobRepository r) {
        this.mainHandler = r.mainHandler;
    }

    private void done(Runnable callback) {
        if (callback != null) mainHandler.post(callback);
    }

    public void seedDatosRealesBFS(Runnable callback)       { done(callback); }
    public void seedRepuestosDesdeJohnstone(Runnable callback) { done(callback); }
    public void seedBFSFull(Runnable callback)              { done(callback); }
    public void seedWorkItemsBFS(Runnable callback)         { done(callback); }
    public void seedEquiposDesdeFotos(Runnable callback)    { done(callback); }
    public void seedCustomersDesdeFotos(Runnable callback)  { done(callback); }
    public void seedRepuestosDesdeFotos(Runnable callback)  { done(callback); }

    static List<JobEntity> makeSeedEntities() {
        return Collections.emptyList();
    }
}
