package com.lc.technician.data;

import android.os.Handler;

import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.PartEntity;
import com.lc.technician.model.PropertyEntity;
import com.lc.technician.model.WorkItemEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * LAB/DEV-ONLY demo data for the com.lc.technician.dev variant.
 *
 * Everything here is FICTIONAL: names ("Demo", "Ejemplo"…), 555-01xx phone numbers
 * (reserved for fiction), "Demo St" addresses, "DEMOAIR" equipment with DEMO-SN serials,
 * and "Proveedor Demo" parts whose prices are placeholders, not real supplier prices
 * (MASTER_FLOW §21). No historical/customer data from the recovered project is used.
 *
 * Same API as src/release/.../SeedData.java (legacy import), so JobRepository and
 * MainActivity are identical for both build types. All seeds are idempotent
 * (they look up by name / serial / SKU before inserting).
 *
 * Load order, triggered once by MainActivity.runOneTimeSeeds():
 *   makeSeedEntities()          → 5 demo jobs (only when the jobs table is empty)
 *   seedDatosRealesBFS()        → 5 demo customers + properties + equipment, linked to the jobs
 *   seedRepuestosDesdeJohnstone → 6 demo parts
 *   seedWorkItemsBFS()          → labor/part lines for the completed demo job
 *   the remaining legacy hooks  → no-op in this variant
 */
final class SeedData {

    private static final String TAG = "LC_DEMO_SEED";
    private static final String DEMO_NOTE = "DATO FICTICIO (laboratorio)";

    private final JobDao dao;
    private final CustomerDao customerDao;
    private final PropertyDao propertyDao;
    private final EquipmentDao equipmentDao;
    private final PartDao partDao;
    private final WorkItemDao workItemDao;
    private final List<Job> cache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    SeedData(JobRepository r) {
        this.dao = r.dao;
        this.customerDao = r.customerDao;
        this.propertyDao = r.propertyDao;
        this.equipmentDao = r.equipmentDao;
        this.partDao = r.partDao;
        this.workItemDao = r.workItemDao;
        this.cache = r.cache;
        this.executor = r.executor;
        this.mainHandler = r.mainHandler;
    }

    // {jobId, cliente, telefono, direccion, hora, descripcion, equipo, estado}
    private static final String[][] DEMO_JOBS = {
        {"1", "Ana Ejemplo",    "(305) 555-0101", "101 Demo St, Lab City, FL 00000",   "8:00 AM",
                "AC no enfría", "DEMOAIR DA-24 (Condensadora)", Job.STATUS_PENDING},
        {"2", "Bruno Prueba",   "(305) 555-0102", "202 Demo Ave Apt 4, Lab City, FL 00000", "10:00 AM",
                "Mantenimiento preventivo", "DEMOAIR AH-36 (Air Handler)", Job.STATUS_EN_ROUTE},
        {"3", "Carla Demo",     "(305) 555-0103", "303 Sample Rd, Lab City, FL 00000", "12:00 PM",
                "Termostato sin pantalla", "DEMOAIR DA-36 (Condensadora)", Job.STATUS_ARRIVED},
        {"4", "Diego Ficticio", "(305) 555-0104", "404 Test Ln, Lab City, FL 00000",   "2:00 PM",
                "Capacitor débil", "DEMOAIR DA-30 (Condensadora)", Job.STATUS_DONE},
        {"5", "Elena Muestra",  "(305) 555-0105", "505 Mock Blvd, Lab City, FL 00000", "4:00 PM",
                "Fuga de agua en drenaje", "DEMOAIR AH-24 (Air Handler)", Job.STATUS_PENDING},
    };

