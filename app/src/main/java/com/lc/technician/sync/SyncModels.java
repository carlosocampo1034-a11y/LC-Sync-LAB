package com.lc.technician.sync;

import java.util.List;

public class SyncModels {

    public static class WorkItemDto {
        public long id;
        public long jobId;
        public String tipo;
        public String descripcion;
        public double cantidad;
        public double precioUnitario;
        public double subtotal;
        public long updatedAt;
    }

    public static class TechnicianDto {
        public String id;
        public String nombre;
        public String email;
        public String rol;
        public String status;
        public boolean activo;
        public long updatedAt;
    }

    public static class JobDto {
        public long id;
        public String cliente;
        public String hora;
        public String estado;
        public String technicianId;
        public long updatedAt;
    }

    public static class MembershipDto {
        public long id;
        public String cardNumber;
        public long customerId;
        public Long coverageTypeId;
        public String status;
        public String paymentStatus;
        public long issueDate;
        public long expiryDate;
        public Long lastServiceDate;
        public Long nextServiceDate;
        public String notes;
        public long updatedAt;
    }

    public static class AuditLogDto {
        public long id;
        public String action;
        public String entityType;
        public long entityId;
        public long jobId;
        public String field;
        public String oldValue;
        public String newValue;
        public String actor;
        public long timestamp;
        public String extra;
        public long updatedAt;
    }

    public static class MembershipCheckInDto {
        public long id;
        public long cardId;
        public Long jobId;
        public long checkInDate;
        public String technicianName;
        public String notes;
        public long createdAt;
        public long updatedAt;
    }

    public static class PhotoDto {
        public long id;
        public long jobId;
        public String filename;
        public String base64Data;
        public Long sizeBytes;
        public String mimeType;
        public long timestamp;
        public long updatedAt;
    }

    public static class SyncPushRequest {
        public List<WorkItemDto> work_items;
        public List<TechnicianDto> technicians;
        public List<JobDto> jobs;
        public List<MembershipDto> memberships;
        public List<AuditLogDto> audit_logs;
        public List<MembershipCheckInDto> membership_check_ins;
        public List<PhotoDto> photos;
        @com.google.gson.annotations.SerializedName("deviceId")
        public String deviceId;
        @com.google.gson.annotations.SerializedName("deviceName")
        public String deviceName;
    }

    public static class SyncPushResponse {
        public int accepted;
        public int rejected;
        public long timestamp;
        // Contrato ampliado (opcional): confirmación por trabajo. El servidor histórico no los envía (null).
        public List<Long> accepted_job_ids;
        public List<Long> rejected_job_ids;
    }

    public static class SyncPullResponse {
        public List<WorkItemDto> work_items;
        public List<TechnicianDto> technicians;
        public List<JobDto> jobs;
        public long timestamp;
        public long server_time; // Flask returns server_time
    }

    public static class HealthResponse {
        public String status;
        public String version;
        public long timestamp;
        public boolean db;
    }

    public static class StatsResponse {
        public int total_jobs;
        public int total_work_items;
        public double total_revenue;
        public int active_technicians;
        public long timestamp;
    }

    // Fase 44: Push Notifications
    public static class JobNotification {
        @com.google.gson.annotations.SerializedName("id")
        public long id;
        @com.google.gson.annotations.SerializedName("cliente")
        public String cliente;
        @com.google.gson.annotations.SerializedName("hora")
        public String hora;
        @com.google.gson.annotations.SerializedName("technicianId")
        public Long technicianId;
    }

    public static class RenewalNotification {
        @com.google.gson.annotations.SerializedName("entityId")
        public long entityId;
        @com.google.gson.annotations.SerializedName("timestamp")
        public long timestamp;
        @com.google.gson.annotations.SerializedName("actor")
        public String actor;
    }

    public static class NotificationResponse {
        @com.google.gson.annotations.SerializedName("since")
        public long since;
        @com.google.gson.annotations.SerializedName("now")
        public long now;
        @com.google.gson.annotations.SerializedName("jobs")
        public java.util.List<JobNotification> jobs;
        @com.google.gson.annotations.SerializedName("renewals")
        public java.util.List<RenewalNotification> renewals;
    }

    // ═══ Contrato v2 (backend de laboratorio, ~/LC/lab/backend) ═════════════════

