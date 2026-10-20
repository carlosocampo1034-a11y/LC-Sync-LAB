package com.lc.technician.sync;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface SyncApi {

    @GET("health")
    Call<SyncModels.HealthResponse> health();

    @POST("sync/push")
    Call<SyncModels.SyncPushResponse> push(@Body SyncModels.SyncPushRequest request);

    @GET("sync/pull")
    Call<SyncModels.SyncPullResponse> pull(@Query("since") long since);

    @GET("stats")
    Call<SyncModels.StatsResponse> stats();

    // Fase 44: Notifications polling
    @GET("sync/notifications")
    Call<SyncModels.NotificationResponse> getNotifications(@Query("since") long since);

    // ── v2 (laboratorio) ──
    @POST("v2/auth/login")
    Call<SyncModels.LoginResponse> loginV2(@Body SyncModels.LoginRequest req);

    @POST("v2/sync/push")
    Call<SyncModels.PushV2Response> pushV2(@retrofit2.http.Header("Authorization") String auth,
                                           @Body SyncModels.PushV2Request req);

    /** Detalle de la orden (incluye conflictos; los secretos llegan ocultos si el técnico no puede verlos). */
    @GET("v2/jobs/{id}")
    Call<com.google.gson.JsonObject> detalleV2(@retrofit2.http.Header("Authorization") String auth,
                                              @retrofit2.http.Path("id") long id);

    @POST("v2/me/password")
    Call<com.google.gson.JsonObject> cambiarClave(@retrofit2.http.Header("Authorization") String auth,
                                                  @Body SyncModels.CambioClaveRequest req);

    @POST("v2/sync/device")
    Call<SyncModels.DeviceV2Response> registrarDispositivo(@retrofit2.http.Header("Authorization") String auth,
                                                           @Body SyncModels.DeviceV2Request req);

    @GET("v2/sync/pull")
    Call<SyncModels.PullV2Response> pullV2(@retrofit2.http.Header("Authorization") String auth,
                                           @Query("since") long since, @Query("deviceId") String deviceId);
}
