package com.lc.technician.data;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.InvoiceSyncEntity;
import com.lc.technician.model.CoverageTypeEntity;
import com.lc.technician.model.FindingEntity;
import com.lc.technician.model.FindingPhotoEntity;
import com.lc.technician.model.StockLocationEntity;
import com.lc.technician.model.StockMovementEntity;
import com.lc.technician.model.PartsRequestEntity;
import com.lc.technician.model.TechnicianEntity;
import com.lc.technician.model.WorkItemEntity;
import com.lc.technician.model.ContactEntity;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.EquipmentPhotoEntity;
import com.lc.technician.model.FieldMeasurementEntity;
import com.lc.technician.model.InspectionItemEntity;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.JobEventEntity;
import com.lc.technician.model.JobPartEntity;
import com.lc.technician.model.PartEntity;
import com.lc.technician.model.PaymentEntity;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.ProposalOptionEntity;
import com.lc.technician.model.PropertyEntity;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.model.MembershipCardEntity;
import com.lc.technician.model.MembershipCheckInEntity;
import com.lc.technician.model.TimeEventEntity;
import com.lc.technician.model.TruckStockEntity;
import com.lc.technician.model.EquipmentServiceEntity;
import com.lc.technician.model.SyncStateEntity;

@Database(entities = {JobEntity.class, PhotoEntity.class, JobEventEntity.class, ContactEntity.class,
        InspectionItemEntity.class, FieldMeasurementEntity.class, AuditLogEntity.class,
        ProposalEntity.class, ProposalOptionEntity.class, SignatureEntity.class, InvoiceSyncEntity.class,
        TimeEventEntity.class, CustomerEntity.class, PropertyEntity.class, EquipmentEntity.class,
        PartEntity.class, JobPartEntity.class, WorkItemEntity.class, PaymentEntity.class,
        TechnicianEntity.class, EquipmentPhotoEntity.class,
        FindingEntity.class, FindingPhotoEntity.class, StockLocationEntity.class,
        StockMovementEntity.class, PartsRequestEntity.class, CoverageTypeEntity.class,
        MembershipCardEntity.class, MembershipCheckInEntity.class,
        TruckStockEntity.class, EquipmentServiceEntity.class,
        SyncStateEntity.class},
        version = 47, exportSchema = true)
public abstract class LcDatabase extends RoomDatabase {