    public static class CustomerLocalDto {
        public Long localId;
        public String nombre;
        public String telefono;
    }

    public static class PropertyLocalDto {
        public Long localId;
        public String direccion;
        public String tipoPropiedad;
    }

    /** Orden completa. Los nombres coinciden con la API (lc_lab/jobs.py CAMPOS + SECRETOS). */
    public static class JobV2Dto {
        public long id;
        public Integer version;          // versión del servidor que el teléfono conoce (base del merge)
        public Long updatedAt;           // versión local: el servidor la devuelve para confirmar ESTA versión
        public String estado;
        public String estadoDispatch;
        public String prioridad;
        public String categoria;
        public String technicianId;
        public String cliente;
        public String telefono;
        public String direccion;
        public Double latitud;
        public Double longitud;
        public String ubicacionFuente;
        public String hora;
        public Long programadoPara;
        public String zonaHoraria;
        public String ventanaPreferida;
        public String descripcion;
        public String equipo;
        public String tipoPropiedad;
        public Boolean clientePresente;
        public String tipoAcceso;
        public String codigoAcceso;       // null en respuestas si el usuario no puede verlo
        public String ubicacionLlave;
        public Boolean codigoAccesoProtegido;
        public Boolean ubicacionLlaveProtegido;
        public String instrucciones;
        public String mascota;
        public String contactoRespaldo;
        public String notasSeguridad;
        public String notas;
        public String coverageType;
        public String coverageAuth;
        public CustomerLocalDto customerLocal;
        public PropertyLocalDto propertyLocal;
        public Integer conflictosPendientes;   // solo en respuestas del servidor
        public List<PropuestaEstadoV2> propuestas;   // solo en respuestas del servidor (técnico)
        public FacturaV2 factura;                    // solo en respuestas del servidor
        public List<PagoV2> pagos;                   // solo en respuestas del servidor
        public Double descuentoAutorizado;           // solo en respuestas del servidor
        public List<VariacionV2> variaciones;        // solo en respuestas del servidor
    }

    public static class PhotoV2Dto {
        public long id;
        public long jobId;
        public String filename;
        public String mimeType;
        public String categoria;
        public String base64Data;
    }

    public static class EventV2Dto {
        public String clientEventId;
        public long jobId;
        public String tipo;               // NOTA | MEDICION | DIAGNOSTICO
        public java.util.Map<String, Object> payload;
    }

    public static class WorkItemV2Dto {
        public long id;
        public long jobId;
        public String tipo;
        public String descripcion;
        public Double cantidad;
        public Double precioUnitario;
        public long updatedAt;
        public Boolean anulado;   // true = anulación lógica (null se omite en el JSON)
    }

    public static class RejectedItem {
        public Long id;
        public String reason;
    }

    public static class WorkItemsAck {
        public List<Long> accepted_ids;
        public List<RejectedItem> rejected;
    }

    public static class PushV2Request {
        public String deviceId;
        public List<JobV2Dto> jobs;
        public List<WorkItemV2Dto> work_items;
        public List<Long> deleted_work_item_ids;
        public List<PhotoV2Dto> photos;
        public List<EventV2Dto> events;
        public List<ProposalV2Dto> proposals;
        public List<InvoiceReqV2Dto> invoices;
        public List<SignatureV2Dto> signatures;
        public List<PartUsedV2Dto> parts_used;
        public List<PaymentV2Dto> payments;
        public List<DocumentV2Dto> documents;
    }

    // ── Trabajo de campo: firmas, repuestos usados, pagos reportados, PDF de factura ──
    public static class SignatureV2Dto {
        public long localId, jobId, firmadaAt;
        public String tipo, firmante, opcion, pngBase64;
        public Double total;
        public Long proposalLocalId;
    }

    public static class PartUsedV2Dto {
        public long localId, jobId, updatedAt;
        public String sku, nombre;
        public double cantidad, precioUnitario;
        public Boolean anulado;
    }

    public static class PaymentV2Dto {
        public long localId, jobId;
        public double monto;
        public String metodo, referencia;
    }

    public static class DocumentV2Dto {
        public long jobId;
        public String numero, pdfBase64;
    }

    public static class PagoV2 {
        public Long id, localId, jobId, revisadoAt;
        public Double monto;
        public String metodo, estado, motivo;
    }