    static List<JobEntity> makeSeedEntities() {
        long now = System.currentTimeMillis();
        List<JobEntity> list = new ArrayList<>();
        for (String[] j : DEMO_JOBS) {
            JobEntity e = new JobEntity();
            e.id = Long.parseLong(j[0]);
            e.cliente = j[1];
            e.telefono = j[2];
            e.direccion = j[3];
            e.hora = j[4];
            e.descripcion = j[5];
            e.equipo = j[6];
            e.estado = j[7];
            e.notas = "";
            e.tipoPropiedad = "Casa";
            e.clientePresente = true;
            e.tipoAcceso = "Cliente abre";
            e.codigoAcceso = "DEMO-0000";
            e.ubicacionLlave = "";
            e.instrucciones = "Llamar 15 minutos antes (demo)";
            e.mascota = "";
            e.contactoRespaldo = "";
            e.notasSeguridad = DEMO_NOTE;
            e.prioridad = "NORMAL";
            e.updatedAt = now;
            list.add(e);
        }
        return list;
    }

    /** Demo customers, properties and equipment; links each demo job to its customer. */
    void seedDatosRealesBFS(Runnable callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                for (String[] j : DEMO_JOBS) {
                    long jobId = Long.parseLong(j[0]);
                    CustomerEntity c = customerDao.getByNombre(j[1]);
                    long customerId;
                    if (c == null) {
                        c = new CustomerEntity();
                        c.nombre = j[1];
                        c.telefono = j[2];
                        c.email = "demo" + jobId + "@example.com";
                        c.notas = DEMO_NOTE;
                        c.createdAt = now;
                        c.updatedAt = now;
                        customerId = customerDao.insert(c);
                    } else {
                        customerId = c.id;
                    }

                    PropertyEntity p = propertyDao.getByCustomerAndDir(customerId, j[3]);
                    long propertyId;
                    if (p == null) {
                        p = new PropertyEntity();
                        p.customerId = customerId;
                        p.direccion = j[3];
                        p.tipoPropiedad = "Casa";
                        p.clientePresente = true;
                        p.tipoAcceso = "Cliente abre";
                        p.codigoAcceso = "DEMO-0000";
                        p.instrucciones = "Llamar 15 minutos antes (demo)";
                        p.notasSeguridad = DEMO_NOTE;
                        p.createdAt = now;
                        p.updatedAt = now;
                        propertyId = propertyDao.insert(p);
                    } else {
                        propertyId = p.id;
                    }

                    String serial = "DEMO-SN-000" + jobId;
                    if (equipmentDao.getBySerial(serial) == null) {
                        String[] eq = j[6].split(" ", 3); // DEMOAIR, model, (tipo)
                        EquipmentEntity e = new EquipmentEntity();
                        e.propertyId = propertyId;
                        e.nombre = j[6];
                        e.marca = eq[0];
                        e.modelo = eq[1];
                        e.serial = serial;
                        e.tipo = eq[2].replace("(", "").replace(")", "");
                        e.refrigerante = "R-410A";
                        e.voltaje = "208/230V";
                        e.fase = "1";
                        e.ubicacion = e.tipo.startsWith("Condensadora") ? "Exterior" : "Interior";
                        e.lifecycleStatus = "ACTIVE";
                        e.scanStatus = "MANUAL";
                        e.estado = "ACTIVO";
                        e.notas = DEMO_NOTE;
                        e.createdAt = now;
                        e.updatedAt = now;
                        equipmentDao.insert(e);
                    }

                    dao.updateCustomerId(jobId, customerId);
                    for (Job cached : cache) {
                        if (cached.id == jobId) cached.customerId = customerId;
                    }
                }
            } catch (Exception ex) {
                android.util.Log.e(TAG, "Error en seed demo de clientes/equipos", ex);
            }
            if (callback != null) mainHandler.post(callback);
        });
    }

    // {sku, nombre, categoria, costo, precio, stockActual, stockMinimo, unidad}
    private static final String[][] DEMO_PARTS = {
        {"DEMO-001", "Capacitor dual 45/5 µF (demo)", "Eléctrico",  "10", "40", "6", "2", "Each"},
        {"DEMO-002", "Contactor 1 polo 30A (demo)",   "Eléctrico",  "8",  "35", "4", "2", "Each"},
        {"DEMO-003", "Motor ventilador 1/4 HP (demo)", "Motores",   "60", "180", "1", "1", "Each"},
        {"DEMO-004", "Filtro 16x25x1 (demo)",         "Filtros",    "3",  "12", "20", "5", "Each"},
        {"DEMO-005", "Tubería cobre 3/8 (demo)",      "Tubería",    "2",  "6",  "50", "10", "Ft"},
        {"DEMO-006", "Tableta drenaje (demo)",        "Químicos",   "1",  "5",  "0", "3", "Each"},
    };

    /** Demo parts. Prices are placeholders, not supplier prices. */
    void seedRepuestosDesdeJohnstone(Runnable callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                for (String[] d : DEMO_PARTS) {
                    if (partDao.getBySku(d[0]) != null) continue;
                    PartEntity p = new PartEntity();
                    p.sku = d[0];
                    p.nombre = d[1];
                    p.categoria = d[2];
                    p.costo = Double.parseDouble(d[3]);
                    p.precio = Double.parseDouble(d[4]);
                    p.stockActual = Integer.parseInt(d[5]);
                    p.stockMinimo = Integer.parseInt(d[6]);
                    p.unidad = d[7];
                    p.marca = "DEMOAIR";
                    p.proveedor = "Proveedor Demo";
                    p.notas = DEMO_NOTE;
                    p.activo = true;
                    p.createdAt = now;
                    p.updatedAt = now;
                    partDao.insert(p);
                }
            } catch (Exception ex) {
                android.util.Log.e(TAG, "Error en seed demo de repuestos", ex);
            }
            if (callback != null) mainHandler.post(callback);
        });
    }

    /** Labor + part lines on the completed demo job (id 4) so totals/statistics have data. */
    void seedWorkItemsBFS(Runnable callback) {
        executor.execute(() -> {
            try {
                long jobId = 4L;
                if (workItemDao.getByJob(jobId).isEmpty()) {
                    long now = System.currentTimeMillis();
                    workItemDao.insert(workItem(jobId, "LABOR", "LAB-DEMO",
                            "Reemplazo de capacitor (demo)", 1.0, "Hour", 120.0, now));
                    PartEntity cap = partDao.getBySku("DEMO-001");
                    WorkItemEntity part = workItem(jobId, "PART", "DEMO-001",
                            "Capacitor dual 45/5 µF (demo)", 1.0, "Each", 40.0, now);
                    if (cap != null) part.partId = cap.id;
                    workItemDao.insert(part);
                }
            } catch (Exception ex) {
                android.util.Log.e(TAG, "Error en seed demo de work items", ex);
            }
            if (callback != null) mainHandler.post(callback);
        });
    }

    private static WorkItemEntity workItem(long jobId, String tipo, String code, String desc,
                                           double qty, String unidad, double precio, long now) {
        WorkItemEntity w = new WorkItemEntity();
        w.jobId = jobId;
        w.tipo = tipo;
        w.itemCode = code;
        w.descripcion = desc;
        w.cantidad = qty;
        w.unidad = unidad;
        w.precioUnitario = precio;
        w.total = qty * precio;
        w.totalLinea = qty * precio;
        w.nota = DEMO_NOTE;
        w.timestamp = now;
        w.createdAt = now;
        w.updatedAt = now;
        return w;
    }

    // Legacy-only imports: nothing to load in the lab variant.
    void seedBFSFull(Runnable callback)             { done(callback); }
    void seedEquiposDesdeFotos(Runnable callback)   { done(callback); }
    void seedCustomersDesdeFotos(Runnable callback) { done(callback); }
    void seedRepuestosDesdeFotos(Runnable callback) { done(callback); }

    private void done(Runnable callback) {
        if (callback != null) executor.execute(() -> mainHandler.post(callback));
    }
}
