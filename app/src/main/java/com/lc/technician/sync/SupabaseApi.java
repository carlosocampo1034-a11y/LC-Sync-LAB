package com.lc.technician.sync;

import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
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
}
