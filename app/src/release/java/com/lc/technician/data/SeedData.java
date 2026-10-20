package com.lc.technician.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.PaymentEntity;
import com.lc.technician.model.WorkItemEntity;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.JobPartEntity;
import com.lc.technician.model.PartsRequestEntity;
import com.lc.technician.model.StockMovementEntity;
import com.lc.technician.model.PartEntity;
import com.lc.technician.model.PropertyEntity;
import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.ContactEntity;
import com.lc.technician.model.FieldMeasurementEntity;
import com.lc.technician.model.InspectionItemEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.JobEventEntity;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.ProposalOptionEntity;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.model.TimeEventEntity;
import com.lc.technician.model.TimeStats;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * RELEASE-ONLY legacy seed data recovered from the original project (historical
 * BFS / Johnstone imports). Kept unchanged so production builds behave as before.
 * Lab/dev builds use src/debug/.../SeedData.java (fictional data) instead, so this
 * file and its data are never compiled into com.lc.technician.dev.
 */
final class SeedData {

    private final JobDao dao;
    private final CustomerDao customerDao;
    private final PropertyDao propertyDao;
    private final EquipmentDao equipmentDao;
    private final PartDao partDao;
    private final JobPartDao jobPartDao;
    private final WorkItemDao workItemDao;
    private final AuditLogDao auditDao;
    private final List<Job> cache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    SeedData(JobRepository r) {
        this.dao = r.dao;
        this.customerDao = r.customerDao;
        this.propertyDao = r.propertyDao;
        this.equipmentDao = r.equipmentDao;
        this.partDao = r.partDao;
        this.jobPartDao = r.jobPartDao;
        this.workItemDao = r.workItemDao;
        this.auditDao = r.auditDao;
        this.cache = r.cache;
        this.executor = r.executor;
        this.mainHandler = r.mainHandler;
    }

    // ── BFS Seed ──────────────────────────────────────────────────────────────

