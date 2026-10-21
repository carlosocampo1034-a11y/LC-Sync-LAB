package com.lc.technician.sync;

import com.google.gson.annotations.SerializedName;

/**
 * DTOs para el REST API de Supabase (auth/v1 y rest/v1/jobs).
 *
 * Esquema esperado en PostgreSQL:
 *   jobs(id BIGINT PK, version INT, seq BIGINT, estado TEXT,
 *        technician_code TEXT, updated_at BIGINT, datos JSONB)
 *
 * Si el esquema usa columnas planas en lugar de JSONB, ajustar JobRow
 * añadiendo los campos correspondientes con @SerializedName.
 */
public class SupabaseModels {

    // ── Auth ────────────────────────────────────────────────────────────────

    public static class SignInRequest {
        public String email;
        public String password;
        public SignInRequest(String email, String password) {
            this.email    = email;
            this.password = password;
        }
    }

    public static class SignInResponse {
        @SerializedName("access_token")  public String accessToken;
        @SerializedName("refresh_token") public String refreshToken;
        @SerializedName("expires_in")    public long   expiresIn;
        public UserDto user;
    }

    public static class RefreshRequest {
        @SerializedName("refresh_token") public String refreshToken;
        public RefreshRequest(String refreshToken) { this.refreshToken = refreshToken; }
    }

    public static class UserDto {
        public String id;
        public String email;
    }

    // ── Jobs ────────────────────────────────────────────────────────────────

    /**
     * Fila canónica de la tabla `jobs` en Supabase.
     * Campos mínimos garantizados; `datos` recibe todo lo demás como JSON blob.
     *
     * Convención de nombres: snake_case para coincidir con las columnas PostgreSQL
     * que genera PostgREST automáticamente.
     */
    public static class JobRow {
        public long    id;
        public Integer version;
        public String  estado;
        @SerializedName("estado_dispatch")  public String  estadoDispatch;
        public String  prioridad;
        @SerializedName("technician_code")  public String  technicianCode;
        public String  cliente;
        public String  telefono;
        public String  direccion;
        public Double  lat;
        public Double  lng;
        @SerializedName("ubicacion_fuente") public String  ubicacionFuente;
        public String  hora;
        @SerializedName("programado_para")  public Long    programadoPara;
        public String  descripcion;
        public String  equipo;
        @SerializedName("tipo_propiedad")   public String  tipoPropiedad;
        @SerializedName("cliente_presente") public Integer clientePresente; // 0/1 — SQLite stores booleans as INTEGER
        public String  instrucciones;
        public String  mascota;
        @SerializedName("contacto_respaldo") public String contactoRespaldo;
        @SerializedName("notas_seguridad")   public String notasSeguridad;
        public String  notas;
        @SerializedName("updated_at")        public Long   updatedAt;
    }

    // ── Realtime payload ────────────────────────────────────────────────────

    public static class RealtimeEnvelope {
        public String event;
        public String topic;
        public RealtimePayload payload;
        public String ref;
    }

    public static class RealtimePayload {
        public RealtimeData data;
        public String status;
    }

    public static class RealtimeData {
        public String type;   // INSERT | UPDATE | DELETE
        public String schema;
        public String table;
        public JobRow record;
        @SerializedName("old_record") public JobRow oldRecord;
    }
}
