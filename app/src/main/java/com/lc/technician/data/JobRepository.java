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

public class JobRepository {

    private static volatile JobRepository INSTANCE;

    final JobDao dao;
    private final PhotoDao photoDao;
    private final JobEventDao eventDao;
    private final ContactDao contactDao;
    private final InspectionItemDao inspectionItemDao;
    private final FieldMeasurementDao fieldMeasurementDao;
    final AuditLogDao auditDao;
    private final ProposalDao proposalDao;
    private final ProposalOptionDao proposalOptionDao;
    private final SignatureDao signatureDao;
    private final TimeEventDao timeEventDao;
    final CustomerDao customerDao;
    final PropertyDao propertyDao;
    final EquipmentDao equipmentDao;
    final PartDao partDao;
    final JobPartDao jobPartDao;
    private final StockMovementDao stockMovementDao;
    private final PartsRequestDao partsRequestDao;
    final WorkItemDao workItemDao;
    private final PaymentDao paymentDao;
    private static final int MAX_CACHE_SIZE = 500;
    final List<Job> cache;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final LcDatabase database;
    private final Context appContext;

    private JobRepository(Context ctx) {
        LcDatabase db = LcDatabase.getInstance(ctx);
        database = db;
        appContext = ctx.getApplicationContext();
        dao = db.jobDao();
        photoDao = db.photoDao();
        eventDao = db.jobEventDao();
        contactDao = db.contactDao();
        inspectionItemDao = db.inspectionItemDao();
        fieldMeasurementDao = db.fieldMeasurementDao();
        auditDao = db.auditLogDao();
        proposalDao = db.proposalDao();
        proposalOptionDao = db.proposalOptionDao();
        signatureDao = db.signatureDao();
        timeEventDao = db.timeEventDao();
        customerDao  = db.customerDao();
        propertyDao  = db.propertyDao();
        equipmentDao = db.equipmentDao();
        partDao      = db.partDao();
        jobPartDao   = db.jobPartDao();
        stockMovementDao = db.stockMovementDao();
        partsRequestDao  = db.partsRequestDao();
        workItemDao  = db.workItemDao();
        paymentDao   = db.paymentDao();
        cache = new ArrayList<>();
        try {
            executor.submit(() -> {
                if (dao.count() == 0) dao.insertAll(SeedData.makeSeedEntities());
                cache.addAll(construirCache(dao.getAll()));
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("JobRepository init failed", e);
        }
    }

    public static void init(Context ctx) {
        if (INSTANCE == null) {
            synchronized (JobRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new JobRepository(ctx);
                }
            }
        }
    }

    public static JobRepository get() {
        if (INSTANCE == null) throw new IllegalStateException("JobRepository.init() not called");
        return INSTANCE;
    }

    public List<Job> getTodayJobs() { return cache; }

    /** Entidad Room → modelo de la caché (un único sitio, para no olvidar campos). */
    public static Job desdeEntidad(JobEntity e) {
        Job job = new Job(e.id, e.cliente, e.telefono, e.direccion,
                e.hora, e.descripcion, e.equipo, e.estado,
                e.tipoPropiedad, e.clientePresente, e.tipoAcceso,
                e.codigoAcceso, e.ubicacionLlave, e.instrucciones,
                e.mascota, e.contactoRespaldo, e.notasSeguridad);
        job.notas = e.notas != null ? e.notas : "";
        job.updatedAt = e.updatedAt;
        job.customerId = e.customerId;
        job.propertyId = e.propertyId;
        job.equipmentId = e.equipmentId;
        job.technicianId = e.technicianId;
        job.coverageTypeId = e.coverageTypeId;
        job.coverageApprovedAmount = e.coverageApprovedAmount;
        job.coverageAuthNumber = e.coverageAuthNumber;
        job.prioridad = e.prioridad;
        job.programadoPara = e.programadoPara;
        job.estadoDispatch = e.estadoDispatch;
        job.latitud = e.latitud;
        job.longitud = e.longitud;
        job.ubicacionFuente = e.ubicacionFuente;
        job.serverVersion = e.serverVersion;
        return job;
    }

    private static List<Job> construirCache(List<JobEntity> entities) {
        List<Job> userJobs = new ArrayList<>();
        List<Job> seedJobs = new ArrayList<>();
        for (JobEntity e : entities) {
            Job job = desdeEntidad(e);
            if (e.id > 1_000L) userJobs.add(job); else seedJobs.add(job);
        }
        userJobs.sort((a, b) -> Long.compare(b.id, a.id));
        List<Job> out = new ArrayList<>(userJobs);
        out.addAll(seedJobs);
        return out;
    }

    /**
     * Relee los trabajos de Room y reemplaza la caché en el hilo principal. Antes la caché solo se
     * cargaba al arrancar: lo que trajera la sincronización no aparecía hasta reiniciar la app.
     */
    public void recargarCacheAsync(Runnable onDone) {
        executor.execute(() -> {
            List<Job> nueva = construirCache(dao.getAll());
            mainHandler.post(() -> {
                cache.clear();
                cache.addAll(nueva);
                if (onDone != null) onDone.run();
                Runnable oyente = alRecargarCache;
                if (oyente != null) oyente.run();     // p. ej. la lista visible se vuelve a pintar tras una sync
            });
        });
    }

    /** Pantalla visible que quiere enterarse cuando la caché cambia (una a la vez; null para quitarla). */
    private volatile Runnable alRecargarCache;

    public void setOyenteCache(Runnable r) { alRecargarCache = r; }

    public Job getById(long id) {
        for (Job j : cache) {
            if (j.id == id) return j;
        }
        return null;
    }

    public int getCacheSize() { return cache.size(); }

    public void addJob(Job job) {
        cache.add(0, job);
        if (cache.size() > MAX_CACHE_SIZE) cache.remove(cache.size() - 1);
        executor.execute(() -> {
            dao.insert(job.toEntity());
            auditInsert("JOB", job.id, job.id, "CREATE", null, null, job.cliente);
        });
    }

    // ── FIX 3: collision-safe ID helper ──────────────────────────────────────

    public void getMaxJobIdAsync(Consumer<Long> cb) {
        executor.execute(() -> {
            long maxId = dao.getMaxJobId();
            mainHandler.post(() -> cb.accept(maxId));
        });
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    public void persistEstado(long id, String nuevoEstado, String prevEstado) {
        persistEstado(id, nuevoEstado, prevEstado, null);
    }

    public void persistEstado(long id, String nuevoEstado, String prevEstado, Runnable onComplete) {
        long now = System.currentTimeMillis();
        Job job = getById(id);
        if (job != null) {
            job.updatedAt = now;
            job.estado = nuevoEstado;
        }
        executor.execute(() -> {
            dao.updateEstadoAndTime(id, nuevoEstado, now);
            logEvento(id, "ESTADO", "Cambió estado: " + prevEstado + " → " + nuevoEstado, now);
            auditInsert("JOB", id, id, "UPDATE", "estado", prevEstado, nuevoEstado);
            solicitarSyncInmediata();
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    /**
     * Con sesión de laboratorio, un cambio de estado se envía en cuanto haya red (no a los 15 min).
     * APPEND_OR_REPLACE: si ya hay una sincronización en curso, esta corre después y no se pierde.
     */
    public void solicitarSyncInmediata() {
        if (appContext == null || !com.lc.technician.sync.LabSession.activa(appContext)) return;
        androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(
                com.lc.technician.worker.SyncWorker.class)
                .setConstraints(new androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build();
        // KEEP: si ya hay una sincronización pendiente o en curso, no se encola otra (sin colas que crezcan
        // sin límite estando sin red). Lo editado durante un envío lo sube la ejecución de seguimiento.
        androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
                "sync_inmediata", androidx.work.ExistingWorkPolicy.KEEP, req);
    }

    /** Una sola ejecución extra tras un envío, para lo editado mientras estaba en tránsito. */
    public void solicitarSyncSeguimiento() {
        if (appContext == null || !com.lc.technician.sync.LabSession.activa(appContext)) return;
        androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(
                com.lc.technician.worker.SyncWorker.class)
                .setInitialDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                .setConstraints(new androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build();
        androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
                "sync_seguimiento", androidx.work.ExistingWorkPolicy.KEEP, req);
    }

    /**
     * Id para una orden nueva creada en este teléfono. Con sesión v2: dentro del espacio propio del dispositivo
     * (asignado por el servidor, disjunto entre técnicos). Sin sesión: ids locales históricos (< 8e12).
     */
    public void nuevoIdOrdenAsync(Consumer<Long> cb) {
        executor.execute(() -> {
            long id;
            long[] esp = appContext != null ? com.lc.technician.sync.LabSession.espacioIds(appContext) : null;
            id = esp != null ? Math.max(esp[0], dao.getMaxIdEnRango(esp[0], esp[1]) + 1) : 0;
            if (esp == null || id >= esp[1]) {
                // sin espacio asignado (o agotado): id local histórico; el servidor rechaza colisiones con ID_EN_USO
                id = Math.max(System.currentTimeMillis(), dao.getMaxJobId() + 1);
            }
            final long f = id;
            mainHandler.post(() -> cb.accept(f));
        });
    }

    /**
     * Guarda notas editadas en pantalla. {@code vistas}/{@code versionVista}: lo que el editor mostró al abrirse.
     * Si mientras tanto llegó otra versión del servidor (Room ya no tiene {@code vistas}), la edición se basa en lo
     * que el técnico vio: se envía con esa versión base y el servidor registra el conflicto en vez de perder la
     * nota de despacho. Si el editor no cambió nada respecto a lo mostrado, no se guarda (no pisa lo recibido).
     */
    public void persistNotas(long id, String notas, String vistas, Integer versionVista) {
        String nueva = notas != null ? notas : "";
        String vista = vistas != null ? vistas : "";
        if (nueva.equals(vista)) return;   // sin edición del técnico
        executor.execute(() -> {
            JobEntity guardado = dao.getJobById(id);
            if (guardado == null) return;
            String enDb = guardado.notas != null ? guardado.notas : "";
            if (nueva.equals(enDb)) return;
            Job job = getById(id);
            if (job != null) job.notas = nueva;
            boolean superada = !enDb.equals(vista) && versionVista != null && guardado.serverVersion != null
                    && guardado.serverVersion > versionVista;
            if (superada) {
                dao.updateNotasConBase(id, nueva, System.currentTimeMillis(), versionVista);
                auditInsert("JOB", id, id, "EDICION_SOBRE_VERSION_ANTERIOR", "notas", enDb, nueva);
            } else {
                dao.updateNotas(id, nueva);
                auditInsert("JOB", id, id, "UPDATE", "notas", enDb, nueva);
            }
            solicitarSyncInmediata();
            logEvento(id, "NOTA", "Notas actualizadas", System.currentTimeMillis());
        });
    }

    public void persistNotas(long id, String notas) {
        Job job = getById(id);
        String notaAnterior = (job != null && job.notas != null) ? job.notas : "";
        String notaNueva = (notas != null) ? notas : "";

        if (job != null) job.notas = notaNueva;

        executor.execute(() -> {
            // Se compara con lo guardado en Room: quien llama suele haber cambiado ya job.notas (mismo
            // objeto de la caché), así que notaAnterior no sirve para saber si hubo cambio.
            JobEntity guardado = dao.getJobById(id);
            String enDb = guardado != null && guardado.notas != null ? guardado.notas : "";
            if (notaNueva.equals(enDb)) return;   // sin cambios: no marcar pendiente ni sincronizar
            dao.updateNotas(id, notaNueva);
            solicitarSyncInmediata();
            logEvento(id, "NOTA", "Notas actualizadas", System.currentTimeMillis());
            auditInsert("JOB", id, id, "UPDATE", "notas", enDb, notaNueva);
        });
    }

    // ── Fotos ─────────────────────────────────────────────────────────────────

    public void getPhotosForJobAsync(long jobId, Consumer<List<PhotoEntity>> cb) {
        executor.execute(() -> {
            List<PhotoEntity> result = photoDao.getPhotosForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only (compartirReporte, etc.). */
    public List<PhotoEntity> getPhotosForJob(long jobId) {
        return photoDao.getPhotosForJob(jobId);
    }

    public void insertPhoto(PhotoEntity photo) {
        long ts = photo.timestamp;
        String desc = "Foto agregada" +
                (photo.categoria != null && !photo.categoria.isEmpty()
                        ? " (" + capitalize(photo.categoria) + ")"
                        : "");
        String categoria = photo.categoria != null ? photo.categoria : "sin categoría";
        executor.execute(() -> {
            photoDao.insertPhoto(photo);
            logEvento(photo.jobId, "FOTO", desc, ts);
            auditInsert("PHOTO", 0, photo.jobId, "CREATE", null, null, "Foto " + categoria);
        });
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    public void deletePhoto(long photoId) {
        executor.execute(() -> photoDao.deletePhoto(photoId));
    }

    // ── Contactos ─────────────────────────────────────────────────────────────

    public void getContactsForJobAsync(long jobId, Consumer<List<ContactEntity>> cb) {
        executor.execute(() -> {
            List<ContactEntity> result = contactDao.getContactsForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only. */
    public List<ContactEntity> getContactsForJob(long jobId) {
        return contactDao.getContactsForJob(jobId);
    }

    /**
     * Add a contact. If contact.customerId is not set but jobId is, backfills
     * customerId from the job's customer. If esPrincipal is true, clears other
     * primaries for the same customer scope.
     */
    public void addContact(ContactEntity contact) {
        long ts = System.currentTimeMillis();
        String rol = contact.rol != null ? capitalize(contact.rol) : "Contacto";
        String nombre = contact.nombre != null ? contact.nombre : "";
        String rolSuffix = contact.rol != null ? " (" + contact.rol + ")" : "";
        executor.execute(() -> {
            // Backfill customerId from job if not already set
            if (contact.customerId == null && contact.jobId > 0) {
                JobEntity jobEntity = dao.getJobById(contact.jobId);
                if (jobEntity != null && jobEntity.customerId != null) {
                    contact.customerId = jobEntity.customerId;
                }
            }
            // If primary, clear other primaries for same customer
            if (contact.esPrincipal && contact.customerId != null && contact.customerId > 0) {
                contactDao.clearPrimaryForCustomer(contact.customerId, 0);
            }
            long contactId = contactDao.insertContact(contact);
            // If primary, clear others (now we have the real id)
            if (contact.esPrincipal && contact.customerId != null && contact.customerId > 0) {
                contactDao.clearPrimaryForCustomer(contact.customerId, contactId);
            }
            logEvento(contact.jobId, "CONTACTO",
                    "Contacto agregado (" + rol + "): " + nombre, ts);
            auditInsert("CONTACT", contactId, contact.jobId, "CREATE", null, null, nombre + rolSuffix);
        });
    }

    public void deleteContact(long contactId, long jobId, String nombre) {
        long ts = System.currentTimeMillis();
        executor.execute(() -> {
            contactDao.softDelete(contactId);
            logEvento(jobId, "CONTACTO", "Contacto eliminado: " + nombre, ts);
            auditInsert("CONTACT", contactId, jobId, "DELETE", null, nombre, null);
        });
    }

    /**
     * Get contacts for a job context with priority order:
     * 1. Customer-global contacts (customerId, no propertyId)
     * 2. Property-specific contacts (customerId + propertyId) — override customer-wide
     * 3. Job-specific contacts — highest priority, override all
     *
     * Deduplicates by id. Returns on calling thread (must be from background thread).
     */
    public List<ContactEntity> getContactsForJobContext(long jobId, Long customerId) {
        return getContactsForJobContext(jobId, customerId, null);
    }

    public List<ContactEntity> getContactsForJobContext(long jobId, Long customerId, Long propertyId) {
        java.util.Map<Long, ContactEntity> merged = new java.util.LinkedHashMap<>();
        // 1. Customer-level contacts (widest scope)
        if (customerId != null && customerId > 0) {
            List<ContactEntity> customerContacts = contactDao.getContactsForCustomer(customerId);
            for (ContactEntity c : customerContacts) {
                if (c.propertyId == null) merged.put(c.id, c);
            }
        }
        // 2. Property-specific contacts (narrower scope — override customer-wide)
        if (customerId != null && customerId > 0 && propertyId != null && propertyId > 0) {
            List<ContactEntity> propertyContacts = contactDao.getContactsForProperty(customerId, propertyId);
            for (ContactEntity c : propertyContacts) merged.put(c.id, c);
        }
        // 3. Job-specific contacts (highest priority)
        List<ContactEntity> jobContacts = contactDao.getContactsForJob(jobId);
        for (ContactEntity c : jobContacts) merged.put(c.id, c);
        return new java.util.ArrayList<>(merged.values());
    }

    /** Async version of getContactsForJobContext — delivers on main thread */
    public void getContactsForJobContextAsync(long jobId, Long customerId, Consumer<List<ContactEntity>> cb) {
        executor.execute(() -> {
            List<ContactEntity> result = getContactsForJobContext(jobId, customerId, null);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** Async version with propertyId scope — delivers on main thread */
    public void getContactsForJobContextAsync(long jobId, Long customerId, Long propertyId,
                                               Consumer<List<ContactEntity>> cb) {
        executor.execute(() -> {
            List<ContactEntity> result = getContactsForJobContext(jobId, customerId, propertyId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    // ── Historial ─────────────────────────────────────────────────────────────

    public void getEventsForJobAsync(long jobId, Consumer<List<JobEventEntity>> cb) {
        executor.execute(() -> {
            List<JobEventEntity> result = eventDao.getEventsForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only. */
    public List<JobEventEntity> getEventsForJob(long jobId) {
        return eventDao.getEventsForJob(jobId);
    }

    public void limpiarHistorialDuplicado() {
        executor.execute(() -> eventDao.deleteDuplicatesKeepingLatest());
    }

    public void agregarEvento(long jobId, String tipo, String descripcion) {
        executor.execute(() -> logEvento(jobId, tipo, descripcion, System.currentTimeMillis()));
    }

    // ── Inspección ────────────────────────────────────────────────────────────

    /** Combined async load of inspection items + field measurements. */
    public void getInspectionDataAsync(long jobId,
            BiConsumer<List<InspectionItemEntity>, List<FieldMeasurementEntity>> cb) {
        executor.execute(() -> {
            List<InspectionItemEntity> items = inspectionItemDao.getItemsForJob(jobId);
            List<FieldMeasurementEntity> meds = fieldMeasurementDao.getMeasurementsForJob(jobId);
            mainHandler.post(() -> cb.accept(items, meds));
        });
    }

    public void getInspectionItemsAsync(long jobId, Consumer<List<InspectionItemEntity>> cb) {
        executor.execute(() -> {
            List<InspectionItemEntity> result = inspectionItemDao.getItemsForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only. */
    public List<InspectionItemEntity> getInspectionItems(long jobId) {
        return inspectionItemDao.getItemsForJob(jobId);
    }

    public void guardarInspectionItems(long jobId, List<InspectionItemEntity> items) {
        executor.execute(() -> {
            inspectionItemDao.deleteItemsForJob(jobId);
            inspectionItemDao.insertItems(items);
            auditInsert("INSPECTION", 0, jobId, "UPDATE", null, null, items.size() + " items guardados");
        });
    }

    // ── Mediciones ────────────────────────────────────────────────────────────

    public void addFieldMeasurementAsync(FieldMeasurementEntity m, Consumer<Long> cb) {
        String valor = m.tipo + " = " + m.valor + " " + (m.unidad != null ? m.unidad : "");
        executor.execute(() -> {
            long newId = fieldMeasurementDao.insertMeasurement(m);
            auditInsert("MEASUREMENT", newId, m.jobId, "CREATE", null, null, valor);
            mainHandler.post(() -> cb.accept(newId));
        });
    }

    /** For use inside background threads only. */
    public List<FieldMeasurementEntity> getFieldMeasurements(long jobId) {
        return fieldMeasurementDao.getMeasurementsForJob(jobId);
    }

    public void deleteFieldMeasurement(long id) {
        executor.execute(() -> fieldMeasurementDao.deleteMeasurement(id));
    }

    // ── Propuestas ────────────────────────────────────────────────────────────

    /**
     * Async: loads or creates the proposal for a job, then loads its options.
     * Use this in ProposalActivity.onCreate() instead of crearPropuesta() + getProposalForJob() + getOptionsForProposal().
     */
    public void loadOrCreateProposalAsync(long jobId,
            BiConsumer<ProposalEntity, List<ProposalOptionEntity>> cb) {
        executor.execute(() -> {
            ProposalEntity p = proposalDao.getForJob(jobId);
            if (p == null) {
                p = new ProposalEntity();
                p.jobId = jobId;
                p.estado = "DRAFT";
                p.createdAt = System.currentTimeMillis();
                p.updatedAt = p.createdAt;
                p.id = proposalDao.insert(p);
                final long pid = p.id;
                auditInsert("PROPOSAL", pid, jobId, "CREATE", null, null, "Propuesta creada");
                logEvento(jobId, "PROPUESTA", "Propuesta creada", System.currentTimeMillis());
            }
            final ProposalEntity proposal = p;
            List<ProposalOptionEntity> opts = proposalOptionDao.getForProposal(p.id);
            mainHandler.post(() -> cb.accept(proposal, opts));
        });
    }

    public void getProposalForJobAsync(long jobId, Consumer<ProposalEntity> cb) {
        executor.execute(() -> {
            ProposalEntity result = proposalDao.getForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getOptionsForProposalAsync(long proposalId, Consumer<List<ProposalOptionEntity>> cb) {
        executor.execute(() -> {
            List<ProposalOptionEntity> result = proposalOptionDao.getForProposal(proposalId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /**
     * Combined async load of proposal + options + signature for JobDetailActivity.
     * Callback receives Object[]{ProposalEntity, List<ProposalOptionEntity>, SignatureEntity}.
     */
    public void getProposalDataAsync(long jobId, Consumer<Object[]> cb) {
        executor.execute(() -> {
            ProposalEntity p = proposalDao.getForJob(jobId);
            List<ProposalOptionEntity> opts = p != null
                    ? proposalOptionDao.getForProposal(p.id)
                    : new ArrayList<>();
            SignatureEntity firma = signatureDao.getForJob(jobId);
            mainHandler.post(() -> cb.accept(new Object[]{p, opts, firma}));
        });
    }

    /** For use inside background threads only. */
    public ProposalEntity getProposalForJob(long jobId) {
        return proposalDao.getForJob(jobId);
    }

    /** For use inside background threads only. */
    public List<ProposalOptionEntity> getOptionsForProposal(long proposalId) {
        return proposalOptionDao.getForProposal(proposalId);
    }

    public void guardarPropuesta(ProposalEntity p, List<ProposalOptionEntity> opciones) {
        guardarPropuesta(p, opciones, null);
    }

    public void guardarPropuesta(ProposalEntity p, List<ProposalOptionEntity> opciones, Runnable onComplete) {
        p.updatedAt = System.currentTimeMillis();
        executor.execute(() -> {
            proposalDao.update(p);
            if (opciones != null && !opciones.isEmpty()) {
                proposalOptionDao.deleteForProposal(p.id);
                for (ProposalOptionEntity o : opciones) {
                    o.proposalId = p.id;
                    o.totalCalculado = o.calcularTotal();
                    proposalOptionDao.insert(o);
                }
            }
            auditInsert("PROPOSAL", p.id, p.jobId, "UPDATE", "estado", null, p.estado);
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    // ── Fase 6: propuesta al cliente y factura por sync v2 ──

    /**
     * Guarda las opciones y deja la propuesta pendiente de envío al servidor (el cliente decide en L&C Customer).
     * Si ya estaba enviada y sin decidir, el servidor crea una revisión nueva y la anterior queda SUPERADA.
     */
    public void enviarPropuestaAsync(ProposalEntity p, List<ProposalOptionEntity> opciones, Consumer<String> cb) {
        executor.execute(() -> {
            ProposalEntity actual = proposalDao.getById(p.id);
            String error = null;
            if (actual == null) error = "Propuesta no encontrada";
            else if (actual.isLocked) error = "La propuesta ya está firmada y bloqueada";
            if (error == null) {
                if (opciones != null && !opciones.isEmpty()) {
                    proposalOptionDao.deleteForProposal(p.id);
                    for (ProposalOptionEntity o : opciones) {
                        o.proposalId = p.id;
                        o.totalCalculado = o.calcularTotal();
                        proposalOptionDao.insert(o);
                    }
                }
                actual.estado = "SENT";
                actual.syncState = 0;
                actual.motivoRechazo = null;
                actual.enviadaAt = System.currentTimeMillis();
                actual.updatedAt = actual.enviadaAt;
                proposalDao.update(actual);
                auditInsert("PROPOSAL", p.id, p.jobId, "ENVIO_CLIENTE", "estado", null, "SENT");
                solicitarSyncInmediata();
            }
            final String e = error;
            mainHandler.post(() -> cb.accept(e));
        });
    }

    /** Pago REPORTADO por el técnico (despacho lo confirma). No marca la factura como pagada. */
    public void reportarPagoAsync(long jobId, double monto, String metodo, String referencia, Runnable cb) {
        executor.execute(() -> {
            PaymentEntity p = new PaymentEntity();
            p.id = java.util.UUID.randomUUID().toString();
            p.jobId = jobId;
            p.amount = Math.round(monto * 100.0) / 100.0;
            p.method = metodo;
            p.reference = referencia;
            p.status = "REPORTED";
            p.timestamp = System.currentTimeMillis();
            p.syncLocalId = p.timestamp * 1000 + new java.util.Random().nextInt(1000);
            p.syncState = 0;
            p.serverEstado = null;
            LcDatabase.getInstance(appContext).paymentDao().insert(p);
            auditInsert("PAYMENT", jobId, jobId, "REPORTE", "monto", null, String.format(java.util.Locale.US, "%.2f %s", p.amount, metodo));
            solicitarSyncInmediata();
            if (cb != null) mainHandler.post(cb);
        });
    }

    public void getPagosAsync(long jobId, Consumer<List<PaymentEntity>> cb) {
        executor.execute(() -> {
            List<PaymentEntity> l = LcDatabase.getInstance(appContext).paymentDao().porOrden(jobId);
            mainHandler.post(() -> cb.accept(l));
        });
    }

    /** Deja el PDF de la factura emitida pendiente de subir al servidor (el cliente lo ve en L&C Customer). */
    public void enviarPdfFacturaAsync(long jobId, String path, Runnable cb) {
        executor.execute(() -> {
            InvoiceSyncDao d = LcDatabase.getInstance(appContext).invoiceSyncDao();
            com.lc.technician.model.InvoiceSyncEntity f = d.get(jobId);
            if (f != null && f.numero != null) {
                f.pdfPath = path;
                f.pdfSyncState = 0;
                f.pdfMotivo = null;
                d.upsert(f);
                solicitarSyncInmediata();
            }
            if (cb != null) mainHandler.post(cb);
        });
    }

    /** Trabajos realizados + repuestos usados (como líneas MATERIAL), igual que la factura del servidor. */
    public void getLineasFacturaAsync(long jobId, Consumer<List<WorkItemEntity>> cb) {
        executor.execute(() -> {
            List<WorkItemEntity> out = new ArrayList<>(workItemDao.getByJob(jobId));
            for (JobPartEntity jp : jobPartDao.getByJob(jobId)) {
                PartEntity part = partDao.getById(jp.partId);
                WorkItemEntity w = new WorkItemEntity();
                w.jobId = jobId;
                w.tipo = "MATERIAL";
                String nombre = part != null && part.nombre != null ? part.nombre : "Repuesto #" + jp.partId;
                w.descripcion = nombre + (part != null && part.sku != null ? " (" + part.sku + ")" : "");
                w.cantidad = (double) jp.cantidad;
                w.precioUnitario = jp.precioUnitario;
                w.total = jp.cantidad * jp.precioUnitario;
                out.add(w);
            }
            mainHandler.post(() -> cb.accept(out));
        });
    }

    public void getInvoiceSyncAsync(long jobId, Consumer<com.lc.technician.model.InvoiceSyncEntity> cb) {
        executor.execute(() -> {
            com.lc.technician.model.InvoiceSyncEntity f = LcDatabase.getInstance(appContext).invoiceSyncDao().get(jobId);
            mainHandler.post(() -> cb.accept(f));
        });
    }

    /**
     * Pide al servidor crear y emitir la factura de la orden (con los trabajos sincronizados). Un mismo
     * clientInvoiceId en todos los reintentos; si ya está emitida no se pide otra (sin doble facturación).
     */
    public void solicitarFacturaAsync(long jobId, Consumer<com.lc.technician.model.InvoiceSyncEntity> cb) {
        solicitarFacturaAsync(jobId, false, cb);
    }

    /** confirmaMenor: el técnico revisó y confirma que el importe final es menor que lo aprobado (queda registrado). */
    public void solicitarFacturaAsync(long jobId, boolean confirmaMenor,
                                      Consumer<com.lc.technician.model.InvoiceSyncEntity> cb) {
        executor.execute(() -> {
            InvoiceSyncDao d = LcDatabase.getInstance(appContext).invoiceSyncDao();
            com.lc.technician.model.InvoiceSyncEntity f = d.get(jobId);
            boolean emitida = f != null && f.syncState == 1 && f.estadoServidor != null
                    && !"BORRADOR".equals(f.estadoServidor);
            if (!emitida) {
                if (f == null) {
                    f = new com.lc.technician.model.InvoiceSyncEntity();
                    f.jobId = jobId;
                    f.clientInvoiceId = java.util.UUID.randomUUID().toString();
                }
                f.syncState = 0;
                f.motivo = null;
                f.confirmaMenor = confirmaMenor ? 1 : 0;
                f.updatedAt = System.currentTimeMillis();
                d.upsert(f);
                auditInsert("INVOICE", jobId, jobId, confirmaMenor ? "SOLICITUD_EMISION_CONFIRMA_MENOR" : "SOLICITUD_EMISION",
                        null, null, f.clientInvoiceId);
                solicitarSyncInmediata();
            }
            final com.lc.technician.model.InvoiceSyncEntity r = f;
            mainHandler.post(() -> cb.accept(r));
        });
    }

    public void aceptarOpcion(long proposalId, long jobId, String nivel) {
        executor.execute(() -> {
            ProposalEntity p = proposalDao.getById(proposalId);
            if (p == null) return;
            p.opcionAceptada = nivel;
            p.estado = "ACCEPTED";
            p.updatedAt = System.currentTimeMillis();
            proposalDao.update(p);
            auditInsert("PROPOSAL", proposalId, jobId, "UPDATE", "opcionAceptada", null, nivel);
            logEvento(jobId, "PROPUESTA", "Propuesta aceptada: " + nivel, System.currentTimeMillis());
        });
    }

    public void rechazarPropuesta(long proposalId, long jobId) {
        executor.execute(() -> {
            ProposalEntity p = proposalDao.getById(proposalId);
            if (p == null) return;
            p.estado = "REJECTED";
            p.updatedAt = System.currentTimeMillis();
            proposalDao.update(p);
            auditInsert("PROPOSAL", proposalId, jobId, "UPDATE", "estado", null, "REJECTED");
            logEvento(jobId, "PROPUESTA", "Propuesta rechazada", System.currentTimeMillis());
        });
    }

    // ── Firma ─────────────────────────────────────────────────────────────────

    public void getSignatureForJobAsync(long jobId, Consumer<SignatureEntity> cb) {
        executor.execute(() -> {
            SignatureEntity result = signatureDao.getForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only. */
    public SignatureEntity getSignatureForJob(long jobId) {
        return signatureDao.getForJob(jobId);
    }

    /** For use inside background threads only. */
    public SignatureEntity getSignatureForProposal(long proposalId) {
        return signatureDao.getForProposal(proposalId);
    }

    public void guardarFirma(SignatureEntity firma) {
        guardarFirma(firma, null);
    }

    public void guardarFirma(SignatureEntity firma, Runnable onComplete) {
        final String opcion = firma.opcionAceptada != null ? firma.opcionAceptada : "";
        final String nombre = firma.nombreFirmante != null ? firma.nombreFirmante : "";
        final double total  = firma.totalAceptado;
        final long jId      = firma.jobId;
        executor.execute(() -> {
            boolean lab = appContext != null && com.lc.technician.sync.LabSession.activa(appContext);
            if (lab) firma.syncState = 0;          // la firma viaja al servidor como evidencia
            long id = signatureDao.insert(firma);
            firma.id = id;
            if (lab) solicitarSyncInmediata();
            auditInsert("SIGNATURE", id, jId, "CREATE", null, null,
                    nombre + " firmó " + opcion + String.format(java.util.Locale.US, " $%.0f", total));
            logEvento(jId, "FIRMA",
                    "Cliente firmó: " + nombre + " — " + opcion +
                    String.format(java.util.Locale.US, " $%.0f", total),
                    System.currentTimeMillis());
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void eliminarFirma(SignatureEntity firma) {
        final long id   = firma.id;
        final long jId  = firma.jobId;
        final String nombre = firma.nombreFirmante != null ? firma.nombreFirmante : "";
        executor.execute(() -> {
            signatureDao.delete(firma);
            auditInsert("SIGNATURE", id, jId, "DELETE", null, nombre, null);
            logEvento(jId, "FIRMA", "Firma eliminada", System.currentTimeMillis());
        });
    }

    // ── Time Events ───────────────────────────────────────────────────────────

    public void getLatestTimeEventAsync(long jobId, String tipo, Consumer<TimeEventEntity> cb) {
        executor.execute(() -> {
            TimeEventEntity result = timeEventDao.getLatestOfType(jobId, tipo);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only. */
    public TimeEventEntity getLatestTimeEvent(long jobId, String tipo) {
        return timeEventDao.getLatestOfType(jobId, tipo);
    }

    /** For use inside background threads only. */
    public List<TimeEventEntity> getTimeEventsForJob(long jobId) {
        return timeEventDao.getForJob(jobId);
    }

    public void registrarTimeEvent(long jobId, String tipo, String nota) {
        registrarTimeEvent(jobId, tipo, nota, null);
    }

    public void registrarTimeEvent(long jobId, String tipo, String nota, Runnable onComplete) {
        long now = System.currentTimeMillis();
        String horaFmt = new SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es"))
                .format(new java.util.Date(now));
        String desc;
        switch (tipo) {
            case "ON_MY_WAY": desc = "Salió hacia el cliente · " + horaFmt; break;
            case "ARRIVED":   desc = "Llegó al sitio · " + horaFmt;         break;
            default:          desc = "Salió del sitio · " + horaFmt;        break;
        }
        executor.execute(() -> {
            TimeEventEntity e = new TimeEventEntity();
            e.jobId = jobId;
            e.tipo = tipo;
            e.timestamp = now;
            e.nota = nota;
            long newId = timeEventDao.insert(e);
            logEvento(jobId, "TIEMPO", desc, now);
            auditInsert("TIME_EVENT", newId, jobId, "CREATE", null, null, tipo);
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void eliminarTimeEvent(long timeEventId, long jobId) {
        executor.execute(() -> {
            TimeEventEntity e = new TimeEventEntity();
            e.id = timeEventId;
            timeEventDao.delete(e);
        });
    }

    public void calcularTiemposAsync(long jobId, Consumer<TimeStats> cb) {
        executor.execute(() -> {
            TimeStats stats = new TimeStats();
            stats.onMyWay = timeEventDao.getLatestOfType(jobId, "ON_MY_WAY");
            stats.arrived = timeEventDao.getLatestOfType(jobId, "ARRIVED");
            stats.offSite = timeEventDao.getLatestOfType(jobId, "OFF_SITE");

            if (stats.onMyWay != null && stats.arrived != null) {
                stats.tiempoViajeMinutos =
                        (stats.arrived.timestamp - stats.onMyWay.timestamp) / 60000;
            }
            if (stats.arrived != null && stats.offSite != null) {
                stats.tiempoEnSitioMinutos =
                        (stats.offSite.timestamp - stats.arrived.timestamp) / 60000;
            }
            if (stats.onMyWay != null && stats.offSite != null) {
                stats.tiempoTotalMinutos =
                        (stats.offSite.timestamp - stats.onMyWay.timestamp) / 60000;
            }
            mainHandler.post(() -> cb.accept(stats));
        });
    }

    /** For use inside background threads only. */
    public TimeStats calcularTiempos(long jobId) {
        TimeStats stats = new TimeStats();
        stats.onMyWay = timeEventDao.getLatestOfType(jobId, "ON_MY_WAY");
        stats.arrived = timeEventDao.getLatestOfType(jobId, "ARRIVED");
        stats.offSite = timeEventDao.getLatestOfType(jobId, "OFF_SITE");

        if (stats.onMyWay != null && stats.arrived != null) {
            stats.tiempoViajeMinutos =
                    (stats.arrived.timestamp - stats.onMyWay.timestamp) / 60000;
        }
        if (stats.arrived != null && stats.offSite != null) {
            stats.tiempoEnSitioMinutos =
                    (stats.offSite.timestamp - stats.arrived.timestamp) / 60000;
        }
        if (stats.onMyWay != null && stats.offSite != null) {
            stats.tiempoTotalMinutos =
                    (stats.offSite.timestamp - stats.onMyWay.timestamp) / 60000;
        }
        return stats;
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    public void getAllAuditAsync(Consumer<List<AuditLogEntity>> cb) {
        executor.execute(() -> {
            List<AuditLogEntity> result = auditDao.getAll();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getAuditForJobAsync(long jobId, Consumer<List<AuditLogEntity>> cb) {
        executor.execute(() -> {
            List<AuditLogEntity> result = auditDao.getByJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** For use inside background threads only. */
    public List<AuditLogEntity> getAllAudit() {
        return auditDao.getAll();
    }

    /** For use inside background threads only. */
    public List<AuditLogEntity> getAuditForJob(long jobId) {
        return auditDao.getByJob(jobId);
    }

    // ── Customers / Properties / Equipment ───────────────────────────────────

    public void getAllCustomersAsync(Consumer<List<CustomerEntity>> cb) {
        executor.execute(() -> {
            List<CustomerEntity> result = customerDao.getAll();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getJobsByCustomerAsync(long customerId, long excludeJobId, Consumer<List<Job>> cb) {
        executor.execute(() -> {
            List<Job> resultado = new ArrayList<>();
            for (Job j : cache) {
                if (j.customerId != null && j.customerId == customerId && j.id != excludeJobId) {
                    resultado.add(j);
                }
            }
            resultado.sort((a, b) -> {
                long ta = a.updatedAt != null ? a.updatedAt : 0;
                long tb = b.updatedAt != null ? b.updatedAt : 0;
                return Long.compare(tb, ta);
            });
            mainHandler.post(() -> cb.accept(resultado));
        });
    }

    public List<Job> getJobsByCustomer(long customerId, long excludeJobId) {
        List<Job> resultado = new ArrayList<>();
        for (Job j : cache) {
            if (j.customerId != null && j.customerId == customerId && j.id != excludeJobId) {
                resultado.add(j);
            }
        }
        resultado.sort((a, b) -> {
            long ta = a.updatedAt != null ? a.updatedAt : 0;
            long tb = b.updatedAt != null ? b.updatedAt : 0;
            return Long.compare(tb, ta);
        });
        return resultado;
    }

    public CustomerEntity getCustomerById(long customerId) {
        return customerDao.getById(customerId);
    }

    public void getPropertiesByCustomerAsync(long customerId, Consumer<List<PropertyEntity>> cb) {
        executor.execute(() -> {
            List<PropertyEntity> result = propertyDao.getByCustomer(customerId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getEquipmentByPropertyAsync(long propertyId, Consumer<List<EquipmentEntity>> cb) {
        executor.execute(() -> {
            List<EquipmentEntity> result = equipmentDao.getByProperty(propertyId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getAllEquipmentAsync(Consumer<List<EquipmentEntity>> cb) {
        executor.execute(() -> {
            List<EquipmentEntity> result = equipmentDao.getAll();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getEquipmentByIdAsync(long id, Consumer<EquipmentEntity> cb) {
        executor.execute(() -> {
            EquipmentEntity result = equipmentDao.getById(id);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void updateEquipmentAsync(EquipmentEntity e, Runnable cb) {
        executor.execute(() -> {
            e.updatedAt = System.currentTimeMillis();
            equipmentDao.update(e);
            if (cb != null) mainHandler.post(cb);
        });
    }

    public void deleteEquipmentAsync(EquipmentEntity e, Runnable cb) {
        executor.execute(() -> {
            equipmentDao.deleteById(e.id);
            if (cb != null) mainHandler.post(cb);
        });
    }

    public void getEquiposPorMarcaAsync(Consumer<java.util.List<com.lc.technician.model.MarcaCount>> cb) {
        executor.execute(() -> {
            java.util.List<com.lc.technician.model.MarcaCount> result = equipmentDao.getEquiposPorMarca();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getJobsByCustomerAsync(long customerId, Consumer<java.util.List<JobEntity>> cb) {
        executor.execute(() -> {
            java.util.List<JobEntity> result = dao.getJobsByCustomer(customerId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void exportarRepuestosCSV(android.content.Context ctx, Consumer<String> cb) {
        executor.execute(() -> {
            try {
                java.util.List<PartEntity> parts = partDao.getAll();
                String fecha = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(new java.util.Date());
                java.io.File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (dir != null) dir.mkdirs();
                java.io.File csv = new java.io.File(dir, "lc_repuestos_" + fecha + ".csv");
                try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(csv))) {
                    w.write("\"Nombre\",\"SKU\",\"Categoria\",\"Precio\",\"Costo\",\"Stock\",\"StockMin\",\"Proveedor\"\n");
                    for (PartEntity p : parts) {
                        w.write(q(p.nombre) + "," + q(p.sku) + "," + q(p.categoria) + ","
                                + p.precio + "," + p.costo + "," + p.stockActual + "," + p.stockMinimo
                                + "," + q(p.proveedor) + "\n");
                    }
                }
                mainHandler.post(() -> cb.accept(csv.getAbsolutePath()));
            } catch (Exception ex) {
                mainHandler.post(() -> cb.accept("ERROR: " + ex.getMessage()));
            }
        });
    }

    public void exportarEquiposCSV(android.content.Context ctx, Consumer<String> cb) {
        executor.execute(() -> {
            try {
                java.util.List<EquipmentEntity> equipos = equipmentDao.getAll();
                String fecha = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(new java.util.Date());
                java.io.File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (dir != null) dir.mkdirs();
                java.io.File csv = new java.io.File(dir, "lc_equipos_" + fecha + ".csv");
                try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(csv))) {
                    w.write("\"Nombre\",\"Marca\",\"Modelo\",\"Serial\",\"Tipo\",\"PropertyId\",\"Notas\"\n");
                    for (EquipmentEntity e : equipos) {
                        w.write(q(e.nombre) + "," + q(e.marca) + "," + q(e.modelo) + ","
                                + q(e.serial) + "," + q(e.tipo) + "," + e.propertyId
                                + "," + q(e.notas) + "\n");
                    }
                }
                mainHandler.post(() -> cb.accept(csv.getAbsolutePath()));
            } catch (Exception ex) {
                mainHandler.post(() -> cb.accept("ERROR: " + ex.getMessage()));
            }
        });
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public void resolverOCrearEntidades(
            String clienteNombre, String clienteTelefono,
            String direccion, String tipoPropiedad,
            boolean clientePresente, String tipoAcceso,
            String codigoAcceso, String ubicacionLlave,
            String instrucciones, String mascota,
            String contactoRespaldo, String notasSeguridad,
            String equipoNombre,
            Consumer<long[]> callback) {
        resolverOCrearEntidades(clienteNombre, clienteTelefono, direccion, tipoPropiedad,
                clientePresente, tipoAcceso, codigoAcceso, ubicacionLlave, instrucciones,
                mascota, contactoRespaldo, notasSeguridad, equipoNombre,
                0, null, null, null, callback, null);
    }

    /**
     * Igual que la versión anterior, más:
     * - clienteForzadoId &gt; 0: usar ese cliente (elegido de sugerencias o confirmado);
     *   -1: crear un cliente nuevo aunque exista otro con el mismo nombre; 0: búsqueda por nombre.
     * - latitud/longitud/fuente: se guardan en una Property nueva, o en una existente sin coordenadas.
     * - onError: se llama en el hilo principal si algo falla (antes el callback no llegaba nunca).
     */
    public void resolverOCrearEntidades(
            String clienteNombre, String clienteTelefono,
            String direccion, String tipoPropiedad,
            boolean clientePresente, String tipoAcceso,
            String codigoAcceso, String ubicacionLlave,
            String instrucciones, String mascota,
            String contactoRespaldo, String notasSeguridad,
            String equipoNombre,
            long clienteForzadoId, Double latitud, Double longitud, String ubicacionFuente,
            Consumer<long[]> callback, Consumer<Exception> onError) {
        executor.execute(() -> {
          try {
            // Transacción: si algo falla, no queda un cliente/propiedad a medias que un reintento duplicaría
            long[] res = database.runInTransaction(() -> {
            long now = System.currentTimeMillis();
            long customerId = 0, propertyId = 0, equipmentId = 0;

            boolean clienteValido = clienteNombre != null && !clienteNombre.isEmpty()
                    && !clienteNombre.equals("Cliente nuevo");
            CustomerEntity forzado = clienteForzadoId > 0 ? customerDao.getById(clienteForzadoId) : null;
            if (clienteValido && forzado != null) {
                customerId = forzado.id;
                if (clienteTelefono != null && !clienteTelefono.isEmpty()
                        && !clienteTelefono.equals(forzado.telefono)) {
                    forzado.telefono = clienteTelefono;
                    forzado.updatedAt = now;
                    customerDao.update(forzado);
                }
            } else if (clienteValido && clienteForzadoId == -1) {
                CustomerEntity c = new CustomerEntity();
                c.nombre = clienteNombre;
                c.telefono = clienteTelefono;
                c.createdAt = now;
                c.updatedAt = now;
                customerId = customerDao.insert(c);
            } else if (clienteValido) {
                // Use case-insensitive search to avoid creating duplicates (e.g., "MARIA" vs "Maria")
                CustomerEntity existing = customerDao.getByNombreCaseInsensitive(clienteNombre);
                if (existing != null) {
                    customerId = existing.id;
                    if (clienteTelefono != null && !clienteTelefono.isEmpty()) {
                        existing.telefono = clienteTelefono;
                        existing.updatedAt = now;
                        customerDao.update(existing);
                    }
                } else {
                    CustomerEntity c = new CustomerEntity();
                    c.nombre = clienteNombre;
                    c.telefono = clienteTelefono;
                    c.createdAt = now;
                    c.updatedAt = now;
                    customerId = customerDao.insert(c);
                }
            }

            boolean dirValida = customerId > 0 && direccion != null && !direccion.isEmpty();
            if (dirValida) {
                PropertyEntity existing = propertyDao.getByCustomerAndDir(customerId, direccion);
                if (existing != null) {
                    propertyId = existing.id;
                    if (existing.latitud == null && latitud != null && longitud != null) {
                        existing.latitud = latitud;
                        existing.longitud = longitud;
                        existing.ubicacionFuente = ubicacionFuente;
                        existing.updatedAt = now;
                        propertyDao.update(existing);
                    }
                } else {
                    PropertyEntity p = new PropertyEntity();
                    p.latitud = latitud;
                    p.longitud = longitud;
                    p.ubicacionFuente = latitud != null ? ubicacionFuente : null;
                    p.customerId = customerId;
                    p.direccion = direccion;
                    p.tipoPropiedad = tipoPropiedad;
                    p.clientePresente = clientePresente;
                    p.tipoAcceso = tipoAcceso;
                    p.codigoAcceso = codigoAcceso;
                    p.ubicacionLlave = ubicacionLlave;
                    p.instrucciones = instrucciones;
                    p.mascota = mascota;
                    p.contactoRespaldo = contactoRespaldo;
                    p.notasSeguridad = notasSeguridad;
                    p.createdAt = now;
                    p.updatedAt = now;
                    propertyId = propertyDao.insert(p);
                }
            }

            boolean equipoValido = propertyId > 0 && equipoNombre != null
                    && !equipoNombre.isEmpty() && !equipoNombre.equals("Por asignar");
            if (equipoValido) {
                EquipmentEntity existing = equipmentDao.getByPropertyAndNombre(propertyId, equipoNombre);
                if (existing != null) {
                    equipmentId = existing.id;
                } else {
                    EquipmentEntity e = new EquipmentEntity();
                    e.propertyId = propertyId;
                    e.nombre = equipoNombre;
                    e.createdAt = now;
                    e.updatedAt = now;
                    equipmentId = equipmentDao.insert(e);
                }
            }

            return new long[]{customerId, propertyId, equipmentId};
            });
            mainHandler.post(() -> callback.accept(res));
          } catch (Exception ex) {
            android.util.Log.e("JobRepository", "resolverOCrearEntidades falló", ex);
            if (onError != null) mainHandler.post(() -> onError.accept(ex));
          }
        });
    }

    /** Cliente con el mismo nombre (sin distinguir mayúsculas), o null. Resultado en hilo principal. */
    public void buscarClientePorNombreAsync(String nombre, Consumer<CustomerEntity> cb) {
        executor.execute(() -> {
            CustomerEntity c = customerDao.getByNombreCaseInsensitive(nombre);
            mainHandler.post(() -> cb.accept(c));
        });
    }

    /**
     * Guarda una solicitud nueva (Solicitar servicio) con los campos de despacho y coordenadas,
     * que Job.toEntity() no copia. Queda synced=0: SyncWorker la subirá cuando haya servidor.
     * onOk recibe la fila leída de vuelta de Room, para confirmar que se guardó de verdad.
     */
    public void crearSolicitudAsync(Job job, JobEntity extra,
                                    Consumer<JobEntity> onOk, Consumer<Exception> onError) {
        executor.execute(() -> {
            try {
                JobEntity e = job.toEntity();
                e.prioridad = extra.prioridad;
                e.programadoPara = extra.programadoPara;
                e.estadoDispatch = extra.estadoDispatch;
                e.latitud = extra.latitud;
                e.longitud = extra.longitud;
                e.ubicacionFuente = extra.ubicacionFuente;
                e.synced = 0;
                dao.insert(e);
                JobEntity guardado = dao.getJobById(e.id);
                if (guardado == null) throw new IllegalStateException("La solicitud no se encontró tras guardarla");
                auditInsert("JOB", job.id, job.id, "CREATE", null, null, job.cliente);
                job.prioridad = e.prioridad;
                job.programadoPara = e.programadoPara;
                job.estadoDispatch = e.estadoDispatch;
                job.latitud = e.latitud;
                job.longitud = e.longitud;
                job.ubicacionFuente = e.ubicacionFuente;
                solicitarSyncInmediata();   // una solicitud nueva sale en cuanto haya red
                mainHandler.post(() -> {
                    cache.add(0, job);
                    if (cache.size() > MAX_CACHE_SIZE) cache.remove(cache.size() - 1);
                    onOk.accept(guardado);
                });
            } catch (Exception ex) {
                android.util.Log.e("JobRepository", "crearSolicitudAsync falló", ex);
                mainHandler.post(() -> onError.accept(ex));
            }
        });
    }

    // ── Parts / Inventario ────────────────────────────────────────────────────

    public void getJobsPendientesAsync(Consumer<List<JobEntity>> cb) {
        executor.execute(() -> {
            List<JobEntity> result = dao.getJobsPendientes();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getAllPartsAsync(Consumer<List<PartEntity>> cb) {
        executor.execute(() -> {
            List<PartEntity> result = partDao.getAll();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void getPartsStockBajoAsync(Consumer<List<PartEntity>> cb) {
        executor.execute(() -> {
            List<PartEntity> result = partDao.getStockBajo();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void addPartAsync(PartEntity part, Consumer<Long> cb) {
        long now = System.currentTimeMillis();
        part.createdAt = now;
        part.updatedAt = now;
        executor.execute(() -> {
            long id = partDao.insert(part);
            part.id = id;
            auditInsert("PART", id, 0, "CREATE", null, null, part.nombre);
            mainHandler.post(() -> cb.accept(id));
        });
    }

    public void updatePartAsync(PartEntity part, Runnable cb) {
        part.updatedAt = System.currentTimeMillis();
        executor.execute(() -> {
            partDao.update(part);
            auditInsert("PART", part.id, 0, "UPDATE", null, null, part.nombre);
            if (cb != null) mainHandler.post(cb);
        });
    }

    public void deletePartAsync(PartEntity part, Runnable cb) {
        executor.execute(() -> {
            partDao.delete(part);
            auditInsert("PART", part.id, 0, "DELETE", null, part.nombre, null);
            if (cb != null) mainHandler.post(cb);
        });
    }

    /** For use inside background threads only (compartirReporte, etc.). */
    public List<JobPartEntity> getJobPartsForJob(long jobId) {
        return jobPartDao.getByJob(jobId);
    }

    /** For use inside background threads only. */
    public PartEntity getPartById(long partId) {
        return partDao.getById(partId);
    }

    public void getJobPartsAsync(long jobId, Consumer<List<JobPartEntity>> cb) {
        executor.execute(() -> {
            List<JobPartEntity> result = jobPartDao.getByJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    public void addJobPartAsync(long jobId, long partId, int cantidad,
                                double costoUnitario, double precioUnitario,
                                String nota, Consumer<Long> cb) {
        long now = System.currentTimeMillis();
        executor.execute(() -> {
            JobPartEntity jp = new JobPartEntity();
            jp.jobId = jobId;
            jp.partId = partId;
            jp.cantidad = cantidad;
            jp.costoUnitario = costoUnitario;
            jp.precioUnitario = precioUnitario;
            jp.nota = nota;
            jp.timestamp = now;
            jp.updatedAt = now;
            boolean lab = appContext != null && com.lc.technician.sync.LabSession.activa(appContext);
            jp.syncState = lab ? 0 : 1;
            long id = jobPartDao.insert(jp);
            if (lab) solicitarSyncInmediata();

            // FASE 11: Registrar movimiento de stock OUT automatico
            try {
                StockMovementEntity sm = new StockMovementEntity();
                sm.partId = partId;
                sm.locationId = 1; // Warehouse Principal
                sm.movementType = StockMovementEntity.TYPE_OUT;
                sm.quantity = -cantidad;
                sm.referenceType = StockMovementEntity.REF_JOB;
                sm.referenceId = jobId;
                sm.notes = "Consumo en trabajo #" + jobId;
                stockMovementDao.insert(sm);
                partDao.decrementStock(partId, cantidad, System.currentTimeMillis());
            } catch (Exception e) {
                android.util.Log.e("JobRepo", "Error registrando movimiento stock", e);
            }

            PartEntity part = partDao.getById(partId);
            String partNombre = part != null ? part.nombre : "Repuesto #" + partId;
            logEvento(jobId, "REPUESTO", "Agregado: " + cantidad + "x " + partNombre, now);
            auditInsert("JOB_PART", id, jobId, "CREATE", null, null,
                    cantidad + "x " + partNombre);
            mainHandler.post(() -> cb.accept(id));
        });
    }

    public void removeJobPartAsync(JobPartEntity jp, Runnable cb) {
        long now = System.currentTimeMillis();
        executor.execute(() -> {
            // anulación lógica: el registro se conserva (auditoría y sincronización); el stock se devuelve igual
            jp.anulado = 1;
            jp.updatedAt = now;
            boolean lab = appContext != null && com.lc.technician.sync.LabSession.activa(appContext);
            if (lab) jp.syncState = 0;
            jobPartDao.update(jp);
            if (lab) solicitarSyncInmediata();

            // FASE 12: Registrar movimiento de stock IN (devolución)
            try {
                StockMovementEntity sm = new StockMovementEntity();
                sm.partId = jp.partId;
                sm.locationId = 1;
                sm.movementType = StockMovementEntity.TYPE_IN;
                sm.quantity = jp.cantidad;
                sm.referenceType = StockMovementEntity.REF_JOB;
                sm.referenceId = jp.jobId;
                sm.notes = "Devolución de trabajo #" + jp.jobId;
                stockMovementDao.insert(sm);
                partDao.incrementStock(jp.partId, jp.cantidad, now);
            } catch (Exception e) {
                android.util.Log.e("JobRepo", "Error registrando devolución stock", e);
            }

            PartEntity part = partDao.getById(jp.partId);
            String partNombre = part != null ? part.nombre : "Repuesto #" + jp.partId;
            logEvento(jp.jobId, "REPUESTO", "Eliminado: " + jp.cantidad + "x " + partNombre, now);
            auditInsert("JOB_PART", jp.id, jp.jobId, "DELETE", null,
                    jp.cantidad + "x " + partNombre, null);
            if (cb != null) mainHandler.post(cb);
        });
    }

    public void backfillCustomerIds(Consumer<Integer> callback) {
        executor.execute(() -> {
            int actualizados = 0;
            List<JobEntity> huerfanos = dao.getJobsSinCustomer();
            for (JobEntity j : huerfanos) {
                if (j.cliente == null || j.cliente.trim().isEmpty()
                        || j.cliente.equals("Cliente nuevo")) continue;
                CustomerEntity c = customerDao.getByNombreCaseInsensitive(j.cliente.trim());
                if (c != null) {
                    dao.updateCustomerId(j.id, c.id);
                    Job cached = getById(j.id);
                    if (cached != null) cached.customerId = c.id;
                    actualizados++;
                    auditInsert("JOB", j.id, j.id, "UPDATE",
                            "customerId", "null", String.valueOf(c.id));
                }
            }
            final int total = actualizados;
            mainHandler.post(() -> callback.accept(total));
        });
    }

    // ── Seed data ─────────────────────────────────────────────────────────────
    // Implemented per build type: src/release = legacy historical import,
    // src/debug = fictional lab data (see SeedData in each source set).

    private SeedData seedData;

    private synchronized SeedData seeds() {
        if (seedData == null) seedData = new SeedData(this);
        return seedData;
    }

    public void seedDatosRealesBFS(Runnable callback)          { seeds().seedDatosRealesBFS(callback); }
    public void seedRepuestosDesdeJohnstone(Runnable callback) { seeds().seedRepuestosDesdeJohnstone(callback); }
    public void seedBFSFull(Runnable callback)                 { seeds().seedBFSFull(callback); }
    public void seedWorkItemsBFS(Runnable callback)            { seeds().seedWorkItemsBFS(callback); }
    public void seedEquiposDesdeFotos(Runnable callback)       { seeds().seedEquiposDesdeFotos(callback); }
    public void seedCustomersDesdeFotos(Runnable callback)     { seeds().seedCustomersDesdeFotos(callback); }
    public void seedRepuestosDesdeFotos(Runnable callback)     { seeds().seedRepuestosDesdeFotos(callback); }

    // ── Work Items ────────────────────────────────────────────────────────────

    public List<WorkItemEntity> getWorkItemsForJob(long jobId) {
        return workItemDao.getByJob(jobId);
    }

    public void getWorkItemsAsync(long jobId, Consumer<List<WorkItemEntity>> callback) {
        executor.execute(() -> {
            List<WorkItemEntity> items = workItemDao.getByJob(jobId);
            mainHandler.post(() -> callback.accept(items));
        });
    }

    public void getTotalWorkItemsAsync(long jobId, Consumer<Double> callback) {
        executor.execute(() -> {
            double total = workItemDao.getTotalByJob(jobId);
            mainHandler.post(() -> callback.accept(total));
        });
    }

    public void addWorkItemAsync(WorkItemEntity w, Consumer<Long> callback) {
        executor.execute(() -> {
            long ahora = System.currentTimeMillis();
            if (w.createdAt == 0) w.createdAt = ahora;
            w.updatedAt = ahora;
            w.synced = 0;
            if (w.cantidad != null && w.precioUnitario != null) {
                w.total = w.cantidad * w.precioUnitario;
                w.totalLinea = w.total;
            }
            long id = workItemDao.insert(w);
            if (appContext != null && com.lc.technician.sync.LabSession.activa(appContext)) solicitarSyncInmediata();
            AuditLogEntity al = new AuditLogEntity();
            al.entityType = "WORK_ITEM"; al.entityId = id; al.jobId = w.jobId;
            al.action = "CREATE"; al.newValue = w.descripcion;
            al.actor = "TÉCNICO"; al.timestamp = System.currentTimeMillis();
            auditDao.insert(al);
            mainHandler.post(() -> callback.accept(id));
        });
    }

    public void updateWorkItemAsync(WorkItemEntity w, Runnable callback) {
        executor.execute(() -> {
            // Antes una edición no volvía a "pendiente": tras la primera sincronización nunca se reenviaba
            w.updatedAt = System.currentTimeMillis();
            w.synced = 0;
            if (w.cantidad != null && w.precioUnitario != null) {
                w.total = w.cantidad * w.precioUnitario;
                w.totalLinea = w.total;
            }
            workItemDao.update(w);
            if (appContext != null && com.lc.technician.sync.LabSession.activa(appContext)) solicitarSyncInmediata();
            AuditLogEntity al = new AuditLogEntity();
            al.entityType = "WORK_ITEM"; al.entityId = w.id; al.jobId = w.jobId;
            al.action = "UPDATE"; al.newValue = w.descripcion;
            al.actor = "TÉCNICO"; al.timestamp = System.currentTimeMillis();
            auditDao.insert(al);
            mainHandler.post(callback);
        });
    }

    /**
     * "Eliminar" = anulación lógica: la fila se conserva (auditoría y sincronización) con anulado = 1 y
     * vuelve a pendiente; el servidor la marca anulada con el mismo orden temporal que las ediciones.
     */
    public void deleteWorkItemAsync(WorkItemEntity w, Runnable callback) {
        executor.execute(() -> {
            long ahora = System.currentTimeMillis();
            w.anulado = 1;
            w.anuladoAt = ahora;
            w.updatedAt = ahora;
            w.synced = 0;
            workItemDao.update(w);
            if (appContext != null && com.lc.technician.sync.LabSession.activa(appContext)) solicitarSyncInmediata();
            AuditLogEntity al = new AuditLogEntity();
            al.entityType = "WORK_ITEM"; al.entityId = w.id; al.jobId = w.jobId;
            al.action = "DELETE"; al.newValue = w.descripcion;
            al.actor = "TÉCNICO"; al.timestamp = System.currentTimeMillis();
            auditDao.insert(al);
            mainHandler.post(callback);
        });
    }

    // ── Dedup Jobs v1 ─────────────────────────────────────────────────────────

    public void dedupJobsV1(Runnable callback) {
        executor.execute(() -> {
            try {
                java.util.List<Long> ids = java.util.Arrays.asList(6L, 7L, 8L);
                dao.deleteByIds(ids);
                jobPartDao.deleteByJobIds(ids);
                eventDao.deleteByJobIds(ids);
                cache.removeIf(j -> j.id == 6 || j.id == 7 || j.id == 8);
                android.util.Log.i("DEDUP", "Removed jobs 6,7,8 and related records");
            } catch (Exception ex) {
                android.util.Log.e("DEDUP", "Error en dedup", ex);
            }
            mainHandler.post(callback);
        });
    }


    public void limpiarAuditAntiguo(int dias) {
        long cutoff = System.currentTimeMillis() - (long) dias * 24 * 60 * 60 * 1000;
        executor.execute(() -> auditDao.deleteOlderThan(cutoff));
    }

    // ── Assignment ────────────────────────────────────────────────────────────

    /**
     * Assign a technician to a job. Writes ASSIGN audit entry (or REASSIGN if
     * the job already had a technician). Updates in-memory cache too.
     *
     * @param jobId         DB id of the job
     * @param technicianId  technician identifier (name for local auth)
     * @param actorName     who performed the action (logged-in user name)
     * @param cb            optional callback on main thread when done
     */
    public void assignTechnician(long jobId, String technicianId, String actorName, Runnable cb) {
        executor.execute(() -> {
            JobEntity existing = dao.getJobById(jobId);
            String oldTech = (existing != null) ? existing.technicianId : null;
            boolean isReassign = (oldTech != null && !oldTech.isEmpty());

            dao.updateTechnicianId(jobId, technicianId);

            // Update in-memory cache
            Job cached = getById(jobId);
            if (cached != null) cached.technicianId = technicianId;

            String action = isReassign ? "REASSIGN" : "ASSIGN";
            String oldVal = isReassign ? oldTech : null;
            auditInsertWithActor("JOB", jobId, jobId, action, "technicianId",
                    oldVal, technicianId,
                    actorName != null ? actorName : "Sistema");
            logEvento(jobId, "ASIGNACIÓN",
                    action.equals("REASSIGN")
                        ? "Reasignado a: " + technicianId + " (anterior: " + oldTech + ")"
                        : "Asignado a: " + technicianId,
                    System.currentTimeMillis());

            if (cb != null) mainHandler.post(cb);
        });
    }

    /**
     * Remove technician assignment from a job. Writes UNASSIGN audit entry.
     */
    public void unassignTechnician(long jobId, String actorName, Runnable cb) {
        executor.execute(() -> {
            JobEntity existing = dao.getJobById(jobId);
            String oldTech = (existing != null) ? existing.technicianId : null;

            dao.updateTechnicianId(jobId, null);

            Job cached = getById(jobId);
            if (cached != null) cached.technicianId = null;

            auditInsertWithActor("JOB", jobId, jobId, "UNASSIGN", "technicianId",
                    oldTech, null,
                    actorName != null ? actorName : "Sistema");
            logEvento(jobId, "ASIGNACIÓN",
                    "Desasignado" + (oldTech != null ? " (anterior: " + oldTech + ")" : ""),
                    System.currentTimeMillis());

            if (cb != null) mainHandler.post(cb);
        });
    }

    // ── Equipment history ─────────────────────────────────────────────────────

    /**
     * Async: get previous jobs for the same equipment (excluding current job).
     * Results on main thread.
     */
    public void getJobsByEquipmentAsync(long equipmentId, long excludeJobId,
                                        Consumer<List<JobEntity>> cb) {
        executor.execute(() -> {
            List<JobEntity> result = dao.getPreviousJobsForEquipment(equipmentId, excludeJobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    // ── Privado ───────────────────────────────────────────────────────────────

    private void auditInsert(String entityType, long entityId, long jobId, String action,
                              String field, String oldValue, String newValue) {
        auditInsertWithActor(entityType, entityId, jobId, action, field, oldValue, newValue, "TÉCNICO");
    }

    private void auditInsertWithActor(String entityType, long entityId, long jobId, String action,
                                       String field, String oldValue, String newValue, String actor) {
        AuditLogEntity e = new AuditLogEntity();
        e.entityType = entityType;
        e.entityId = entityId;
        e.jobId = jobId;
        e.action = action;
        e.field = field;
        e.oldValue = oldValue;
        e.newValue = newValue;
        e.actor = actor != null ? actor : "Sistema";
        e.timestamp = System.currentTimeMillis();
        auditDao.insert(e);
    }

    private void logEvento(long jobId, String tipo, String descripcion, long timestamp) {
        JobEventEntity ev = new JobEventEntity();
        ev.jobId = jobId;
        ev.tipo = tipo;
        ev.descripcion = descripcion;
        ev.timestamp = timestamp;
        eventDao.insert(ev);
    }


    // ── Pagos (Room) ──────────────────────────────────────────────────────────

    /** Persist a payment to Room. */
    public void insertPaymentAsync(PaymentEntity payment, Runnable cb) {
        executor.execute(() -> {
            paymentDao.insert(payment);
            auditInsert("PAYMENT", 0, payment.jobId, "CREATE", "status",
                    null, payment.status + " $" + payment.amount);
            if (cb != null) mainHandler.post(cb);
        });
    }

    /** Async: get all payments for a job. */
    public void getPaymentsForJobAsync(long jobId, Consumer<List<PaymentEntity>> cb) {
        executor.execute(() -> {
            List<PaymentEntity> result = paymentDao.getPaymentsForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** Async: get the PAID payment for a specific job (null if unpaid). */
    public void getPaidPaymentAsync(long jobId, Consumer<PaymentEntity> cb) {
        executor.execute(() -> {
            PaymentEntity result = paymentDao.getPaidForJob(jobId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    /** Async: get all paid payments (for PagosActivity). */
    public void getAllPaidPaymentsAsync(Consumer<List<PaymentEntity>> cb) {
        executor.execute(() -> {
            List<PaymentEntity> result = paymentDao.getPaidPayments();
            mainHandler.post(() -> cb.accept(result));
        });
    }

    // ── My Jobs filter ────────────────────────────────────────────────────────

    /**
     * Returns jobs assigned to the given technicianId PLUS unassigned jobs.
     * Used for TECNICO role "Mis Jobs" filter in the Dashboard.
     * Synchronous — must be called off the main thread.
     */
    public List<Job> getJobsForTechnician(String technicianId) {
        List<JobEntity> entities = dao.getJobsForTechnician(technicianId);
        List<Job> result = new ArrayList<>();
        for (JobEntity e : entities) {
            Job job = new Job(e.id, e.cliente, e.telefono, e.direccion,
                    e.hora, e.descripcion, e.equipo, e.estado,
                    e.tipoPropiedad, e.clientePresente, e.tipoAcceso,
                    e.codigoAcceso, e.ubicacionLlave, e.instrucciones,
                    e.mascota, e.contactoRespaldo, e.notasSeguridad);
            job.notas       = e.notas != null ? e.notas : "";
            job.updatedAt   = e.updatedAt;
            job.customerId  = e.customerId;
            job.propertyId  = e.propertyId;
            job.equipmentId = e.equipmentId;
            job.technicianId = e.technicianId;
            job.prioridad   = e.prioridad;
            job.estadoDispatch = e.estadoDispatch;
            job.programadoPara = e.programadoPara;
            job.latitud = e.latitud;
            job.longitud = e.longitud;
            job.ubicacionFuente = e.ubicacionFuente;
            job.serverVersion = e.serverVersion;
            result.add(job);
        }
        return result;
    }

    /**
     * Async version of getJobsForTechnician — result delivered on main thread.
     */
    public void getJobsForTechnicianAsync(String technicianId, Consumer<List<Job>> cb) {
        executor.execute(() -> {
            List<Job> result = getJobsForTechnician(technicianId);
            mainHandler.post(() -> cb.accept(result));
        });
    }

    // FASE 13: PartsRequest desde JobDetail
    public void addPartsRequestAsync(PartsRequestEntity req, Consumer<Long> cb) {
        executor.execute(() -> {
            long id = partsRequestDao.insert(req);
            mainHandler.post(() -> { if (cb != null) cb.accept(id); });
        });
    }

    public void getPartsRequestsByJobAsync(long jobId, Consumer<List<PartsRequestEntity>> cb) {
        executor.execute(() -> {
            List<PartsRequestEntity> list = partsRequestDao.getByJob(jobId);
            mainHandler.post(() -> cb.accept(list));
        });
    }

    // Phase 58: Offline mode helpers
    public static boolean isOffline(android.content.Context ctx) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
            ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni == null || !ni.isConnected();
    }

    public static long getPendingSyncSince(android.content.Context ctx) {
        return ctx.getSharedPreferences("lc_sync_prefs", android.content.Context.MODE_PRIVATE)
            .getLong("pending_sync_since", 0L);
    }
}