    public static class ResultadoCampoV2 {
        public Long localId, jobId, id;
        public String status, reason, numero;
        public PropuestaEstadoV2 propuesta;
        public PagoV2 pago;
    }

    public static class ResultadosCampo { public List<ResultadoCampoV2> results; }

    // ── Fase 6: propuestas y facturas ──
    public static class ProposalOptionV2Dto {
        public String nivel, titulo, descripcion;
        public double costoEquipo, costoManoObra, costoMateriales, costoPermiso, costoRemocion, costoAccesorios;
        public double markupPorcentaje, descuentoMonto, impuestoPorcentaje, totalCalculado;
        public boolean recomendada;
    }

    public static class ProposalV2Dto {
        public long localId;
        public long jobId;
        public List<ProposalOptionV2Dto> opciones;
    }

    /** Estado de una propuesta según el servidor (en respuestas del push y en el pull). Sin imagen de firma. */
    public static class PropuestaEstadoV2 {
        public Long id, localId, jobId, decidedAt, expiresAt;
        public Integer revision;
        public String estado, contenidoSha256, opcionElegida, firmante, status, reason;
        public Double totalAceptado;
    }

    public static class InvoiceReqV2Dto {
        public long jobId;
        public String clientInvoiceId;
        public Boolean confirmaMenor;    // el técnico revisó y confirma un importe menor que lo aprobado
    }

    public static class ComparacionV2 {
        public Double aprobado, facturado, diferencia;
        public String tipo, variacionEstado;
        public Integer revision;
    }

    public static class VariacionV2 {
        public Long id, jobId, decidedAt;
        public String tipo, estado, itemsSha256, firmante;
        public Double aprobado, facturado, diferencia;
    }

    public static class FacturaV2 {
        public Long id, jobId, emitidaAt;
        public String numero, estado;
        public Double subtotal, impuesto, total, descuento, saldo, pagado;
        public Boolean tienePdf;
        public PropuestaAprobadaV2 propuestaAprobada;
        public ComparacionV2 comparacion;
        public Long variacionId;
    }

    public static class PropuestaAprobadaV2 {
        public Integer revision;
        public String opcion;
        public Double total;
    }

    public static class FacturaResultV2 {
        public Long jobId;
        public String clientInvoiceId, status, reason, codigo;
        public FacturaV2 factura;
        public VariacionV2 variacion;
        public ComparacionV2 comparacion;
    }

    public static class ResultadosPropuestas { public List<PropuestaEstadoV2> results; }

    public static class ResultadosFacturas { public List<FacturaResultV2> results; }

    public static class JobResultV2 {
        public long id;
        public String status;             // ACCEPTED | REJECTED | CONFLICT
        public Integer version;
        public Long clientUpdatedAt;
        public String reason;
        public List<String> conflictFields;
        public JobV2Dto server;           // copia del servidor (resultado del merge)
        public List<Long> conflictIds;    // conflictos pendientes registrados en el servidor
        public Boolean replay;
    }

    public static class AcceptedIds {
        public List<Long> accepted_ids;
    }

    public static class AcceptedEventIds {
        public List<String> accepted_ids;
    }

    public static class PushV2Response {
        public List<JobResultV2> results;
        public WorkItemsAck work_items;
        public AcceptedIds photos;
        public AcceptedEventIds events;
        public ResultadosPropuestas proposals;
        public ResultadosFacturas invoices;
        public ResultadosCampo signatures, parts_used, payments, documents;
        public long timestamp;
    }

    public static class PullV2Response {
        public List<JobV2Dto> jobs;
        public long cursor;
        public long server_time;
    }

    public static class LoginRequest {
        public String username;
        public String password;
        public String deviceId;
    }

    public static class DeviceV2Request {
        public String deviceId;
    }

    public static class DeviceV2Response {
        public Long deviceNumber;
        public Long idBase;
        public Long idLimite;
    }

    public static class LoginResponse {
        public String token;
        public String role;
        public String displayName;
        public String technicianId;
        public String error;
        public Long idBase;              // espacio de ids propio de este dispositivo (sync v2)
        public Long idLimite;
        public Boolean mustChangePassword;   // alta por el administrador: debe cambiar la contraseña al entrar
    }

    /** Cambio de la propia contraseña (POST v2/me/password). */
    public static class CambioClaveRequest {
        public String actual;
        public String nueva;
    }
}
