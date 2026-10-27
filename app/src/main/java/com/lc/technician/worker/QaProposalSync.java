package com.lc.technician.worker;

import android.content.Context;
import android.util.Log;

import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.sync.SupabaseClient;
import com.lc.technician.sync.SyncModels;
import com.lc.technician.util.DeviceIdManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Variante QA: las propuestas del técnico viajan a lc-api y el cliente decide en L&C Customer.
 * Mismo manejo de estado que SyncV2 (syncState, serverEstado…), pero contra Supabase en lugar de Flask.
 * Va después del envío de órdenes: una propuesta solo sale cuando su orden ya está confirmada en el servidor.
 */
public final class QaProposalSync {

    private static final String TAG = "LcQaPropuestas";
    private static final String PREFS = "lc_qa_propuestas";

    private QaProposalSync() {}

    public static void sincronizar(Context c, LcDatabase db, SupabaseClient sb) {
        try {
            enviar(c, db, sb);
            recibir(c, db, sb);
        } catch (Exception e) {
            Log.w(TAG, "propuestas: " + e.getClass().getSimpleName() + " " + e.getMessage());
            db.proposalDao().liberarEnTransito();
        }
    }

    private static void enviar(Context c, LcDatabase db, SupabaseClient sb) throws Exception {
        List<ProposalEntity> lote = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (ProposalEntity p : db.proposalDao().pendientesDeEnvio()) {
            JobEntity j = db.jobDao().getJobById(p.jobId);
            if (j == null || j.synced != 1) continue;     // la orden aún no está confirmada en el servidor
            lote.add(p);
            ids.add(p.id);
        }
        if (ids.isEmpty()) return;
        db.proposalDao().marcarEnTransito(ids);
        String device = DeviceIdManager.getDeviceId(c);
        for (ProposalEntity p : lote) {
            SyncModels.ProposalV2Dto d = SyncV2.aDtoPropuesta(p, db.proposalOptionDao().getForProposal(p.id));
            Map<String, Object> body = new HashMap<>();
            body.put("localId", d.localId);
            body.put("jobId", d.jobId);
            body.put("device", device);
            body.put("opciones", d.opciones);
            retrofit2.Response<okhttp3.ResponseBody> r = sb.api().postProposal(sb.authHeader(), body).execute();
            String txt = r.isSuccessful() ? (r.body() != null ? r.body().string() : "{}")
                    : (r.errorBody() != null ? r.errorBody().string() : "{}");
            int code = r.code();
            if (code == 401 || code == 429 || code >= 500) {
                db.proposalDao().liberarEnTransito();
                Log.w(TAG, "propuesta " + p.id + " HTTP " + code + "; se reintentará");
                return;
            }
            JSONObject o = txt.trim().startsWith("{") ? new JSONObject(txt) : new JSONObject();
            SyncModels.PropuestaEstadoV2 e = estado(o);
            e.localId = p.id;
            if (code == 200 || code == 201) e.status = "ACCEPTED";
            else { e.status = "REJECTED"; if (e.reason == null) e.reason = o.optString("error", "HTTP " + code); }
            SyncV2.confirmarPropuesta(db, e);
            Log.i(TAG, "propuesta " + p.id + " → " + e.status + (e.id != null ? " (servidor " + e.id + ")" : ""));
        }
    }

    private static void recibir(Context c, LcDatabase db, SupabaseClient sb) throws Exception {
        android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long since = Math.max(0, sp.getLong("since", 0) - 60_000L);
        retrofit2.Response<okhttp3.ResponseBody> r = sb.api().proposals(sb.authHeader(), since).execute();
        if (!r.isSuccessful() || r.body() == null) return;
        JSONArray arr = new JSONArray(r.body().string());
        long max = sp.getLong("since", 0);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            SyncV2.aplicarEstadoPropuesta(db, estado(o));
            max = Math.max(max, Math.max(o.optLong("decidedAt", 0), o.optLong("createdAt", 0)));
        }
        sp.edit().putLong("since", max).apply();
    }

    private static SyncModels.PropuestaEstadoV2 estado(JSONObject o) {
        SyncModels.PropuestaEstadoV2 e = new SyncModels.PropuestaEstadoV2();
        e.id = o.has("id") ? o.optLong("id") : null;
        e.localId = o.has("localId") ? o.optLong("localId") : null;
        e.jobId = o.has("jobId") ? o.optLong("jobId") : null;
        e.revision = o.has("revision") ? o.optInt("revision") : null;
        e.estado = o.isNull("estado") ? null : o.optString("estado");
        e.contenidoSha256 = o.isNull("contenidoSha256") ? null : o.optString("contenidoSha256");
        e.opcionElegida = o.isNull("opcionElegida") ? null : o.optString("opcionElegida");
        e.firmante = o.isNull("firmante") ? null : o.optString("firmante");
        e.totalAceptado = o.isNull("totalAceptado") ? null : o.optDouble("totalAceptado");
        e.decidedAt = o.isNull("decidedAt") ? null : o.optLong("decidedAt");
        e.expiresAt = o.isNull("expiresAt") ? null : o.optLong("expiresAt");
        e.reason = o.isNull("reason") ? null : o.optString("reason");
        return e;
    }
}