    private static volatile LcDatabase instance;

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE jobs ADD COLUMN updatedAt INTEGER");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS job_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "tipo TEXT NOT NULL, " +
                "descripcion TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL)"
            );
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE photos ADD COLUMN categoria TEXT");
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS contacts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "nombre TEXT, " +
                "telefono TEXT, " +
                "email TEXT, " +
                "rol TEXT, " +
                "esPrincipal INTEGER NOT NULL)"
            );
        }
    };

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS contacts");
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS contacts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "nombre TEXT, " +
                "telefono TEXT, " +
                "email TEXT, " +
                "rol TEXT, " +
                "esPrincipal INTEGER NOT NULL)"
            );
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS inspection_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "area TEXT, nombre TEXT, estado TEXT, hallazgo TEXT, resolucion TEXT)"
            );
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS field_measurements (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "tipo TEXT, ubicacion TEXT, valor REAL NOT NULL, unidad TEXT, nota TEXT)"
            );
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS audit_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "entityType TEXT, " +
                "entityId INTEGER NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "action TEXT, " +
                "field TEXT, " +
                "oldValue TEXT, " +
                "newValue TEXT, " +
                "actor TEXT, " +
                "timestamp INTEGER NOT NULL, " +
                "extra TEXT)"
            );
        }
    };

    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS signatures (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "proposalId INTEGER NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "nombreFirmante TEXT, " +
                "opcionAceptada TEXT, " +
                "totalAceptado REAL NOT NULL, " +
                "imagePath TEXT, " +
                "timestamp INTEGER NOT NULL, " +
                "notasFirma TEXT)"
            );
        }
    };

    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS time_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "tipo TEXT, " +
                "timestamp INTEGER NOT NULL, " +
                "nota TEXT)"
            );
        }
    };

    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "DELETE FROM job_events WHERE tipo = 'AVISO' AND id NOT IN (" +
                "  SELECT MAX(id) FROM job_events WHERE tipo = 'AVISO' " +
                "  GROUP BY descripcion" +
                ")"
            );
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS proposals (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "estado TEXT, " +
                "opcionAceptada TEXT, " +
                "notas TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL)");
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS proposal_options (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "proposalId INTEGER NOT NULL, " +
                "nivel TEXT, titulo TEXT, descripcion TEXT, " +
                "costoEquipo REAL NOT NULL, " +
                "costoManoObra REAL NOT NULL, " +
                "costoMateriales REAL NOT NULL, " +
                "costoPermiso REAL NOT NULL, " +
                "costoRemocion REAL NOT NULL, " +
                "costoAccesorios REAL NOT NULL, " +
                "markupPorcentaje REAL NOT NULL, " +
                "descuentoMonto REAL NOT NULL, " +
                "impuestoPorcentaje REAL NOT NULL, " +
                "totalCalculado REAL NOT NULL, " +
                "recomendada INTEGER NOT NULL)");
        }
    };

    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // 1. Crear las 3 tablas nuevas
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS customers (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "nombre TEXT, telefono TEXT, email TEXT, notas TEXT, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            );
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS properties (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "customerId INTEGER NOT NULL, " +
                "direccion TEXT, tipoPropiedad TEXT, " +
                "clientePresente INTEGER NOT NULL DEFAULT 1, " +
                "tipoAcceso TEXT, codigoAcceso TEXT, ubicacionLlave TEXT, " +
                "instrucciones TEXT, mascota TEXT, contactoRespaldo TEXT, notasSeguridad TEXT, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            );
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS equipment (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "propertyId INTEGER NOT NULL, " +
                "nombre TEXT, marca TEXT, modelo TEXT, serial TEXT, " +
                "tipo TEXT, installDate TEXT, ultimoServicio INTEGER, notas TEXT, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            );

            // 2. Agregar columnas nullable a jobs
            database.execSQL("ALTER TABLE jobs ADD COLUMN customerId INTEGER");
            database.execSQL("ALTER TABLE jobs ADD COLUMN propertyId INTEGER");
            database.execSQL("ALTER TABLE jobs ADD COLUMN equipmentId INTEGER");

            // 3. BACKFILL desde jobs existentes
            long now = System.currentTimeMillis();
            Cursor cursor = database.query(
                "SELECT id, cliente, telefono, direccion, equipo, tipoPropiedad, " +
                "clientePresente, tipoAcceso, codigoAcceso, ubicacionLlave, " +
                "instrucciones, mascota, contactoRespaldo, notasSeguridad FROM jobs"
            );

            java.util.Map<String, Long> customerCache  = new java.util.HashMap<>();
            java.util.Map<String, Long> propertyCache  = new java.util.HashMap<>();
            java.util.Map<String, Long> equipmentCache = new java.util.HashMap<>();

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long   jobId           = cursor.getLong(0);
                    String cliente         = cursor.getString(1);
                    String telefono        = cursor.getString(2);
                    String direccion       = cursor.getString(3);
                    String equipo          = cursor.getString(4);
                    String tipoPropiedad   = cursor.getString(5);
                    int    clientePresente = cursor.getInt(6);
                    String tipoAcceso      = cursor.getString(7);
                    String codigoAcceso    = cursor.getString(8);
                    String ubicacionLlave  = cursor.getString(9);
                    String instrucciones   = cursor.getString(10);
                    String mascota         = cursor.getString(11);
                    String contactoRespaldo= cursor.getString(12);
                    String notasSeguridad  = cursor.getString(13);

                    // ── Customer ─────────────────────────────────────────────
                    long customerId = 0;
                    if (cliente != null && !cliente.isEmpty()) {
                        Long cached = customerCache.get(cliente);
                        if (cached != null) {
                            customerId = cached;
                        } else {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put("nombre",    cliente);
                            cv.put("telefono",  telefono);
                            cv.put("createdAt", now);
                            cv.put("updatedAt", now);
                            customerId = database.insert("customers",
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, cv);
                            if (customerId > 0) customerCache.put(cliente, customerId);
                        }
                    }

                    // ── Property ─────────────────────────────────────────────
                    long propertyId = 0;
                    if (customerId > 0 && direccion != null && !direccion.isEmpty()) {
                        String key = customerId + "|" + direccion;
                        Long cached = propertyCache.get(key);
                        if (cached != null) {
                            propertyId = cached;
                        } else {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put("customerId",       customerId);
                            cv.put("direccion",        direccion);
                            cv.put("tipoPropiedad",    tipoPropiedad);
                            cv.put("clientePresente",  clientePresente);
                            cv.put("tipoAcceso",       tipoAcceso);
                            cv.put("codigoAcceso",     codigoAcceso);
                            cv.put("ubicacionLlave",   ubicacionLlave);
                            cv.put("instrucciones",    instrucciones);
                            cv.put("mascota",          mascota);
                            cv.put("contactoRespaldo", contactoRespaldo);
                            cv.put("notasSeguridad",   notasSeguridad);
                            cv.put("createdAt",        now);
                            cv.put("updatedAt",        now);
                            propertyId = database.insert("properties",
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, cv);
                            if (propertyId > 0) propertyCache.put(key, propertyId);
                        }
                    }

                    // ── Equipment ─────────────────────────────────────────────
                    long equipmentId = 0;
                    if (propertyId > 0 && equipo != null && !equipo.isEmpty()) {
                        String key = propertyId + "|" + equipo;
                        Long cached = equipmentCache.get(key);
                        if (cached != null) {
                            equipmentId = cached;
                        } else {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put("propertyId", propertyId);
                            cv.put("nombre",     equipo);
                            cv.put("createdAt",  now);
                            cv.put("updatedAt",  now);
                            equipmentId = database.insert("equipment",
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, cv);
                            if (equipmentId > 0) equipmentCache.put(key, equipmentId);
                        }
                    }

                    // ── Vincular al job ───────────────────────────────────────
                    database.execSQL(
                        "UPDATE jobs SET customerId = ?, propertyId = ?, equipmentId = ? WHERE id = ?",
                        new Object[]{
                            customerId  > 0 ? customerId  : null,
                            propertyId  > 0 ? propertyId  : null,
                            equipmentId > 0 ? equipmentId : null,
                            jobId
                        }
                    );
                }
                cursor.close();
            }
        }
    };

    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS parts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "sku TEXT, nombre TEXT, categoria TEXT, " +
                "costo REAL NOT NULL DEFAULT 0, " +
                "precio REAL NOT NULL DEFAULT 0, " +
                "stockActual INTEGER NOT NULL DEFAULT 0, " +
                "stockMinimo INTEGER NOT NULL DEFAULT 0, " +
                "proveedor TEXT, notas TEXT, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            );
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS job_parts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "partId INTEGER NOT NULL, " +
                "cantidad INTEGER NOT NULL DEFAULT 1, " +
                "costoUnitario REAL NOT NULL DEFAULT 0, " +
                "precioUnitario REAL NOT NULL DEFAULT 0, " +
                "nota TEXT, " +
                "timestamp INTEGER NOT NULL)"
            );
        }
    };

    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS work_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "tipo TEXT, " +
                "itemCode TEXT, " +
                "descripcion TEXT, " +
                "cantidad REAL, " +
                "unidad TEXT, " +
                "precioUnitario REAL, " +
                "total REAL, " +
                "partId INTEGER, " +
                "nota TEXT, " +
                "timestamp INTEGER NOT NULL)"
            );
        }
    };

    public static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS payments (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "jobId INTEGER NOT NULL, " +
                "amount REAL NOT NULL, " +
                "method TEXT, " +
                "status TEXT, " +
                "timestamp INTEGER NOT NULL, " +
                "reference TEXT, " +
                "cliente TEXT, " +
                "notes TEXT)"
            );
        }
    };

    public static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // ── contacts: add customerId, propertyId, isDeleted ─────────────────
            database.execSQL("ALTER TABLE contacts ADD COLUMN customerId INTEGER");
            database.execSQL("ALTER TABLE contacts ADD COLUMN propertyId INTEGER");
            database.execSQL("ALTER TABLE contacts ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");

            // Backfill customerId from the associated job's customerId
            database.execSQL(
                "UPDATE contacts SET customerId = " +
                "(SELECT jobs.customerId FROM jobs WHERE jobs.id = contacts.jobId) " +
                "WHERE customerId IS NULL AND jobId IS NOT NULL"
            );

            // ── proposal_options: add markupUsed, taxUsed snapshot columns ──────
            database.execSQL("ALTER TABLE proposal_options ADD COLUMN markupUsed REAL NOT NULL DEFAULT 15");
            database.execSQL("ALTER TABLE proposal_options ADD COLUMN taxUsed REAL NOT NULL DEFAULT 7");

            // Backfill existing options: snapshot = currently stored values
            database.execSQL(
                "UPDATE proposal_options SET markupUsed = markupPorcentaje, taxUsed = impuestoPorcentaje " +
                "WHERE markupUsed = 15 AND taxUsed = 7"
            );
        }
    };

    public static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE jobs ADD COLUMN technicianId TEXT");
        }
    };

    public static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Create technicians table
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS technicians (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "nombre TEXT, " +
                "email TEXT, " +
                "telefono TEXT, " +
                "rol TEXT, " +
                "activo INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0)"
            );
            // Backfill: create technician records from existing job.technicianId values
            database.execSQL(
                "INSERT OR IGNORE INTO technicians (id, nombre, activo, createdAt, updatedAt) " +
                "SELECT DISTINCT technicianId, technicianId, 1, " +
                "strftime('%s','now')*1000, strftime('%s','now')*1000 " +
                "FROM jobs WHERE technicianId IS NOT NULL AND technicianId != ''"
            );
        }
    };

    /**
     * v20 -> v21
     *
     * Extend the existing L&C equipment table with the technical identity,
     * HVAC specifications, pairing, lifecycle and scanner state required
     * by the BFS equipment workflow.
     *
     * All new columns are nullable so every existing v20 record remains
     * valid and no legacy customer/job/equipment data is destroyed.
     */
    static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE equipment ADD COLUMN tons REAL");
            database.execSQL("ALTER TABLE equipment ADD COLUMN refrigerante TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN voltaje TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN fase TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN seer REAL");
            database.execSQL("ALTER TABLE equipment ADD COLUMN manufactureDate TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN ubicacion TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN heatPump INTEGER");
            database.execSQL("ALTER TABLE equipment ADD COLUMN systemRole TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN pairedEquipmentId INTEGER");
            database.execSQL("ALTER TABLE equipment ADD COLUMN lifecycleStatus TEXT");
            database.execSQL("ALTER TABLE equipment ADD COLUMN replacedByEquipmentId INTEGER");
            database.execSQL("ALTER TABLE equipment ADD COLUMN scanStatus TEXT");
        }
    };

    /**
     * v21 -> v22
     *
     * Phase 7: Nameplate OCR — add equipment_photos table.
     * Using Option B (separate table) to avoid touching PhotoEntity.jobId
     * which is a primitive long used extensively by the job photo flow.
     *
     * All job photo code remains untouched; equipment OCR photos are
     * stored in this new table.
     */
    static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS equipment_photos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "equipment_id INTEGER NOT NULL, " +
                "file_path TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL, " +
                "raw_ocr_text TEXT)"
            );
        }
    };

    /**
     * v22 -> v23
     *
     * Phase 7 closure: register EquipmentPhotoEntity with Room.
     * The table was already created via raw execSQL in MIGRATION_21_22.
     * This migration bumps the version so Room reconciles its schema hash
     * without touching the physical table structure.
     */
    static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // equipment_photos table already exists from v22 raw execSQL.
            // This migration registers it with Room without changes.
        }
    };

    /**
     * v23 -> v24
     *
     * Phase 8: Inspection → Finding → WorkItem
     * - Create findings table (FindingEntity)
     * - Create finding_photos table (FindingPhotoEntity)
     * - Add finding_id to work_items (links quote line to originating finding)
     * - Add finding_id to field_measurements (links measurement to finding)
     */
    static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `findings` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`job_id` INTEGER NOT NULL," +
                "`equipment_id` INTEGER," +
                "`inspection_item_id` INTEGER," +
                "`area` TEXT NOT NULL," +
                "`component` TEXT NOT NULL," +
                "`problem` TEXT NOT NULL," +
                "`diagnosis` TEXT," +
                "`resolution` TEXT," +
                "`severity` TEXT NOT NULL," +
                "`status` TEXT NOT NULL," +
                "`technician_notes` TEXT," +
                "`created_at` INTEGER NOT NULL," +
                "`updated_at` INTEGER NOT NULL)");

            database.execSQL("CREATE TABLE IF NOT EXISTS `finding_photos` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`finding_id` INTEGER NOT NULL," +
                "`file_path` TEXT NOT NULL," +
                "`category` TEXT," +
                "`timestamp` INTEGER NOT NULL)");

            database.execSQL("ALTER TABLE work_items ADD COLUMN findingId INTEGER");
            database.execSQL("ALTER TABLE field_measurements ADD COLUMN findingId INTEGER");
        }
    };

        static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS stock_locations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT, name TEXT, technicianId INTEGER, " +
                "isActive INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL)"
            );
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS stock_movements (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "partId INTEGER NOT NULL, locationId INTEGER NOT NULL, " +
                "movementType TEXT, quantity INTEGER NOT NULL, " +
                "fromLocationId INTEGER, toLocationId INTEGER, " +
                "referenceType TEXT, referenceId INTEGER, " +
                "notes TEXT, technicianId INTEGER, " +
                "timestamp INTEGER NOT NULL)"
            );
            database.execSQL(
                "INSERT OR IGNORE INTO stock_locations (id, type, name, isActive, createdAt) " +
                "VALUES (1, 'WAREHOUSE', 'Warehouse Principal', 1, " + System.currentTimeMillis() + ")"
            );
        }
    };

        static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS parts_requests (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, technicianId INTEGER NOT NULL, " +
                "status TEXT, itemsJson TEXT, totalEstimated REAL NOT NULL DEFAULT 0, " +
                "notes TEXT, approvedBy INTEGER, approvedAt INTEGER, " +
                "rejectionReason TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            );
        }
    };

    static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE proposals ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE proposals ADD COLUMN lockedAt INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE proposals ADD COLUMN version INTEGER NOT NULL DEFAULT 1");
            // Backfill: proposals already ACCEPTED that have a signature get version=1, isLocked=0
            // (lock is only set going forward via SignatureActivity)
        }
    };

    static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `membership_cards` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`cardNumber` TEXT, "
                    + "`customerId` INTEGER NOT NULL, "
                    + "`coverageTypeId` INTEGER, "
                    + "`status` TEXT, "
                    + "`paymentStatus` TEXT, "
                    + "`issueDate` INTEGER NOT NULL, "
                    + "`expiryDate` INTEGER NOT NULL, "
                    + "`lastServiceDate` INTEGER, "
                    + "`nextServiceDate` INTEGER, "
                    + "`notes` TEXT, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_membership_cards_cardNumber` "
                    + "ON `membership_cards` (`cardNumber`)");
        }
    };

    static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `membership_check_ins` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`cardId` INTEGER NOT NULL, "
                    + "`jobId` INTEGER, "
                    + "`checkInDate` INTEGER NOT NULL, "
                    + "`technicianName` TEXT, "
                    + "`notes` TEXT, "
                    + "`createdAt` INTEGER NOT NULL)");
        }
    };

    // WARNING: deductible/coverage seed values are PLACEHOLDER — admin must configure
    // real values per BFS official policy before production use.
    static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS coverage_types (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "code TEXT, displayName TEXT, description TEXT, " +
                "deductibleAmount REAL NOT NULL DEFAULT 0, " +
                "coveragePercentage REAL NOT NULL DEFAULT 0, " +
                "requiresApproval INTEGER NOT NULL DEFAULT 0, " +
                "isActive INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0)"
            );
            database.execSQL("ALTER TABLE jobs ADD COLUMN coverageTypeId INTEGER");
            database.execSQL("ALTER TABLE jobs ADD COLUMN coverageApprovedAmount REAL");
            database.execSQL("ALTER TABLE jobs ADD COLUMN coverageAuthNumber TEXT");
            long now = System.currentTimeMillis();
            database.execSQL("INSERT INTO coverage_types (code,displayName,description,deductibleAmount,coveragePercentage,requiresApproval,isActive,createdAt,updatedAt) VALUES ('GOLD','Gold Maintenance','Plan de mantenimiento Gold (PLACEHOLDER)',0,100,0,1," + now + "," + now + ")");
            database.execSQL("INSERT INTO coverage_types (code,displayName,description,deductibleAmount,coveragePercentage,requiresApproval,isActive,createdAt,updatedAt) VALUES ('SILVER','Silver Maintenance','Plan de mantenimiento Silver (PLACEHOLDER)',50,80,0,1," + now + "," + now + ")");
            database.execSQL("INSERT INTO coverage_types (code,displayName,description,deductibleAmount,coveragePercentage,requiresApproval,isActive,createdAt,updatedAt) VALUES ('BRONZE','Bronze Maintenance','Plan de mantenimiento Bronze (PLACEHOLDER)',100,60,0,1," + now + "," + now + ")");
            database.execSQL("INSERT INTO coverage_types (code,displayName,description,deductibleAmount,coveragePercentage,requiresApproval,isActive,createdAt,updatedAt) VALUES ('HOME_WARRANTY','Home Warranty','Garantía del hogar (PLACEHOLDER)',75,90,1,1," + now + "," + now + ")");
            database.execSQL("INSERT INTO coverage_types (code,displayName,description,deductibleAmount,coveragePercentage,requiresApproval,isActive,createdAt,updatedAt) VALUES ('COD','Cash on Delivery','Pago contra entrega (PLACEHOLDER)',0,0,0,1," + now + "," + now + ")");
            database.execSQL("INSERT INTO coverage_types (code,displayName,description,deductibleAmount,coveragePercentage,requiresApproval,isActive,createdAt,updatedAt) VALUES ('NONE','Sin cobertura','Sin cobertura asignada',0,0,0,1," + now + "," + now + ")");
        }
    };

    static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Parts: add new columns
            database.execSQL("ALTER TABLE parts ADD COLUMN marca TEXT");
            database.execSQL("ALTER TABLE parts ADD COLUMN modelo TEXT");
            database.execSQL("ALTER TABLE parts ADD COLUMN unidad TEXT");
            database.execSQL("ALTER TABLE parts ADD COLUMN activo INTEGER NOT NULL DEFAULT 1");
            // New truck_stock table
            database.execSQL("CREATE TABLE IF NOT EXISTS truck_stock (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "partId INTEGER NOT NULL, " +
                "ubicacion TEXT, " +
                "cantidad REAL NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL)");
            // New stock_movements_v2 table
            database.execSQL("CREATE TABLE IF NOT EXISTS stock_movements_v2 (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "partId INTEGER NOT NULL, " +
                "tipo TEXT, " +
                "cantidad REAL NOT NULL, " +
                "ubicacionOrigen TEXT, " +
                "ubicacionDestino TEXT, " +
                "jobId INTEGER, " +
                "notas TEXT, " +
                "timestamp INTEGER NOT NULL)");
        }
    };

    static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // work_items: add proposalId and totalLinea
            database.execSQL("ALTER TABLE work_items ADD COLUMN proposalId INTEGER");
            database.execSQL("ALTER TABLE work_items ADD COLUMN totalLinea REAL");
        }
    };

    static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Equipment: add lifecycle fields
            database.execSQL("ALTER TABLE equipment ADD COLUMN proximoMantenimiento INTEGER");
            database.execSQL("ALTER TABLE equipment ADD COLUMN intervaloMantenimientoDias INTEGER NOT NULL DEFAULT 365");
            database.execSQL("ALTER TABLE equipment ADD COLUMN ultimoServicioJobId INTEGER");
            database.execSQL("ALTER TABLE equipment ADD COLUMN estado TEXT DEFAULT 'ACTIVO'");
            // equipment_services table
            database.execSQL("CREATE TABLE IF NOT EXISTS equipment_services (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "equipmentId INTEGER NOT NULL, " +
                "jobId INTEGER, " +
                "tipoServicio TEXT, " +
                "descripcion TEXT, " +
                "costoTotal REAL NOT NULL DEFAULT 0, " +
                "timestamp INTEGER NOT NULL)");
        }
    };

    static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Jobs: dispatch fields
            database.execSQL("ALTER TABLE jobs ADD COLUMN prioridad TEXT DEFAULT 'NORMAL'");
            database.execSQL("ALTER TABLE jobs ADD COLUMN programadoPara INTEGER");
            database.execSQL("ALTER TABLE jobs ADD COLUMN estadoDispatch TEXT DEFAULT 'QUEUE'");
            // Technicians: extra fields
            database.execSQL("ALTER TABLE technicians ADD COLUMN zona TEXT");
            database.execSQL("ALTER TABLE technicians ADD COLUMN ubicacionActual TEXT");
        }
    };

    static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // work_items: add createdAt and updatedAt timestamps
            database.execSQL("ALTER TABLE work_items ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE work_items ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            // Backfill: use existing timestamp value
            database.execSQL("UPDATE work_items SET createdAt = timestamp, updatedAt = timestamp WHERE createdAt = 0");
        }
    };

    static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // technicians: add dispatch + rating fields
            database.execSQL("ALTER TABLE technicians ADD COLUMN status TEXT DEFAULT 'ACTIVE'");
            database.execSQL("ALTER TABLE technicians ADD COLUMN currentJobId INTEGER");
            database.execSQL("ALTER TABLE technicians ADD COLUMN jobsCompleted REAL NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE technicians ADD COLUMN rating REAL NOT NULL DEFAULT 5.0");
            // Backfill status from activo boolean
            database.execSQL("UPDATE technicians SET status = CASE WHEN activo = 1 THEN 'ACTIVE' ELSE 'INACTIVE' END");
            // Seed 3 demo technicians if table is nearly empty
            long now = System.currentTimeMillis();
            database.execSQL("INSERT OR IGNORE INTO technicians (id,nombre,email,telefono,rol,activo,status,jobsCompleted,rating,createdAt,updatedAt) VALUES ('admin-01','Admin LC','admin@lc.com','','" + TechnicianEntity.ROLE_ADMIN + "',1,'ACTIVE',0,5.0," + now + "," + now + ")");
            database.execSQL("INSERT OR IGNORE INTO technicians (id,nombre,email,telefono,rol,activo,status,jobsCompleted,rating,createdAt,updatedAt) VALUES ('tech-carlos','Carlos Ocampo','carlos@lc.com','','" + TechnicianEntity.ROLE_TECH + "',1,'ACTIVE',0,5.0," + now + "," + now + ")");
            database.execSQL("INSERT OR IGNORE INTO technicians (id,nombre,email,telefono,rol,activo,status,jobsCompleted,rating,createdAt,updatedAt) VALUES ('tech-juan','Juan Pérez','juan@lc.com','','" + TechnicianEntity.ROLE_TECH + "',1,'ACTIVE',0,5.0," + now + "," + now + ")");
        }
    };

    static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // sync_state table
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_state (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "entityType TEXT, " +
                "lastSyncAt INTEGER, " +
                "lastError TEXT)"
            );
            // work_items: sync flags
            database.execSQL("ALTER TABLE work_items ADD COLUMN synced INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE work_items ADD COLUMN lastSyncedAt INTEGER");
            // technicians: sync flags
            database.execSQL("ALTER TABLE technicians ADD COLUMN synced INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE technicians ADD COLUMN lastSyncedAt INTEGER");
            // jobs: sync flags
            database.execSQL("ALTER TABLE jobs ADD COLUMN synced INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE jobs ADD COLUMN lastSyncedAt INTEGER");
            // Clean up contaminated technician entries (from old job.technicianId backfill)
            database.execSQL("DELETE FROM technicians WHERE rol IS NULL OR rol NOT IN ('ADMIN','TECNICO','DISPATCHER')");
        }
    };

    static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Remove duplicate "Admin" entry created by old technicianId backfill (non-seeded UUID)
            database.execSQL(
                "DELETE FROM technicians WHERE nombre = 'Admin' AND id != 'admin-01'"
            );
            // Normalize English estado values to Spanish
            database.execSQL("UPDATE jobs SET estado = 'PENDIENTE' WHERE estado = 'PENDING'");
            database.execSQL("UPDATE jobs SET estado = 'COMPLETADO' WHERE estado = 'COMPLETED'");
            database.execSQL("UPDATE jobs SET estado = 'EN RUTA' WHERE estado IN ('IN_ROUTE','EN_ROUTE')");
            database.execSQL("UPDATE jobs SET estado = 'CANCELADO' WHERE estado = 'CANCELLED'");
        }
    };

    static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Recalculate totalLinea for work items where it was never set (null or 0)
            database.execSQL(
                "UPDATE work_items SET totalLinea = cantidad * precioUnitario " +
                "WHERE (totalLinea IS NULL OR totalLinea = 0) " +
                "AND cantidad IS NOT NULL AND cantidad > 0 " +
                "AND precioUnitario IS NOT NULL AND precioUnitario > 0"
            );
        }
    };

    /*
     * 39→40 y 40→41 borraban trabajos (y su auditoría) cuando el nombre del cliente contenía
     * "Prueba", "E2E", "MiamiCliente"… LIKE no distingue mayúsculas: también se perdían clientes
     * reales como "Restaurante La Prueba". Ahora son no-op: ninguna versión cambia el esquema
     * (solo había DELETE) y todas las órdenes se conservan. Limpiar datos de prueba, si hace falta,
     * debe hacerse con una herramienta explícita, con vista previa y confirmación, nunca al actualizar.
     */
    static final Migration MIGRATION_39_40 = new Migration(39, 40) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Sin cambios de esquema ni de datos (antes: DELETE por patrón de nombre de cliente)
        }
    };

    static final Migration MIGRATION_40_41 = new Migration(40, 41) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Sin cambios de esquema ni de datos (antes: DELETE de jobs con cliente LIKE '%Prueba%' / '%E2E%')
        }
    };

    /** v42: coordenadas opcionales de la dirección del servicio. Solo ADD COLUMN, no destructiva. */
    static final Migration MIGRATION_41_42 = new Migration(41, 42) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            for (String t : new String[]{"jobs", "properties"}) {
                database.execSQL("ALTER TABLE " + t + " ADD COLUMN latitud REAL");
                database.execSQL("ALTER TABLE " + t + " ADD COLUMN longitud REAL");
                database.execSQL("ALTER TABLE " + t + " ADD COLUMN ubicacionFuente TEXT");
            }
        }
    };

    /** v43: versión del servidor por trabajo (sync v2). Solo ADD COLUMN, no destructiva. */
    static final Migration MIGRATION_42_43 = new Migration(42, 43) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE jobs ADD COLUMN serverVersion INTEGER");
        }
    };

    /** v44: anulación lógica de partidas de trabajo (no se borran filas; solo columnas nuevas). */
    static final Migration MIGRATION_43_44 = new Migration(43, 44) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE work_items ADD COLUMN anulado INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE work_items ADD COLUMN anuladoAt INTEGER");
        }
    };

    /** v45: propuestas y facturas sincronizables (solo columnas y tabla nuevas; no se borra nada). */
    static final Migration MIGRATION_44_45 = new Migration(44, 45) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE proposals ADD COLUMN syncState INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE proposals ADD COLUMN serverId INTEGER");
            database.execSQL("ALTER TABLE proposals ADD COLUMN serverRevision INTEGER");
            database.execSQL("ALTER TABLE proposals ADD COLUMN serverEstado TEXT");
            database.execSQL("ALTER TABLE proposals ADD COLUMN contenidoSha TEXT");
            database.execSQL("ALTER TABLE proposals ADD COLUMN firmanteServidor TEXT");
            database.execSQL("ALTER TABLE proposals ADD COLUMN opcionServidor TEXT");
            database.execSQL("ALTER TABLE proposals ADD COLUMN totalServidor REAL");
            database.execSQL("ALTER TABLE proposals ADD COLUMN decididaAt INTEGER");
            database.execSQL("ALTER TABLE proposals ADD COLUMN venceAt INTEGER");
            database.execSQL("ALTER TABLE proposals ADD COLUMN motivoRechazo TEXT");
            database.execSQL("ALTER TABLE proposals ADD COLUMN enviadaAt INTEGER");
            database.execSQL("CREATE TABLE IF NOT EXISTS `invoice_sync` (`jobId` INTEGER NOT NULL, `clientInvoiceId` "
                    + "TEXT NOT NULL, `syncState` INTEGER NOT NULL, `serverId` INTEGER, `numero` TEXT, "
                    + "`estadoServidor` TEXT, `subtotal` REAL, `impuesto` REAL, `total` REAL, `motivo` TEXT, "
                    + "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`jobId`))");
        }
    };

    /** v46: firmas, repuestos usados, pagos reportados y PDF de factura sincronizables (solo ADD COLUMN). */
    static final Migration MIGRATION_45_46 = new Migration(45, 46) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE signatures ADD COLUMN syncState INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE signatures ADD COLUMN serverId INTEGER");
            database.execSQL("ALTER TABLE signatures ADD COLUMN motivoRechazo TEXT");
            database.execSQL("ALTER TABLE job_parts ADD COLUMN syncState INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE job_parts ADD COLUMN anulado INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE job_parts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE payments ADD COLUMN syncState INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE payments ADD COLUMN syncLocalId INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE payments ADD COLUMN serverId INTEGER");
            database.execSQL("ALTER TABLE payments ADD COLUMN serverEstado TEXT");
            database.execSQL("ALTER TABLE payments ADD COLUMN motivo TEXT");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN pdfPath TEXT");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN pdfSyncState INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN pdfMotivo TEXT");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN descuento REAL");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN pagado REAL");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN saldo REAL");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN propuestaTotal REAL");
        }
    };

    /** v47: comparación factura/propuesta aprobada y aprobación adicional (solo ADD COLUMN). */
    static final Migration MIGRATION_46_47 = new Migration(46, 47) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN aprobadoTotal REAL");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN diferencia REAL");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN tipoComparacion TEXT");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN variacionId INTEGER");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN variacionEstado TEXT");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN codigoRechazo TEXT");
            database.execSQL("ALTER TABLE invoice_sync ADD COLUMN confirmaMenor INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract InvoiceSyncDao invoiceSyncDao();

    public abstract JobDao jobDao();
    public abstract PhotoDao photoDao();
    public abstract JobEventDao jobEventDao();
    public abstract ContactDao contactDao();
    public abstract InspectionItemDao inspectionItemDao();
    public abstract FieldMeasurementDao fieldMeasurementDao();
    public abstract AuditLogDao auditLogDao();
    public abstract ProposalDao proposalDao();
    public abstract ProposalOptionDao proposalOptionDao();
    public abstract SignatureDao signatureDao();
    public abstract TimeEventDao timeEventDao();
    public abstract CustomerDao customerDao();
    public abstract PropertyDao propertyDao();
    public abstract EquipmentDao equipmentDao();
    public abstract PartDao partDao();
    public abstract JobPartDao jobPartDao();
    public abstract WorkItemDao workItemDao();
    public abstract PaymentDao paymentDao();
    public abstract TechnicianDao technicianDao();
    public abstract EquipmentPhotoDao equipmentPhotoDao();
    public abstract FindingDao findingDao();
    public abstract FindingPhotoDao findingPhotoDao();
    public abstract StockLocationDao stockLocationDao();
    public abstract StockMovementDao stockMovementDao();
    public abstract PartsRequestDao partsRequestDao();
    public abstract CoverageDao coverageDao();
    public abstract MembershipCardDao membershipCardDao();
    public abstract MembershipCheckInDao membershipCheckInDao();
    public abstract TruckStockDao truckStockDao();
    public abstract EquipmentServiceDao equipmentServiceDao();
    public abstract SyncStateDao syncStateDao();

    public static LcDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (LcDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            LcDatabase.class,
                            "lc_database"
                    )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                            MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                            MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                            MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
                            MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30,
                            MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34,
                            MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38,
                            MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41,
                            MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47)
                    .build();
                }
            }
        }
        return instance;
    }
}
