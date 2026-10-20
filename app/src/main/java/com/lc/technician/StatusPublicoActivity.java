package com.lc.technician;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class StatusPublicoActivity extends AppCompatActivity {
    public static final String EXTRA_JOB_ID = "pub_job_id";
    public static final String EXTRA_CLIENTE = "pub_cliente";
    public static final String EXTRA_ESTADO = "pub_estado";
    public static final String EXTRA_TECNICO = "pub_tecnico";
    public static final String EXTRA_EQUIPO = "pub_equipo";
    public static final String EXTRA_DIRECCION = "pub_direccion";
    public static final String EXTRA_HORA = "pub_hora";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_publico);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));
        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        Intent in = getIntent();
        String cliente = in.getStringExtra(EXTRA_CLIENTE);
        String estado = in.getStringExtra(EXTRA_ESTADO);
        String tecnico = in.getStringExtra(EXTRA_TECNICO);
        String equipo = in.getStringExtra(EXTRA_EQUIPO);
        String direccion = in.getStringExtra(EXTRA_DIRECCION);
        String hora = in.getStringExtra(EXTRA_HORA);

        if (cliente == null) cliente = "Cliente";
        if (estado == null) estado = "PENDIENTE";
        if (tecnico == null) tecnico = "Sin asignar";

        ((TextView) findViewById(R.id.tvClienteNombre)).setText(cliente);
        ((TextView) findViewById(R.id.tvEstadoBadge)).setText(estado);
        ((TextView) findViewById(R.id.tvTecnicoPublico)).setText("Técnico: " + tecnico);
        ((TextView) findViewById(R.id.tvPublicoEquipo)).setText(equipo != null ? equipo : "—");
        ((TextView) findViewById(R.id.tvPublicoDireccion)).setText(direccion != null ? direccion : "—");
        ((TextView) findViewById(R.id.tvPublicoHora)).setText(hora != null ? ("Hora: " + hora) : "—");

        // Progress based on status
        int progress = progressForEstado(estado);
        ((ProgressBar) findViewById(R.id.progressEstado)).setProgress(progress);
        ((TextView) findViewById(R.id.tvProgresoPct)).setText(progress + "%");
        ((TextView) findViewById(R.id.tvProgresoLabel)).setText(labelForEstado(estado));

        final String finalEstado = estado;
        final String finalCliente = cliente;
        final String finalTecnico = tecnico;
        ((Button) findViewById(R.id.btnCompartirEstado)).setOnClickListener(v -> {
            String texto = "Estado del servicio para " + finalCliente + ":\n"
                    + "Estado: " + finalEstado + "\n"
                    + "Técnico: " + finalTecnico + "\n"
                    + (equipo != null ? "Equipo: " + equipo + "\n" : "")
                    + (direccion != null ? "Dirección: " + direccion + "\n" : "")
                    + "\nL&C Servicios Técnicos";
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, texto);
            startActivity(Intent.createChooser(share, "Compartir estado"));
        });
    }

    private int progressForEstado(String estado) {
        if (estado == null) return 0;
        switch (estado) {
            case "PENDIENTE": return 10;
            case "EN RUTA": return 25;
            case "EN SITIO": return 40;
            case "EN REPARACIÓN": return 65;
            case "ESPERANDO REPUESTO": return 50;
            case "COMPLETADO": return 100;
            default: return 20;
        }
    }

    private String labelForEstado(String estado) {
        if (estado == null) return "";
        switch (estado) {
            case "PENDIENTE": return "Programado";
            case "EN RUTA": return "En camino";
            case "EN SITIO": return "En el sitio";
            case "EN REPARACIÓN": return "Reparando";
            case "ESPERANDO REPUESTO": return "Espera de repuesto";
            case "COMPLETADO": return "Completado ✓";
            default: return estado;
        }
    }
}
