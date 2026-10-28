package com.lc.technician;

import android.content.Context;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.sync.PhotoSync;

import java.io.File;

/**
 * Privacidad de datos (Orden 21 · Fase D). El teléfono del técnico no debe conservar nombre, teléfono, dirección, correo ni
 * notas del cliente más allá de lo necesario para la visita de hoy:
 *  - al CERRAR una orden (completada o cancelada y ya confirmada por el servidor) se borran sus datos personales;
 *  - al CERRAR SESIÓN se borran los de todas las órdenes ya confirmadas y los clientes/contactos/firmas locales;
 *  - nunca se pierde nada sin subir: si hay cambios o fotos pendientes, cerrar sesión se bloquea.
 * Se conservan el número de orden, el estado y las fotos de evidencia del trabajo (no son datos del cliente).
 * Las órdenes abiertas se vuelven a bajar del servidor al iniciar sesión.
 */
public final class PrivacidadLocal {

    private static final String TAG = "LcPrivacidad";

    /** Columnas de la orden con datos personales. '' (no NULL) porque algunas columnas son NOT NULL. */
    private static final String BORRAR_PERSONALES =
            "cliente = '', telefono = '', direccion = '', descripcion = '', equipo = '', notas = '', instrucciones = '', "
            + "codigoAcceso = '', ubicacionLlave = '', mascota = '', contactoRespaldo = '', notasSeguridad = ''";

    private PrivacidadLocal() {}

    /** Órdenes cerradas y ya confirmadas por el servidor: se vacían sus datos personales. Seguro de llamar muchas veces. */
    public static void purgarCerradas(Context c) {
        try {
            SupportSQLiteDatabase db = LcDatabase.getInstance(c.getApplicationContext()).getOpenHelper().getWritableDatabase();
            db.execSQL("UPDATE jobs SET " + BORRAR_PERSONALES + " WHERE synced = 1 AND estado IN ('COMPLETADO', 'CANCELADO') "
                    + "AND (cliente <> '' OR telefono <> '' OR direccion <> '' OR notas <> '' OR descripcion <> '')");
            db.execSQL("DELETE FROM contacts WHERE jobId IN (SELECT id FROM jobs WHERE synced = 1 AND estado IN ('COMPLETADO', 'CANCELADO'))");
            db.execSQL("DELETE FROM signatures WHERE jobId IN (SELECT id FROM jobs WHERE synced = 1 AND estado IN ('COMPLETADO', 'CANCELADO'))");
            // clientes y propiedades locales que ya no usa ninguna orden abierta
            String abiertas = "(SELECT customerId FROM jobs WHERE customerId IS NOT NULL AND estado NOT IN ('COMPLETADO', 'CANCELADO'))";
            db.execSQL("DELETE FROM properties WHERE customerId NOT IN " + abiertas);
            db.execSQL("DELETE FROM customers WHERE id NOT IN " + abiertas);
            borrarCarpeta(new File(c.getExternalFilesDir(null), "reportes"));   // los PDF llevan nombre y dirección
        } catch (Exception e) {
            Log.w(TAG, "purgarCerradas: " + e.getClass().getSimpleName());
        }
        JobRepository.get().recargarCacheAsync(null);
    }

    /** Cambios de órdenes o fotos que todavía no están en el servidor. Mientras haya, no se puede cerrar sesión. */
    public static int pendientesSinSubir(Context c) {
        int n = 0;
        try {
            LcDatabase db = LcDatabase.getInstance(c.getApplicationContext());
            for (com.lc.technician.model.JobEntity j : db.jobDao().getAll()) if (j.synced != 1) n++;
            long desde = c.getApplicationContext().getSharedPreferences("lc_qa_photos_up", Context.MODE_PRIVATE).getLong("auto_desde", 0);
            for (PhotoEntity p : db.photoDao().getAll()) if (p.timestamp >= desde && !PhotoSync.subida(c, p.id)) n++;
            n += ColaCierre.pendientes(c);   // firmas o negativas guardadas esperando red
        } catch (Exception e) {
            Log.w(TAG, "pendientes: " + e.getClass().getSimpleName());
            return Integer.MAX_VALUE;   // ante la duda, no se cierra sesión
        }
        return n;
    }

    /** Cierre de sesión: borra todos los datos personales confirmados y reinicia la descarga. Llamar fuera del hilo principal. */
    public static void purgarAlCerrarSesion(Context c) {
        Context app = c.getApplicationContext();
        try {
            androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(app);
            wm.cancelUniqueWork(com.lc.technician.worker.SyncWorker.TAG);
            wm.cancelUniqueWork("auto_sync_silent");
            LcDatabase room = LcDatabase.getInstance(app);
            SupportSQLiteDatabase db = room.getOpenHelper().getWritableDatabase();
            db.execSQL("UPDATE jobs SET " + BORRAR_PERSONALES + " WHERE synced = 1");
            db.execSQL("DELETE FROM contacts");
            db.execSQL("DELETE FROM signatures");
            db.execSQL("DELETE FROM customers");
            db.execSQL("DELETE FROM properties");
            room.syncStateDao().markSuccess("PULL_JOB", 0L);   // al volver a entrar se bajan de nuevo las órdenes abiertas
            borrarCarpeta(new File(app.getExternalFilesDir(null), "reportes"));
            borrarContenido(app.getCacheDir());
            app.getSharedPreferences("lc_prefs", Context.MODE_PRIVATE).edit().remove("ordenes_saltadas").apply();
        } catch (Exception e) {
            Log.w(TAG, "purgarAlCerrarSesion: " + e.getClass().getSimpleName());
        }
        JobRepository.get().recargarCacheAsync(null);
    }

    private static void borrarCarpeta(File d) {
        if (d == null || !d.exists()) return;
        borrarContenido(d);
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    private static void borrarContenido(File d) {
        File[] hijos = d == null ? null : d.listFiles();
        if (hijos == null) return;
        for (File f : hijos) {
            if (f.isDirectory()) borrarContenido(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
