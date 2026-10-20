package com.lc.technician.sync;

import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

public class ApiClient {

    private static final String PREFS = "lc_sync_prefs";
    private static final String KEY_BASE_URL = "sync_base_url";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:5055/api/";

    private static volatile SyncApi instance;
    private static String currentBaseUrl = null;

    public static SyncApi get(Context ctx) {
        String url = getBaseUrl(ctx);
        if (instance == null || !url.equals(currentBaseUrl)) {
            synchronized (ApiClient.class) {
                if (instance == null || !url.equals(currentBaseUrl)) {
                    instance = buildClient(url);
                    currentBaseUrl = url;
                }
            }
        }
        return instance;
    }

    public static void invalidate() {
        synchronized (ApiClient.class) {
            instance = null;
            currentBaseUrl = null;
        }
    }

    public static String getBaseUrl(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public static void setBaseUrl(Context ctx, String url) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_BASE_URL, url).apply();
        invalidate();
    }

    private static SyncApi buildClient(String baseUrl) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SyncApi.class);
    }
}