    public void seedDatosRealesBFS(Runnable callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();

                // ── 1. EQUIPOS (14) ───────────────────────────────────────────
                String[][] eqData = {
                    {"LENNOX CBX27UH-036-230-6-03","LENNOX","CBX27UH-036-230-6-03","1616F04044","Air Handler","R-410A. 1/2 HP. 208/240V. 4.1 FLA. Assembled USA"},
                    {"DAIKIN GLXS4MA3010AA","DAIKIN","GLXS4MA3010AA","2507137575","Condensadora","R-32. 25A max fuse. 670 PSIG max. 2025"},
                    {"DAIKIN AMST30BU1300AA","DAIKIN","AMST30BU1300AA","2411115533","Air Handler","R-32. 233oz charge. 6.61kg. 2024"},
                    {"TRANE FCPC0B1L1B0A0C1C0S","TRANE","FCPC0B1L1B0A0C1C0S","T03B07421","Air Handler","115V. Electric heat 2.0KW. Blower 1.7 FLA 0.13 HP"},
                    {"RHEEM RBHP-21J06SH2B","RHEEM","RBHP-21J06SH2B","W312003775","Air Handler","R-410A. 07/2020. 1/2 HP. 3.1 FLA"},
                    {"RHEEM RBHP-21J07SH2","RHEEM","RBHP-21J07SH2","N391700470","Air Handler","R-410A. 10/2017. 1/2 HP. Assembled Mexico"},
                    {"CLIMATEMASTER SCK048AGC3ZC0PS","CLIMATEMASTER","SCK048AGC3ZC0PS","261610584","Condensadora","R-454B. GWP 466. 2024. 31.3A min CKT"},
                    {"RHEEM RH1T6024STANJA","RHEEM","RH1T6024STANJA","W291957890","Air Handler","R-410A. 07/2019. 3/4 HP"},
                    {"RHEEM RHLL-HM6024JA","RHEEM","RHLL-HM6024JA","W231425307","Central AC","R-410A. 06/2014. 3/4 HP. 6.0A nominal"},
                    {"RHEEM RA1436AJ1NA","RHEEM","RA1436AJ1NA","W132157378","Condensadora","R-410A. 04/2021. 14.1 RLA. 0.8 FLA fan"},
                    {"RHEEM 14AJM42A01","RHEEM","14AJM42A01","7997W29132905","Condensadora","R-410A. 07/2013. 3.5 ton. 17.9 RLA. 4114g charge"},
                    {"RHEEM 14AJM56A01","RHEEM","14AJM56A01","W311402676","Condensadora","R-410A. 07/2014. 4.5 ton. 21.4 RLA. 6832g charge"},
                    {"RHEEM NXA430GKC101","RHEEM","NXA430GKC101","E153111350","Condensadora","R-410A. Jul 2015. 12.8 RLA. 5.67 lbs charge"},
                    {"RHEEM/ICECO 14AHJ06S01C01","RHEEM/ICECO","14AHJ06S01C01","T M5105 00710","Air Handler","12/2005. 1/6 HP. 21.5A total"},
                };
                int equiposIns = 0;
                long[] eqIds = new long[eqData.length];
                for (int i = 0; i < eqData.length; i++) {
                    EquipmentEntity ex = equipmentDao.getBySerial(eqData[i][3]);
                    if (ex != null) {
                        eqIds[i] = ex.id;
                    } else {
                        EquipmentEntity e = new EquipmentEntity();
                        e.propertyId = 0;
                        e.nombre  = eqData[i][0];
                        e.marca   = eqData[i][1];
                        e.modelo  = eqData[i][2];
                        e.serial  = eqData[i][3];
                        e.tipo    = eqData[i][4];
                        e.notas   = eqData[i][5];
                        e.createdAt = now; e.updatedAt = now;
                        eqIds[i] = equipmentDao.insert(e);
                        bfsAudit("EQUIPMENT", eqIds[i], 0, "CREATE", e.nombre, now);
                        equiposIns++;
                    }
                }

                // ── 2. REPUESTOS (12) ─────────────────────────────────────────
                String[][] pData = {
                    {"CAP-355","Capacitor 35/5 uF 440V","Eléctrico","12.50","45.00","10","3"},
                    {"CAP-405","Capacitor 40/5 uF 440V","Eléctrico","14.00","52.00","8","3"},
                    {"CONT-40A","Contactor 40A 24V","Eléctrico","18.00","68.00","6","2"},
                    {"FIL-2025","Filtro 20x25x1 MERV 8","Filtros","3.50","18.00","24","6"},
                    {"FIL-1620","Filtro 16x20x1 MERV 8","Filtros","3.00","15.00","18","6"},
                    {"DRN-TAB","Pastilla drenaje A/C","Mantenimiento","2.00","12.00","30","10"},
                    {"R410A-LB","Refrigerante R-410A (libra)","Refrigeración","8.00","35.00","50","15"},
                    {"R32-LB","Refrigerante R-32 (libra)","Refrigeración","10.00","42.00","30","10"},
                    {"FLW-SW","Flow switch universal","Eléctrico","22.00","85.00","5","2"},
                    {"MOT-FAN","Motor ventilador condensadora 1/6 HP","Motores","65.00","180.00","3","2"},
                    {"COIL-CLN","Servicio limpieza de coil","Servicio","0.00","150.00","0","0"},
                    {"TSTAT-WF","Termostato WiFi","Control","85.00","220.00","4","2"},
                };
                int repuestosIns = 0;
                long[] pIds = new long[pData.length];
                for (int i = 0; i < pData.length; i++) {
                    PartEntity ex = partDao.getBySku(pData[i][0]);
                    if (ex != null) {
                        pIds[i] = ex.id;
                    } else {
                        PartEntity p = new PartEntity();
                        p.sku        = pData[i][0];
                        p.nombre     = pData[i][1];
                        p.categoria  = pData[i][2];
                        p.costo      = Double.parseDouble(pData[i][3]);
                        p.precio     = Double.parseDouble(pData[i][4]);
                        p.stockActual  = Integer.parseInt(pData[i][5]);
                        p.stockMinimo  = Integer.parseInt(pData[i][6]);
                        p.createdAt  = now; p.updatedAt = now;
                        pIds[i] = partDao.insert(p);
                        bfsAudit("PART", pIds[i], 0, "CREATE", p.nombre, now);
                        repuestosIns++;
                    }
                }
                // pIds[0]=CAP-355  pIds[2]=CONT-40A  pIds[3]=FIL-2025
                // pIds[5]=DRN-TAB  pIds[10]=COIL-CLN
                // eqIds[1]=DAIKIN GLXS4MA  eqIds[9]=RHEEM RA1436  eqIds[10]=RHEEM 14AJM42

                // ── 3. CLIENTES + PROPERTIES + JOBS + JOB_PARTS ──────────────
                int jobsCreados = 0;
                Object[][] jobDefs = {
                    // {id, cliente, tel, dir, hora, desc, equipo, eqIdx,
                    //  [partIdxes], [cantidades], [costos], [precios]}
                    {6L, "Hantman, Neil", "(954) 555-0116",
                     "7135 NW 66th Terr, Parkland, FL 33067", "10:00 AM",
                     "AC in bedroom - coil needs cleaning",
                     "DAIKIN GLXS4MA3010AA", 1,
                     new int[]{10}, new int[]{1},
                     new double[]{0.00}, new double[]{150.00}},
                    {7L, "Fink, Howard", "(954) 555-0175",
                     "5975 NW 72 Court, Parkland, FL 33067", "11:00 AM",
                     "AC not cooling - capacitor replaced",
                     "RHEEM RA1436AJ1NA", 9,
                     new int[]{0,2}, new int[]{1,1},
                     new double[]{12.50,18.00}, new double[]{45.00,68.00}},
                    {8L, "Poorian, Stella", "(954) 488-1051",
                     "1692 Harding St (Rear Apt), Hollywood, FL 33020", "2:00 PM",
                     "AC not working - drain line clogged, cleared",
                     "RHEEM 14AJM42A01", 10,
                     new int[]{5,3}, new int[]{2,1},
                     new double[]{2.00,3.50}, new double[]{12.00,18.00}},
                };

                for (Object[] jd : jobDefs) {
                    long jobId = (long) jd[0];
                    String clienteNombre = (String) jd[1];
                    String tel           = (String) jd[2];
                    String dir           = (String) jd[3];
                    String hora          = (String) jd[4];
                    String desc          = (String) jd[5];
                    String equipoNombre  = (String) jd[6];
                    int    eqIdx         = (int)    jd[7];
                    int[]  pIdxes        = (int[])  jd[8];
                    int[]  cantidades    = (int[])  jd[9];
                    double[] costos      = (double[]) jd[10];
                    double[] precios     = (double[]) jd[11];

                    // Customer (idempotente por nombre)
                    long customerId = 0;
                    CustomerEntity cust = customerDao.getByNombreCaseInsensitive(clienteNombre);
                    if (cust == null) {
                        cust = new CustomerEntity();
                        cust.nombre = clienteNombre;
                        cust.telefono = tel;
                        cust.createdAt = now; cust.updatedAt = now;
                        customerId = customerDao.insert(cust);
                        bfsAudit("CUSTOMER", customerId, 0, "CREATE", clienteNombre, now);
                    } else {
                        customerId = cust.id;
                    }

                    // Property (idempotente por dir)
                    long propertyId = 0;
                    PropertyEntity prop = propertyDao.getByCustomerAndDir(customerId, dir);
                    if (prop == null) {
                        prop = new PropertyEntity();
                        prop.customerId = customerId;
                        prop.direccion  = dir;
                        prop.createdAt  = now; prop.updatedAt = now;
                        propertyId = propertyDao.insert(prop);
                    } else {
                        propertyId = prop.id;
                    }

                    // Job (REPLACE si ya existe)
                    JobEntity je = new JobEntity();
                    je.id         = jobId;
                    je.cliente    = clienteNombre;
                    je.telefono   = tel;
                    je.direccion  = dir;
                    je.hora       = hora;
                    je.descripcion = desc;
                    je.equipo     = equipoNombre;
                    je.estado     = Job.STATUS_DONE;
                    je.notas      = "";
                    je.updatedAt  = now;
                    je.customerId  = customerId;
                    je.propertyId  = propertyId;
                    je.equipmentId = eqIds[eqIdx];
                    dao.insert(je);
                    bfsAudit("JOB", jobId, jobId, "CREATE", clienteNombre, now);
                    jobsCreados++;

                    // Job_parts (solo si no hay partes aún para este job)
                    if (jobPartDao.getByJob(jobId).isEmpty()) {
                        for (int pi = 0; pi < pIdxes.length; pi++) {
                            JobPartEntity jp = new JobPartEntity();
                            jp.jobId         = jobId;
                            jp.partId        = pIds[pIdxes[pi]];
                            jp.cantidad      = cantidades[pi];
                            jp.costoUnitario = costos[pi];
                            jp.precioUnitario = precios[pi];
                            jp.nota          = "";
                            jp.timestamp     = now;
                            long jpId = jobPartDao.insert(jp);
                            bfsAudit("JOB_PART", jpId, jobId, "CREATE",
                                cantidades[pi] + "x " + pData[pIdxes[pi]][1], now);
                        }
                    }

                    // Agregar al cache (seed jobs van al final)
                    boolean enCache = false;
                    for (Job cj : cache) { if (cj.id == jobId) { enCache = true; break; } }
                    if (!enCache) {
                        Job j = new Job(jobId, clienteNombre, tel, dir, hora, desc,
                                equipoNombre, Job.STATUS_DONE,
                                "", false, "", "", "", "", "", "", "");
                        j.updatedAt  = now;
                        j.customerId = customerId;
                        j.propertyId = propertyId;
                        j.equipmentId = eqIds[eqIdx];
                        cache.add(j);
                    }
                }

                android.util.Log.i("BFS_SEED",
                    "equipos=" + equiposIns + " repuestos=" + repuestosIns
                    + " jobs=" + jobsCreados);

            } catch (Exception ex) {
                android.util.Log.e("BFS_SEED", "Error en seed BFS", ex);
            }
            mainHandler.post(callback);
        });
    }

    private void bfsAudit(String entityType, long entityId, long jobId,
                           String action, String newValue, long ts) {
        AuditLogEntity al = new AuditLogEntity();
        al.entityType = entityType;
        al.entityId   = entityId;
        al.jobId      = jobId;
        al.action     = action;
        al.newValue   = newValue;
        al.actor      = "BFS_AUDIT";
        al.timestamp  = ts;
        auditDao.insert(al);
    }

    // ── Johnstone Supply Seed ─────────────────────────────────────────────────

    public void seedRepuestosDesdeJohnstone(Runnable callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                String[][] jData = {
                    // {sku, nombre, categoria, costo, precio, stockActual, stockMinimo}
                    {"JS-B92-910","R410A Refrigerante Cilindro","Refrigeración","137.99","229.99","0","0"},
                    {"JS-B66-856","R454B Refrigerante Cilindro","Refrigeración","299.99","499.99","0","0"},
                    {"JS-B64-386","R32 Refrigerante Cilindro","Refrigeración","179.99","299.99","0","0"},
                    {"JS-L49-493","Termostato Sensi Classic","Control","65.99","109.99","0","0"},
                    {"JS-L51-496","Termostato Sensi Lite","Control","44.99","74.99","0","0"},
                    {"JS-L48-024","Termostato 80 Series P","Control","26.99","44.99","0","0"},
                    {"JS-L48-025","Termostato 80 Series NP","Control","23.99","39.99","0","0"},
                    {"JS-L51-024","Termostato 70 Series NP","Control","16.19","26.99","0","0"},
                    {"JS-B96-349","Cojin Condensadora EL3030-2","Mantenimiento","13.19","21.99","0","0"},
                    {"JS-B94-676","Cojin Condensadora EL3636-2","Mantenimiento","16.19","26.99","0","0"},
                    {"JS-B96-920","Cojin Condensadora EL1838-3","Mantenimiento","12.59","20.99","0","0"},
                    {"JS-B75-254","Line Set 3/4\" 25' extremo recto","Mantenimiento","77.99","129.99","0","0"},
                    {"JS-B75-256","Line Set 3/4\" 50' extremo recto","Mantenimiento","143.99","239.99","0","0"},
                    {"JS-B75-257","Line Set 7/8\" 25' extremo recto","Mantenimiento","107.99","179.99","0","0"},
                    {"JS-B75-259","Line Set 7/8\" 50' extremo recto","Mantenimiento","179.99","299.99","0","0"},
                    {"JS-B61-730","Line Set UV 3/4\" 25' extremo recto","Mantenimiento","89.99","149.99","0","0"},
                    {"JS-B61-732","Line Set UV 3/4\" 50' extremo recto","Mantenimiento","167.99","279.99","0","0"},
                    {"JS-B61-733","Line Set UV 7/8\" 25' extremo recto","Mantenimiento","119.99","199.99","0","0"},
                    {"JS-B61-735","Line Set UV 7/8\" 50' extremo recto","Mantenimiento","209.99","349.99","0","0"},
                    {"JS-G33-036","Capacitor Turbo Universal 2.5-67.5 MFD","Electrico","35.99","59.99","0","0"},
                    {"JS-B12-855","Hard Start KS1 (96506)","Electrico","29.99","49.99","0","0"},
                    {"JS-B11-947","Relay/Capacitor TO-5 (96503)","Electrico","29.99","49.99","0","0"},
                    {"JS-B11-519","Super Boost SPP6","Electrico","5.99","9.99","0","0"},
                    {"JS-S89-577","Motor 1/3-1/6 HP Condensadora Consol","Motores","53.99","89.99","0","0"},
                    {"JS-S82-540","Motor 1/2-1/6 HP Condensadora Consol","Motores","59.99","99.99","0","0"},
                    {"JS-S83-125","Motor 1/2-1/3 HP 208-230V","Motores","131.99","219.99","0","0"},
                    {"JS-S83-273","Motor 1-1/3 HP 115/208-230V","Motores","221.99","369.99","0","0"},
                    {"JS-G89-797","AC Whip 6-12-6 NM","Electrico","9.59","15.99","0","0"},
                    {"JS-G89-798","AC Whip 6-34-6 NM","Electrico","13.19","21.99","0","0"},
                    {"JS-G35-618","Disconnect 60A Sin Fusible","Electrico","10.79","17.99","0","0"},
                    {"JS-G37-516","Disconnect 30A Con Fusible","Electrico","11.99","19.99","0","0"},
                    {"JS-G37-517","Disconnect 60A Con Fusible","Electrico","13.19","21.99","0","0"},
                    {"JS-G81-048","Disconnect 60A w/RSH-50 SP","Electrico","74.99","124.99","0","0"},
                    {"JS-B17-834","Locking Caps Universal 50pk","Mantenimiento","197.99","329.99","0","0"},
                    {"JS-B22-927","Locking Caps A2L 50pk","Mantenimiento","167.99","279.99","0","0"},
                    {"JS-B86-936","EasySeal UV (sellador)","Mantenimiento","32.99","54.99","0","0"},
                    {"JS-B82-551","Tri-Power Coil Cleaner","Mantenimiento","10.19","16.99","0","0"},
                };
                int insertados = 0;
                for (String[] pd : jData) {
                    if (partDao.getBySku(pd[0]) != null) continue;
                    PartEntity p = new PartEntity();
                    p.sku         = pd[0];
                    p.nombre      = pd[1];
                    p.categoria   = pd[2];
                    p.costo       = Double.parseDouble(pd[3]);
                    p.precio      = Double.parseDouble(pd[4]);
                    p.stockActual = Integer.parseInt(pd[5]);
                    p.stockMinimo = Integer.parseInt(pd[6]);
                    p.createdAt   = now; p.updatedAt = now;
                    long pid = partDao.insert(p);
                    AuditLogEntity al = new AuditLogEntity();
                    al.entityType = "PART";
                    al.entityId   = pid;
                    al.jobId      = 0;
                    al.action     = "CREATE";
                    al.newValue   = p.nombre;
                    al.actor      = "JOHNSTONE_466";
                    al.timestamp  = now;
                    auditDao.insert(al);
                    insertados++;
                }
                android.util.Log.i("JOHNSTONE_SEED", "insertados=" + insertados);
            } catch (Exception ex) {
                android.util.Log.e("JOHNSTONE_SEED", "Error en seed Johnstone", ex);
            }
            mainHandler.post(callback);
        });
    }

    // ── BFS Full Seed ─────────────────────────────────────────────────────────

    public void seedBFSFull(Runnable callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                int custIns = 0, propIns = 0, jobsIns = 0, eqIns = 0;

                // ── 1. CUSTOMERS (13) ─────────────────────────────────────
                String[][] cData = {
                    {"Isava, Alejandro",             "(954) 555-0101","WARRANTY-HW · Site 10098634"},
                    {"Hantman, Neil",                "(954) 555-0102","ESA-HW · Site 10016363"},
                    {"Fink, Howard",                 "(954) 555-0103","ESA-HW · Site 10075442"},
                    {"De Faria Riina",               "(954) 555-0104","MAINTENANCE-HW · Site 10107043"},
                    {"Herrera, Ricardo",             "(954) 555-0105","A/R · Site 10108021"},
                    {"Tortugaitor LLC / Valeria Total","(954) 555-0106","COMMERCIAL · Site 10088806"},
                    {"Stangle Fred & Barb",          "(954) 555-0107","ESA-HW · Site 10103841"},
                    {"Beard, Jeff",                  "(954) 555-0108","ESA-HW · Site 10113254"},
                    {"Moleon, Georges",              "(954) 555-0109","ESA-HW · Site 10055971"},
                    {"Sobrevilla, Luis",             "(954) 555-0110","A/R · Site 10102074"},
                    {"Poorian, Stella",              "(954) 488-1051","A/R · Site 10112584"},
                    {"Louis, Serge",                 "(954) 555-0112","A/R · Site 10113574"},
                    {"Little, James",                "(954) 555-0113","Site 100196"},
                };
                long[] custIds = new long[cData.length];
                for (int i = 0; i < cData.length; i++) {
                    CustomerEntity ex = customerDao.getByNombreCaseInsensitive(cData[i][0]);
                    if (ex != null) {
                        custIds[i] = ex.id;
                    } else {
                        CustomerEntity c = new CustomerEntity();
                        c.nombre = cData[i][0]; c.telefono = cData[i][1]; c.notas = cData[i][2];
                        c.createdAt = now; c.updatedAt = now;
                        custIds[i] = customerDao.insert(c);
                        bfsFullAudit("CUSTOMER", custIds[i], "CREATE", c.nombre, now);
                        custIns++;
                    }
                }

                // ── 2. PROPERTIES (13) ────────────────────────────────────
                // {dir, tipoPropiedad, codigoAcceso, notasSeguridad}
                String[][] prData = {
                    {"2545 NW 31 CT, Fort Lauderdale, FL 33309",     "Casa",       "GATE CODE 6373 ENTER","Disposal garbage"},
                    {"7135 NW 66 TH TERR, Parkland, FL 33067",       "Casa",       "",                   "AC in bedroom"},
                    {"5975 NW 72 COURT, Parkland, FL 33067",          "Casa",       "",                   "AC not cooling"},
                    {"3001 S OCEAN DR #913, Hollywood, FL 33019",     "Apartamento","",                   "Maintenance dual-fuel"},
                    {"18800 NE 29 AVE #814, Aventura, FL 33180",      "Apartamento","",                   "Maintenance"},
                    {"2501 S OCEAN DR #1632, Hollywood, FL 33019",    "Apartamento","",                   "Commercial, flow switch"},
                    {"13024 Spring Lake Dr, Cooper City, FL 33330",   "Casa",       "",                   "No cooling"},
                    {"900 St Charles Place #204, Hollywood, FL 33026","Apartamento","",                   "Leak check"},
                    {"853 NE 153 TERR, Miami, FL 33162",              "Casa",       "",                   "Maintenance"},
                    {"3001 S OCEAN DR #443, Hollywood, FL 33019",     "Apartamento","",                   "Sweating concern"},
                    {"1692 Harding St (Rear Apt), Hollywood, FL 33020","Apartamento","",                  "Drain line clogged"},
                    {"21013 NW 14th Place #243, Miami, FL 33169",     "Apartamento","",                   "No cool, fan motor"},
                    {"2841 N.E. 46 Street, FL 33064",                  "Casa",       "",                   "Maintenance"},
                };
                long[] propIds = new long[prData.length];
                for (int i = 0; i < prData.length; i++) {
                    PropertyEntity ex = propertyDao.getByCustomerAndDir(custIds[i], prData[i][0]);
                    if (ex != null) {
                        propIds[i] = ex.id;
                    } else {
                        PropertyEntity p = new PropertyEntity();
                        p.customerId = custIds[i]; p.direccion = prData[i][0];
                        p.tipoPropiedad = prData[i][1]; p.codigoAcceso = prData[i][2];
                        p.notasSeguridad = prData[i][3];
                        p.createdAt = now; p.updatedAt = now;
                        propIds[i] = propertyDao.insert(p);
                        bfsFullAudit("PROPERTY", propIds[i], "CREATE", prData[i][0], now);
                        propIns++;
                    }
                }

                // ── 3. SITE EQUIPMENT (skip if serial empty) ──────────────
                String[][] seData = {
                    {"Rheem RA1424BJ1NA","RHEEM",  "RA1424BJ1NA","W04251983",   "Condensadora",        "R-410A. 02/2022. 0.75 HP fan. 208/230V"},
                    {"Carrier 48TCED08", "CARRIER","48TCED08",   "DEMO-SN-0001","Rooftop Package Unit","7.5 ton. R-410A. 460V. 3 phase. DEMO"},
                };
                long carrierEqId = 0;
                for (int i = 0; i < seData.length; i++) {
                    String serial = seData[i][3];
                    if (serial.isEmpty()) continue;
                    EquipmentEntity ex = equipmentDao.getBySerial(serial);
                    long eid;
                    if (ex != null) {
                        eid = ex.id;
                    } else {
                        EquipmentEntity e = new EquipmentEntity();
                        e.propertyId = 0; e.nombre = seData[i][0]; e.marca = seData[i][1];
                        e.modelo = seData[i][2]; e.serial = serial;
                        e.tipo = seData[i][4]; e.notas = seData[i][5];
                        e.createdAt = now; e.updatedAt = now;
                        eid = equipmentDao.insert(e);
                        bfsFullAudit("EQUIPMENT", eid, "CREATE", e.nombre, now);
                        eqIns++;
                    }
                    if (i == 1) carrierEqId = eid; // Carrier 48TCED08
                }

                // ── 4. PARTS lookup ───────────────────────────────────────
                PartEntity coilCln = partDao.getBySku("COIL-CLN");
                PartEntity cap355  = partDao.getBySku("CAP-355");
                PartEntity cont40a = partDao.getBySku("CONT-40A");
                PartEntity drnTab  = partDao.getBySku("DRN-TAB");
                PartEntity fil2025 = partDao.getBySku("FIL-2025");
                PartEntity motFan  = partDao.getBySku("MOT-FAN");

                // ── 5. JOBS (IDs 100-112) ─────────────────────────────────
                // {id, custIdx, hora, desc, equipoNombre, equipmentId}
                Object[][] jDefs = {
                    {100L,1,"10:00 AM","AC in bedroom - coil needs cleaning","Carrier Rooftop 5 ton",carrierEqId},
                    {101L,2,"11:00 AM","AC not cooling - capacitor replaced", "Carrier Rooftop 5 ton",carrierEqId},
                    {102L,10,"2:00 PM","AC not working - drain line clogged, cleared","Carrier COD",0L},
                    {103L,4,"9:00 AM", "Maintenance dual-fuel",               "Carrier DMA",0L},
                    {104L,9,"1:00 PM", "AC sweating concern",                 "Carrier DMA",0L},
                    {105L,11,"3:00 PM","No cool - fan motor replaced",        "Carrier COD",0L},
                    {106L,0,"8:00 AM", "Warranty inspection",                 "Carrier AC", 0L},
                    {107L,3,"10:00 AM","Maintenance - dual fuel system",      "Carrier DMA",0L},
                    {108L,5,"11:00 AM","Flow switch inspection",              "Carrier COD",0L},
                    {109L,6,"9:00 AM", "No cooling - inspection",             "Carrier AC", 0L},
                    {110L,7,"2:00 PM", "Leak check",                          "Carrier COD",0L},
                    {111L,8,"10:00 AM","Maintenance visit",                   "Carrier AC", 0L},
                    {112L,12,"9:00 AM","Maintenance",                         "Carrier AC", 0L},
                };
                for (Object[] jd : jDefs) {
                    long jobId  = (long)   jd[0];
                    int ci      = (int)    jd[1];
                    String hora = (String) jd[2];
                    String desc = (String) jd[3];
                    String eqNm = (String) jd[4];
                    long eqId   = (long)   jd[5];
                    JobEntity je = new JobEntity();
                    je.id = jobId;
                    je.cliente      = cData[ci][0];
                    je.telefono     = cData[ci][1];
                    je.direccion    = prData[ci][0];
                    je.hora         = hora;
                    je.descripcion  = desc;
                    je.equipo       = eqNm;
                    je.estado       = Job.STATUS_DONE;
                    je.notas        = "";
                    je.notasSeguridad = prData[ci][3];
                    je.updatedAt    = now;
                    je.customerId   = custIds[ci];
                    je.propertyId   = propIds[ci];
                    je.equipmentId  = eqId;
                    dao.insert(je);
                    bfsFullAudit("JOB", jobId, "CREATE", je.cliente, now);
                    jobsIns++;
                }

                // ── 6. JOB_PARTS ─────────────────────────────────────────
                if (coilCln != null && jobPartDao.getByJob(100L).isEmpty()) {
                    JobPartEntity jp = new JobPartEntity();
                    jp.jobId=100L; jp.partId=coilCln.id; jp.cantidad=1;
                    jp.costoUnitario=0.0; jp.precioUnitario=150.0; jp.nota=""; jp.timestamp=now;
                    jobPartDao.insert(jp);
                }
                if (cap355 != null && jobPartDao.getByJob(101L).isEmpty()) {
                    JobPartEntity jp1 = new JobPartEntity();
                    jp1.jobId=101L; jp1.partId=cap355.id; jp1.cantidad=1;
                    jp1.costoUnitario=12.50; jp1.precioUnitario=45.0; jp1.nota=""; jp1.timestamp=now;
                    jobPartDao.insert(jp1);
                    if (cont40a != null) {
                        JobPartEntity jp2 = new JobPartEntity();
                        jp2.jobId=101L; jp2.partId=cont40a.id; jp2.cantidad=1;
                        jp2.costoUnitario=18.0; jp2.precioUnitario=68.0; jp2.nota=""; jp2.timestamp=now;
                        jobPartDao.insert(jp2);
                    }
                }
                if (drnTab != null && jobPartDao.getByJob(102L).isEmpty()) {
                    JobPartEntity jp1 = new JobPartEntity();
                    jp1.jobId=102L; jp1.partId=drnTab.id; jp1.cantidad=2;
                    jp1.costoUnitario=2.0; jp1.precioUnitario=12.0; jp1.nota=""; jp1.timestamp=now;
                    jobPartDao.insert(jp1);
                    if (fil2025 != null) {
                        JobPartEntity jp2 = new JobPartEntity();
                        jp2.jobId=102L; jp2.partId=fil2025.id; jp2.cantidad=1;
                        jp2.costoUnitario=3.50; jp2.precioUnitario=18.0; jp2.nota=""; jp2.timestamp=now;
                        jobPartDao.insert(jp2);
                    }
                }
                if (motFan != null && jobPartDao.getByJob(105L).isEmpty()) {
                    JobPartEntity jp = new JobPartEntity();
                    jp.jobId=105L; jp.partId=motFan.id; jp.cantidad=1;
                    jp.costoUnitario=65.0; jp.precioUnitario=180.0; jp.nota=""; jp.timestamp=now;
                    jobPartDao.insert(jp);
                }

                android.util.Log.i("BFS_FULL",
                    "customers=" + custIns + " props=" + propIns
                    + " jobs=" + jobsIns + " equipment=" + eqIns);
            } catch (Exception ex) {
                android.util.Log.e("BFS_FULL", "Error en BFS full seed", ex);
            }
            mainHandler.post(callback);
        });
    }

    private void bfsFullAudit(String entityType, long entityId,
                               String action, String newValue, long ts) {
        AuditLogEntity al = new AuditLogEntity();
        al.entityType = entityType; al.entityId = entityId; al.jobId = 0;
        al.action = action; al.newValue = newValue;
        al.actor = "BFS_FULL"; al.timestamp = ts;
        auditDao.insert(al);
    }

    // ── Work Items BFS Seed ───────────────────────────────────────────────────

    public void seedWorkItemsBFS(Runnable callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                // {jobId, tipo, itemCode, desc, cantidad, precioUnitario}
                Object[][] wData = {
                    // Job 100: Hantman coil
                    {100L,"LABOR",  "1/L",  "HVAC Labor",                   1.0,  0.0},
                    {100L,"MATERIAL","COIL-CLN","Servicio limpieza de coil", 1.0,150.0},
                    // Job 101: Fink capacitor
                    {101L,"LABOR",  "1/L",  "HVAC Labor",                   1.0,  0.0},
                    {101L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {101L,"MATERIAL","CAP-355","Capacitor 35/5 uF",          1.0, 45.0},
                    {101L,"MATERIAL","CONT-40A","Contactor 40A",             1.0, 68.0},
                    // Job 102: Poorian drain
                    {102L,"LABOR",  "1/L",  "HVAC Labor",                   1.0,  0.0},
                    {102L,"MATERIAL","DRN-TAB","Pastilla drenaje",           2.0, 12.0},
                    {102L,"MATERIAL","FIL-2025","Filtro 20x25x1",            1.0, 18.0},
                    // Job 103: De Faria maintenance
                    {103L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    // Job 104: Herrera maintenance
                    {104L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    // Job 105: Louis fan motor
                    {105L,"LABOR",  "1/L",  "HVAC Labor",                   1.0,  0.0},
                    {105L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {105L,"MATERIAL","MOT-FAN","Motor ventilador 1/6 HP",    1.0,180.0},
                    // Jobs 106-112: service charge only
                    {106L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {107L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {108L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {109L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {110L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {111L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                    {112L,"LABOR",  "1/LSC","HVAC Service Charge",           1.0, 55.0},
                };
                java.util.Set<Long> checkedJobs = new java.util.HashSet<>();
                java.util.Set<Long> skipJobs    = new java.util.HashSet<>();
                int inserted = 0;
                for (Object[] wd : wData) {
                    long jobId = (long) wd[0];
                    if (!checkedJobs.contains(jobId)) {
                        checkedJobs.add(jobId);
                        if (!workItemDao.getByJob(jobId).isEmpty()) skipJobs.add(jobId);
                    }
                    if (skipJobs.contains(jobId)) continue;
                    WorkItemEntity w = new WorkItemEntity();
                    w.jobId          = jobId;
                    w.tipo           = (String) wd[1];
                    w.itemCode       = (String) wd[2];
                    w.descripcion    = (String) wd[3];
                    w.cantidad       = (double) wd[4];
                    w.precioUnitario = (double) wd[5];
                    w.total          = w.cantidad * w.precioUnitario;
                    w.totalLinea     = w.total;
                    w.unidad         = "LABOR".equals(w.tipo) ? "Hour" : "Each";
                    w.timestamp      = now;
                    long wid = workItemDao.insert(w);
                    AuditLogEntity aw = new AuditLogEntity();
                    aw.entityType = "WORK_ITEM"; aw.entityId = wid;
                    aw.jobId = jobId; aw.action = "CREATE";
                    aw.newValue = w.descripcion; aw.actor = "WORK_ITEM_BFS";
                    aw.timestamp = now;
                    auditDao.insert(aw);
                    inserted++;
                }
                android.util.Log.i("BFS_WORK_ITEMS", "inserted=" + inserted);
            } catch (Exception ex) {
                android.util.Log.e("BFS_WORK_ITEMS", "Error en seed work items", ex);
            }
            mainHandler.post(callback);
        });
    }

    // ── Seed data ─────────────────────────────────────────────────────────────

    static List<JobEntity> makeSeedEntities() {
        List<JobEntity> list = new ArrayList<>();

        list.add(entity(1, "María Rodríguez", "(305) 555-0101",
                "1234 SW 8th St, Miami, FL 33135", "9:00 AM",
                "Aire acondicionado no enfría. Ruido fuerte al encender.",
                "Carrier 3 ton · Serial 4TTR5036E1000AB", Job.STATUS_PENDING,
                "Casa", false, "CÓDIGO PUERTA", "#4821", "",
                "Puerta lateral derecha. Se atora, empujar fuerte.",
                "Perro labrador amigable, en el patio",
                "Sra. García vecina · (305) 555-9999",
                "Cámara en la entrada"));

        list.add(entity(2, "Juan Pérez", "(305) 555-0102",
                "567 NE 2nd Ave, Miami, FL 33132", "11:30 AM",
                "Mantenimiento anual. Revisar filtros y refrigerante.",
                "Rheem 2.5 ton · Serial RH1234567890", Job.STATUS_PENDING,
                "Apartamento", true, "PRESENTE", "", "", "",
                "", "", "Timbre 3B. Cliente está en casa."));

        list.add(entity(3, "Ana González", "(305) 555-0103",
                "890 Brickell Ave, Miami, FL 33131", "2:00 PM",
                "Instalación de minisplit nuevo en dormitorio principal.",
                "LG 12,000 BTU inverter", Job.STATUS_PENDING,
                "Apartamento", false, "LOCKBOX", "4455", "",
                "Lockbox en la baranda del pasillo.",
                "", "Property manager · (305) 555-8888",
                "Solo entrar con zapatos. No tocar cuadros."));

        list.add(entity(4, "Carlos Méndez", "(305) 555-0104",
                "45 Coral Way, Miami, FL 33145", "4:30 PM",
                "Fuga de agua en unidad interior. Cliente escucha goteo.",
                "Trane 4 ton · Serial TR45678", Job.STATUS_PENDING,
                "Casa", false, "LLAVE ESCONDIDA", "",
                "Debajo de la maceta azul del lado derecho del porche",
                "Entrar por el garaje, puerta lateral.",
                "Gato. No dejar puertas abiertas.", "",
                "Alarma: 7742#"));

        list.add(entity(5, "Luisa Fernández", "(305) 555-0105",
                "2100 Ponce de Leon Blvd, Coral Gables, FL 33134", "6:00 PM",
                "Termostato no responde. Pantalla en blanco.",
                "Honeywell T6 Pro · instalado 2022", Job.STATUS_PENDING,
                "Casa", true, "PRESENTE", "", "", "",
                "", "", "Timbre normal. Cliente recibe en persona."));

        return list;
    }

    private static JobEntity entity(long id, String cliente, String telefono, String direccion,
                                    String hora, String descripcion, String equipo, String estado,
                                    String tipoPropiedad, boolean clientePresente, String tipoAcceso,
                                    String codigoAcceso, String ubicacionLlave, String instrucciones,
                                    String mascota, String contactoRespaldo, String notasSeguridad) {
        JobEntity e = new JobEntity();
        e.id = id;
        e.cliente = cliente;
        e.telefono = telefono;
        e.direccion = direccion;
        e.hora = hora;
        e.descripcion = descripcion;
        e.equipo = equipo;
        e.estado = estado;
        e.notas = "";
        e.tipoPropiedad = tipoPropiedad;
        e.clientePresente = clientePresente;
        e.tipoAcceso = tipoAcceso;
        e.codigoAcceso = codigoAcceso;
        e.ubicacionLlave = ubicacionLlave;
        e.instrucciones = instrucciones;
        e.mascota = mascota;
        e.contactoRespaldo = contactoRespaldo;
        e.notasSeguridad = notasSeguridad;
        return e;
    }

    // ── FASE 3C: Seed desde fotos DCIM/BFS ───────────────────────────────────

    public void seedEquiposDesdeFotos(Runnable callback) {
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            // {nombre, marca, modelo, serial, tipo, notas}
            String[][] eq = {
                {"Daikin GSXN403010AB",        "DAIKIN",       "GSXN403010AB",        "2410176079",       "Condensadora",    "~2.5T, R-410A, 2024, IPX4"},
                {"Rheem RA1424BJINA",           "RHEEM",        "RA1424BJINA",         "W042251983",       "Condensadora",    "2T, R-410A, 02/2022, MOP 25A"},
                {"Rheem RH1P3017STANJA",        "RHEEM",        "RH1P3017STANJA",      "W202038030",       "Air Handler",     "~1.5T, R-410A, 05/2020, 1/4 HP"},
                {"Rheem RA1430AJ1NA",           "RHEEM",        "RA1430AJ1NA",         "W212007274",       "Condensadora",    "2.5T, R-410A, 05/2020, MOP 25A"},
                {"Rheem RH1T6024STANJA",        "RHEEM",        "RH1T6024STANJA",      "W291957890",       "Air Handler",     "2T, R-410A, 07/2019, 3/4 HP"},
                {"Rheem RA1660AJ1NA",           "RHEEM",        "RA1660AJ1NA",         "W071916301",       "Condensadora",    "5T, R-410A, 02/2019, MOP 50A"},
                {"Ameristar AMST36CU1400AA",    "AMERISTAR",    "AMST36CU1400AA",      "2310213496",       "Air Handler",     "3T, R-410A, 3/4 HP, MOP 15A"},
                {"ICECO 14AHJ06S01C01",         "ICECO",        "14AHJ06S01C01",       "TM510500710",      "Air Handler",     "R-22, 12/2005, 1/6 HP, REEMPLAZO PENDIENTE"},
                {"ClimateMaster SCK048AGC3ZC0PS","CLIMATEMASTER","SCK048AGC3ZC0PS",    "261610584",        "Water Source HP", "4T, R-454B A2L, MOP 50A"},
                {"Rheem RBHP-21J07SH2",         "RHEEM",        "RBHP-21J07SH2",       "N391700470",       "Air Handler",     "R-410A, 10/2017, 1/2 HP, MOP 40-45A"},
                {"Trane FCPC08L1LB0A0C1C05",    "TRANE",        "FCPC08L1LB0A0C1C05",  "T03B07421",        "Fan Coil",        "115V, Hydronic, 2.0 kW heat, MOP 25A"},
                {"Trane FCPC06L1LB0A0C1C0S",    "TRANE",        "FCPC06L1LB0A0C1C0S",  "T03B07570",        "Fan Coil",        "115V, Hydronic, 2.0 kW heat, MOP 25A"},
                {"Rheem RBHP-21J06SH2B",        "RHEEM",        "RBHP-21J06SH2B",      "W312003775",       "Air Handler",     "R-410A, 07/2020, 1/2 HP, MOP 30A"},
                {"Rheem RA1436AJ1NA",           "RHEEM",        "RA1436AJ1NA",         "W132157378",       "Condensadora",    "3T, R-410A, 04/2021, MOP 30A"},
                {"Rheem RHLL-HM6024JA",         "RHEEM",        "RHLL-HM6024JA",       "W231425307",       "Air Handler",     "2T, R-410A, 06/2014, 3/4 HP"},
                {"ICP NXA430GKC101",            "ICP",          "NXA430GKC101",        "E153111350",       "Condensadora",    "3.5T, R-410A, 07/2015, MOP 25A"},
                {"Rheem 14AJM56A01",            "RHEEM",        "14AJM56A01",          "W311402676",       "Condensadora",    "~4.5T, R-410A, 07/2014, MOP 50A"},
                {"Rheem 14AJM42A01",            "RHEEM",        "14AJM42A01",          "7997W291320905",   "Condensadora",    "~3.5T, R-410A, 07/2013, MOP 40A"},
            };
            int ins = 0;
            for (String[] row : eq) {
                String serial = row[3];
                if (equipmentDao.getBySerial(serial) == null) {
                    EquipmentEntity e = new EquipmentEntity();
                    e.propertyId = 0; e.nombre = row[0]; e.marca = row[1];
                    e.modelo = row[2]; e.serial = serial;
                    e.tipo = row[4]; e.notas = row[5];
                    e.createdAt = now; e.updatedAt = now;
                    long eid = equipmentDao.insert(e);
                    AuditLogEntity ae = new AuditLogEntity();
                    ae.entityType = "EQUIPMENT"; ae.entityId = eid;
                    ae.jobId = 0; ae.action = "CREATE";
                    ae.newValue = e.nombre + " · " + serial;
                    ae.actor = "BFS_FOTOS"; ae.timestamp = now;
                    auditDao.insert(ae);
                    ins++;
                }
            }
            android.util.Log.i("BFS_FOTOS", "seedEquiposDesdeFotos: " + ins + " equipos insertados");
            if (callback != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
        });
    }

    public void seedCustomersDesdeFotos(Runnable callback) {
        executor.execute(() -> {
            // All customers already seeded via seedBFSFull.
            // Update real phone numbers obtained from BFS screenshots.
            long now = System.currentTimeMillis();
            String[][] updates = {
                {"Stangle Fred & Barb",           "(954) 651-4514"},
                {"Beard, Jeff",                   "(954) 600-4464"},
                {"Herrera, Ricardo",              "(954) 673-8869"},
                {"De Faria Riina",                "(416) 346-4440"},
                {"Louis, Serge",                  "(954) 483-1119"},
            };
            int updated = 0;
            for (String[] row : updates) {
                CustomerEntity c = customerDao.getByNombreCaseInsensitive(row[0]);
                if (c != null && (c.telefono == null || c.telefono.startsWith("(954) 555"))) {
                    c.telefono = row[1]; c.updatedAt = now;
                    customerDao.update(c);
                    updated++;
                }
            }
            android.util.Log.i("BFS_FOTOS", "seedCustomersDesdeFotos: " + updated + " telefonos actualizados");
            if (callback != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
        });
    }

    public void seedRepuestosDesdeFotos(Runnable callback) {
        // No parts/invoice data visible in DCIM/BFS photos — only equipment nameplates.
        android.util.Log.i("BFS_FOTOS", "seedRepuestosDesdeFotos: sin datos de partes en fotos");
        if (callback != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
    }
}
