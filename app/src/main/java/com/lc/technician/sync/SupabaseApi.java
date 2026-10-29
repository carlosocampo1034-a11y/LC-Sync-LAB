package com.lc.technician.sync;

import java.util.List;
import java.util.Map;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.Path;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface SupabaseApi {

    // ── Auth ────────────────────────────────────────────────────────────────

    @Headers("Content-Type: application/json")
    @POST("auth/v1/token")
    Call<SupabaseModels.SignInResponse> signIn(
            @Query("grant_type") String grantType,
            @Body SupabaseModels.SignInRequest body);

    @Headers("Content-Type: application/json")
    @POST("auth/v1/token")
    Call<SupabaseModels.SignInResponse> refreshToken(
            @Query("grant_type") String grantType,
            @Body SupabaseModels.RefreshRequest body);

    // ── Jobs REST ───────────────────────────────────────────────────────────

    /**
     * UPSERT: inserta o actualiza filas por conflict en `id`.
     * Prefer: resolution=merge-duplicates → ON CONFLICT (id) DO UPDATE
     * return=minimal → no devuelve body (reduce tráfico).
     */
    @Headers({
        "Content-Type: application/json",
        "Prefer: resolution=merge-duplicates,return=minimal"
    })
    @POST("rest/v1/jobs")
    Call<ResponseBody> upsertJobs(
            @Header("Authorization") String authorization,
            @Body List<SupabaseModels.JobRow> jobs);

    /**
     * Consulta jobs actualizados después de un timestamp dado.
     * updated_at=gt.<epoch_ms> filtra por PostgREST.
     */
    @GET("rest/v1/jobs")
    Call<List<SupabaseModels.JobRow>> getJobsSince(
            @Header("Authorization") String authorization,
            @Query("updated_at") String updatedAtFilter,
            @Query("select")     String selectCols,
            @Query("limit")      int    limit);

    /** «Notifícame cuando esté listo» (orden 68): guarda el interés en una función aún no disponible. */
    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/feature-interest")
    Call<ResponseBody> featureInterest(
            @Header("Authorization") String authorization,
            @Body java.util.Map<String, Object> body);

    /** Pago en efectivo confirmado por el técnico (Orden 65): crea el pago CASH y marca la factura pagada. */
    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/orders/{id}/pay-cash")
    Call<ResponseBody> payCash(
            @Header("Authorization") String authorization,
            @Path("id") long id,
            @Body java.util.Map<String, Object> body);

    /** Perfil propio (la política profiles_read_own solo deja ver la fila del usuario con sesión; solo para el técnico). */
    @GET("rest/v1/profiles")
    Call<ResponseBody> miPerfil(
            @Header("Authorization") String authorization,
            @Query("select") String selectCols,
            @Query("limit")  int    limit);

    // ── lc-api (Edge Function) · variante QA ────────────────────────────────

    /** Cambio de una orden con versión base, dispositivo y marca del cliente (idempotente). */
    @Headers("Content-Type: application/json")
    @PATCH("functions/v1/lc-api/orders/{id}")
    Call<ResponseBody> patchOrder(
            @Header("Authorization") String authorization,
            @Path("id") long id,
            @Body java.util.Map<String, Object> body);

    /** Alta de una orden creada por el técnico (idempotente por request_id). */
    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/orders")
    Call<ResponseBody> createOrder(
            @Header("Authorization") String authorization,
            @Body java.util.Map<String, Object> body);

    /** Propuesta Good/Better/Best del técnico (idempotente por dispositivo + localId + contenido). */
    /** Configuración dinámica (precios y listas editables); since = epoch ms de la última descarga. */
    @GET("functions/v1/lc-api/settings")
    Call<ResponseBody> settings(@Header("Authorization") String authorization, @Query("since") String since);

    /** Foto de evidencia de la orden (lc-api qa-8). clientPhotoId evita duplicados al reintentar. */
    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/orders/{id}/photos")
    Call<ResponseBody> uploadPhoto(@Header("Authorization") String authorization, @Path("id") long id,
                                   @Body Map<String, Object> body);

    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/proposals")
    Call<ResponseBody> postProposal(
            @Header("Authorization") String authorization,
            @Body java.util.Map<String, Object> body);

    /** Estado de las propuestas del técnico (decisión del cliente) cambiadas desde {@code since} (epoch ms). */
    @GET("functions/v1/lc-api/proposals")
    Call<ResponseBody> proposals(@Header("Authorization") String authorization, @Query("since") long since);

    /** Token de FCM de este teléfono asociado al usuario con sesión (push de ofertas y avisos). */
    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/me/push-token")
    Call<ResponseBody> registerPushToken(@Header("Authorization") String authorization,
                                         @Body java.util.Map<String, Object> body);

    /** Ofertas automáticas de trabajo abiertas para este técnico (el servidor también reofrece lo vencido). */
    @GET("functions/v1/lc-api/offers")
    Call<ResponseBody> offers(@Header("Authorization") String authorization);

    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/offers/{id}/accept")
    Call<ResponseBody> acceptOffer(@Header("Authorization") String authorization, @Path("id") long id,
                                   @Body java.util.Map<String, Object> body);

    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/offers/{id}/reject")
    Call<ResponseBody> rejectOffer(@Header("Authorization") String authorization, @Path("id") long id,
                                   @Body java.util.Map<String, Object> body);

    /** Rol del usuario autenticado según profiles (lo decide el servidor, no la app). */
    @GET("functions/v1/lc-api/auth/me")
    Call<ResponseBody> me(@Header("Authorization") String authorization);

    /** Notificaciones del técnico (asignaciones) creadas después de {@code since} (epoch ms). */
    @GET("functions/v1/lc-api/notifications")
    Call<ResponseBody> notifications(@Header("Authorization") String authorization, @Query("since") long since);

    /** Cierre del trabajo (lc-api qa-11): firma del cliente o negativa registrada. El servidor valida diagnóstico y fotos. */
    @GET("functions/v1/lc-api/orders/{id}/closure")
    Call<ResponseBody> closureGet(@Header("Authorization") String authorization, @Path("id") long id);

    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/orders/{id}/closure")
    Call<ResponseBody> closurePost(@Header("Authorization") String authorization, @Path("id") long id,
                                   @Body Map<String, Object> body);

    /** Mensajes de la orden (lc-api qa-8): lista desde el id {@code since} y envío idempotente por clientMsgId. */
    @GET("functions/v1/lc-api/orders/{id}/messages")
    Call<ResponseBody> getMessages(@Header("Authorization") String authorization, @Path("id") long id,
                                   @Query("since") String since);

    @Headers("Content-Type: application/json")
    @POST("functions/v1/lc-api/orders/{id}/messages")
    Call<ResponseBody> postMessage(@Header("Authorization") String authorization, @Path("id") long id,
                                   @Body Map<String, Object> body);
}
