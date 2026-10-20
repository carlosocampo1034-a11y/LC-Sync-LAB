package com.lc.technician.sync;

import android.util.Log;

import com.lc.technician.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Suscripción a cambios de la tabla `jobs` vía Supabase Realtime v2.
 *
 * Protocolo: Phoenix channels sobre WebSocket.
 *   - Heartbeat cada 25 s para mantener la conexión viva.
 *   - Reconexión automática con backoff (5 s → 15 s → 30 s).
 *   - En producción el access_token de Supabase Auth reemplaza al anon key
 *     para que RLS filtre solo las órdenes del usuario autenticado.
 */
public class SupabaseRealtimeClient {

    private static final String TAG       = "SupabaseRealtime";
    private static final String WS_PATH   = "/realtime/v1/websocket";
    private static final String CHANNEL   = "realtime:public:jobs";
    private static final int[]  BACKOFF_S = {5, 15, 30, 60};

    public interface ChangeListener {
        /** Llamado en hilo background cuando una fila de `jobs` cambia en Supabase. */
        void onJobChanged(long jobId, int version, String eventType);
    }

    private final String         wsUrl;
    private final Gson           gson = new Gson();
    private final AtomicInteger  ref  = new AtomicInteger(1);

    private WebSocket        ws;
    private ChangeListener   listener;
    private boolean          running;
    private int              retryCount;
    private Thread           heartbeatThread;

    public SupabaseRealtimeClient() {
        String base = BuildConfig.SUPABASE_URL
                .replace("https://", "wss://")
                .replace("http://",  "ws://");
        wsUrl = base + WS_PATH + "?apikey=" + BuildConfig.SUPABASE_ANON_KEY + "&vsn=1.0.0";
    }

    public void start(String accessToken, ChangeListener cl) {
        this.listener = cl;
        this.running  = true;
        this.retryCount = 0;
        connect(accessToken);
    }

    public void stop() {
        running = false;
        if (ws != null) ws.close(1000, "stop");
        if (heartbeatThread != null) heartbeatThread.interrupt();
    }

    private void connect(String accessToken) {
        OkHttpClient client = new OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder().url(wsUrl).build();

        ws = client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket abierto");
                retryCount = 0;
                subscribe(webSocket, accessToken);
                startHeartbeat(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(TAG, "fallo WebSocket: " + t.getMessage());
                scheduleReconnect(accessToken);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket cerrado: " + reason);
                if (running) scheduleReconnect(accessToken);
            }
        });
    }

    private void subscribe(WebSocket webSocket, String accessToken) {
        String token = (accessToken != null && !accessToken.isEmpty())
                ? accessToken
                : BuildConfig.SUPABASE_ANON_KEY;

        // Mensaje de suscripción Phoenix
        String msg = "{\"event\":\"phx_join\",\"topic\":\"" + CHANNEL + "\","
                + "\"payload\":{"
                + "\"config\":{"
                + "\"broadcast\":{\"self\":false},"
                + "\"presence\":{\"key\":\"\"},"
                + "\"postgres_changes\":[{\"event\":\"*\",\"schema\":\"public\",\"table\":\"jobs\"}]"
                + "},"
                + "\"access_token\":\"" + token + "\""
                + "},"
                + "\"ref\":\"" + ref.getAndIncrement() + "\","
                + "\"join_ref\":\"1\"}";

        webSocket.send(msg);
        Log.i(TAG, "suscripción enviada a " + CHANNEL);
    }

    private void handleMessage(String text) {
        try {
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            String event   = obj.has("event") ? obj.get("event").getAsString() : "";

            if ("postgres_changes".equals(event)) {
                JsonObject payload = obj.getAsJsonObject("payload");
                if (payload == null) return;
                JsonObject data    = payload.getAsJsonObject("data");
                if (data == null) return;

                String type = data.has("type") ? data.get("type").getAsString() : "";
                JsonObject record  = data.getAsJsonObject("record");
                if (record == null) return;

                long jobId  = record.has("id")      ? record.get("id").getAsLong()      : -1;
                int  ver    = record.has("version")  ? record.get("version").getAsInt()  : -1;

                if (jobId > 0 && listener != null) {
                    Log.d(TAG, "cambio Supabase: " + type + " job=" + jobId + " v=" + ver);
                    listener.onJobChanged(jobId, ver, type);
                }
            } else if ("phx_reply".equals(event)) {
                // Confirma suscripción o heartbeat
                JsonObject payload = obj.getAsJsonObject("payload");
                String status = (payload != null && payload.has("status"))
                        ? payload.get("status").getAsString() : "";
                if (!"ok".equals(status)) {
                    Log.w(TAG, "phx_reply no-ok: " + text);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "handleMessage error: " + e.getMessage());
        }
    }

    private void startHeartbeat(WebSocket webSocket) {
        if (heartbeatThread != null) heartbeatThread.interrupt();
        heartbeatThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && running) {
                try {
                    Thread.sleep(25_000);
                    String hb = "{\"event\":\"heartbeat\",\"topic\":\"phoenix\","
                            + "\"payload\":{},\"ref\":\"" + ref.getAndIncrement() + "\"}";
                    webSocket.send(hb);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "sb-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void scheduleReconnect(String accessToken) {
        if (!running) return;
        int delaySec = BACKOFF_S[Math.min(retryCount, BACKOFF_S.length - 1)];
        retryCount++;
        Log.i(TAG, "reconectando en " + delaySec + "s (intento " + retryCount + ")");
        new Thread(() -> {
            try {
                Thread.sleep(delaySec * 1000L);
                if (running) connect(accessToken);
            } catch (InterruptedException ignored) {}
        }, "sb-reconnect").start();
    }
}
