package com.lc.technician;

import android.Manifest;
import android.util.Log;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.ContactEntity;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.FieldMeasurementEntity;
import com.lc.technician.model.InspectionItemEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.JobEventEntity;
import com.lc.technician.model.JobPartEntity;
import com.lc.technician.model.PartEntity;
import com.lc.technician.data.MembershipCardDao;
import com.lc.technician.model.CoverageTypeEntity;
import com.lc.technician.model.MembershipCardEntity;
import com.lc.technician.model.PartsRequestEntity;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.model.WorkItemEntity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.ProposalOptionEntity;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.model.TimeEventEntity;
import com.lc.technician.model.TimeStats;
import com.lc.technician.ui.adapter.ContactAdapter;
import com.lc.technician.ui.adapter.JobEventAdapter;
import com.lc.technician.ui.adapter.PhotoAdapter;
import com.lc.technician.data.LcDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class JobDetailActivity extends AppCompatActivity {

    private Job job;
    private TextView estadoView;
    private TextView updatedAtView;
    private Button botonPrincipal;
    private Button botonSecundario;
    private Button botonSecundario2;
    private Button btnCompartirReporte;

    private List<PhotoEntity> fotos;
    private LinearLayout layoutFotoSecciones;
    private TextView tvSinFotos;
    private String pendingCategoria;

    private List<ContactEntity> contactos;
    private ContactAdapter contactAdapter;
    private RecyclerView recyclerContactos;
    private TextView tvSinContactos;

    private List<JobEventEntity> eventos;
    private JobEventAdapter historialAdapter;
    private RecyclerView recyclerHistorial;
    private TextView tvSinHistorial;

    private TextView btnInspeccion;
    private TextView tvResumenInspeccion;
    // Phase 8: findings summary
    private LinearLayout cardHallazgosSummary;
    private TextView tvFindingCritical, tvFindingHigh, tvFindingMedium, tvFindingLow;
    private TextView btnPropuesta;
    private TextView tvResumenPropuesta;
    private android.widget.FrameLayout btnFirmaCliente; // Fase 49
    private static final int REQUEST_CUSTOMER_SIGN = 5001;
    private TextView btnFirmaTecnico; // Fase 55
    private static final int REQUEST_TECHNICIAN_SIGN = 5002;
    private TextView btnAvisarCliente;

    private LinearLayout layoutServiciosAnteriores;
    private TextView tvSinServiciosAnteriores;
    private LinearLayout layoutEquipmentHistory;
    private TextView tvEquipmentHistoryHeader;
    private TextView tvSinEquipmentHistory;

    private LinearLayout layoutRepuestos;
    private TextView tvSinRepuestos;
    private LinearLayout layoutSolicitudes;
    private TextView tvSinSolicitudes;

    // FASE 15: Coverage
    private TextView tvCoberturaActual;
    private TextView tvCoberturaDetalle;
    private TextView tvCoberturaAuth;

    private LinearLayout layoutWorkItems;
    private TextView tvSinWorkItems;
    private TextView tvTotalWorkItems;
    private TextView tvTecnicoAsignado;
    private Button btnFactura;
    private Button btnCompartirCliente;
    private Button btnRecordatorio;
    private Button btnVerComoCliente;


    private File tempFotoFile;

    private static final SimpleDateFormat FMT_FECHA =
            new SimpleDateFormat("d MMM, h:mm a", Locale.forLanguageTag("es"));
    private static final SimpleDateFormat FMT_HORA =
            new SimpleDateFormat("h:mm a", Locale.forLanguageTag("es"));


    /** Resultado de la pantalla de cierre: solo vuelve con «COMPLETE» o «PART_HOLD» cuando hay diagnóstico, foto y firma/negativa. */
    private final androidx.activity.result.ActivityResultLauncher<Intent> cierreLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null || job == null) return;
                String accion = res.getData().getStringExtra(CierreActivity.RESULT_ACCION);
                if ("COMPLETE".equals(accion)) {
                    aplicarEstado(Job.STATUS_DONE, "Trabajo completado");
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Trabajo completado")
                            .setMessage("La orden Nº " + job.id + " quedó cerrada con diagnóstico, foto y firma.\n\n"
                                    + "La factura se genera automáticamente y el reporte del servicio queda registrado en la orden.")
                            .setPositiveButton("Entendido", null).show();
                } else if ("PART_HOLD".equals(accion)) {
                    aplicarEstado(Job.STATUS_PART_HOLD, "Part hold — esperando repuesto");
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Repuesto solicitado")
                            .setMessage("La orden Nº " + job.id + " quedó en espera de repuesto, con el diagnóstico, la foto y la firma registrados.")
                            .setPositiveButton("Entendido", null).show();
                }
            });
    private final ActivityResultLauncher<Uri> launcherCamara =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && tempFotoFile != null && tempFotoFile.exists()) {
                    agregarFoto(tempFotoFile.getAbsolutePath());
                }
            });

    private final ActivityResultLauncher<String> launcherGaleria =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) guardarFotoDesdeGaleria(uri);
            });

    private final ActivityResultLauncher<String> launcherPermisoCamara =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) abrirCamara();
                else Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show();
            });

    private ActivityResultLauncher<Intent> membershipScanLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.lc.technician.sync.Listas.refrescar(this, false, null);   // menús y listas editables desde Control
        setContentView(R.layout.activity_job_detail);
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado (bottomBar ya se gestiona abajo)
        InsetsHelper.padTop(findViewById(R.id.header));

        View bottomBar = findViewById(R.id.bottomBar);
        final int origPaddingBottom = bottomBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    origPaddingBottom + navBottom);
            return insets;
        });

        long id = getIntent().getLongExtra("job_id", -1);
        job = JobRepository.get().getById(id);
        if (job == null) { finish(); return; }

        estadoView      = findViewById(R.id.detalleEstado);
        layoutTiemposRegistrados = findViewById(R.id.layoutTiemposRegistrados);
        tvTiemposRegistrados = findViewById(R.id.tvTiemposRegistrados);
        updatedAtView   = findViewById(R.id.detalleUpdatedAt);
        botonPrincipal       = findViewById(R.id.btnPrincipal);
        botonSecundario      = findViewById(R.id.btnSecundario);
        botonSecundario2     = findViewById(R.id.btnSecundario2);
        btnCompartirReporte  = findViewById(R.id.btnCompartirReporte);
        btnFactura           = findViewById(R.id.btnFactura);
        btnCompartirCliente  = findViewById(R.id.btnCompartirCliente);
        btnRecordatorio      = findViewById(R.id.btnRecordatorio);
        btnVerComoCliente    = findViewById(R.id.btnVerComoCliente);
        tvSinFotos           = findViewById(R.id.tvSinFotos);
        layoutFotoSecciones  = findViewById(R.id.layoutFotoSecciones);
        recyclerContactos = findViewById(R.id.recyclerContactos);
        tvSinContactos    = findViewById(R.id.tvSinContactos);
        recyclerHistorial = findViewById(R.id.recyclerHistorial);
        tvSinHistorial    = findViewById(R.id.tvSinHistorial);
        btnInspeccion       = findViewById(R.id.btnInspeccion);
        tvResumenInspeccion = findViewById(R.id.tvResumenInspeccion);
        // Phase 8: findings summary views
        cardHallazgosSummary = findViewById(R.id.cardHallazgosSummary);
        tvFindingCritical = findViewById(R.id.tvFindingCritical);
        tvFindingHigh     = findViewById(R.id.tvFindingHigh);
        tvFindingMedium   = findViewById(R.id.tvFindingMedium);
        tvFindingLow      = findViewById(R.id.tvFindingLow);
        btnPropuesta        = findViewById(R.id.btnPropuesta);
        tvResumenPropuesta  = findViewById(R.id.tvResumenPropuesta);
        btnFirmaCliente     = findViewById(R.id.btnFirmaCliente); // Fase 49
        btnFirmaTecnico     = findViewById(R.id.btnFirmaTecnico); // Fase 55
        btnAvisarCliente    = findViewById(R.id.btnAvisarCliente);
        layoutRepuestos  = findViewById(R.id.layoutRepuestos);
        tvSinRepuestos   = findViewById(R.id.tvSinRepuestos);
        layoutSolicitudes = findViewById(R.id.layoutSolicitudes);
        tvSinSolicitudes  = findViewById(R.id.tvSinSolicitudes);
        tvCoberturaActual = findViewById(R.id.tvCoberturaActual);
        tvCoberturaDetalle = findViewById(R.id.tvCoberturaDetalle);
        tvCoberturaAuth   = findViewById(R.id.tvCoberturaAuth);
        EditText notas  = findViewById(R.id.detalleNotas);

        ((TextView) findViewById(R.id.detalleCliente)).setText(job.cliente);

        TextView tvDireccion = findViewById(R.id.detalleDireccion);
        tvDireccion.setText(job.direccion);
        if (job.direccion != null && !job.direccion.isEmpty()) {
            tvDireccion.setClickable(true);
            tvDireccion.setFocusable(true);
            tvDireccion.setTextColor(0xFF171717);
            tvDireccion.setPaintFlags(tvDireccion.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            tvDireccion.setOnClickListener(v -> abrirMapa(job.direccion));
        }

        TextView tvTelefono = findViewById(R.id.detalleTelefono);
        tvTelefono.setText(job.telefono);
        if (job.telefono != null && !job.telefono.isEmpty()) {
            tvTelefono.setClickable(true);
            tvTelefono.setFocusable(true);
            tvTelefono.setTextColor(0xFF171717);
            tvTelefono.setPaintFlags(tvTelefono.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            tvTelefono.setOnClickListener(v -> {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + job.telefono));
                startActivity(dialIntent);
            });
        }

        ((TextView) findViewById(R.id.detalleHora)).setText(job.horaConFecha());
        ((TextView) findViewById(R.id.detalleDescripcion)).setText(job.descripcion);
        TextView tvEquipo = findViewById(R.id.detalleEquipo);
        tvEquipo.setText(job.equipo);
        // 27C: Tap equipo → EquipmentDetailActivity
        if (job.equipmentId != null && job.equipmentId > 0) {
            tvEquipo.setTextColor(0xFF171717);
            final long eqId = job.equipmentId;
            tvEquipo.setOnClickListener(v -> {
                Intent ei = new Intent(this, EquipmentDetailActivity.class);
                ei.putExtra(EquipmentDetailActivity.EXTRA_EQUIPMENT_ID, eqId);
                startActivity(ei);
            });
        }

        TextView clientePresente = findViewById(R.id.accesoClientePresente);
        TextView tipoAcceso = findViewById(R.id.accesoTipoAcceso);
        TextView instr = findViewById(R.id.accesoInstrucciones);
        TextView mascota = findViewById(R.id.accesoMascota);
        TextView contacto = findViewById(R.id.accesoContacto);
        TextView notasSeg = findViewById(R.id.accesoNotasSeguridad);

        if (job.clientePresente) {
            clientePresente.setText("Cliente estará en casa");
            clientePresente.setTextColor(0xFF171717);
        } else {
            clientePresente.setText("Cliente NO estará · Entrar con autorización");
            clientePresente.setTextColor(0xFFE91605);
        }

        // Fix P0: never show "Entrada: null" — hide row when tipoAcceso is absent
        if (job.tipoAcceso != null && !job.tipoAcceso.isEmpty()) {
            StringBuilder acceso = new StringBuilder();
            acceso.append("Entrada: ").append(job.tipoAcceso);
            if (job.codigoAcceso != null && !job.codigoAcceso.isEmpty()) {
                acceso.append("   →   ").append(job.codigoAcceso);
            }
            tipoAcceso.setText(acceso.toString());
            tipoAcceso.setVisibility(View.VISIBLE);
        } else {
            tipoAcceso.setVisibility(View.GONE);
        }

        StringBuilder det = new StringBuilder();
        if (job.ubicacionLlave != null && !job.ubicacionLlave.isEmpty()) det.append(job.ubicacionLlave).append("\n");
        if (job.instrucciones != null && !job.instrucciones.isEmpty()) det.append(job.instrucciones);
        if (det.length() > 0) { instr.setText(det.toString().trim()); instr.setVisibility(View.VISIBLE); }
        else instr.setVisibility(View.GONE);

        if (job.mascota != null && !job.mascota.isEmpty()) {
            mascota.setText("Mascota: " + job.mascota);
            mascota.setVisibility(View.VISIBLE);
        } else mascota.setVisibility(View.GONE);

        if (job.contactoRespaldo != null && !job.contactoRespaldo.isEmpty()) {
            contacto.setText("Contacto: " + job.contactoRespaldo);
            contacto.setVisibility(View.VISIBLE);
        } else contacto.setVisibility(View.GONE);

        if (job.notasSeguridad != null && !job.notasSeguridad.isEmpty()) {
            notasSeg.setText(job.notasSeguridad);
            notasSeg.setVisibility(View.VISIBLE);
        } else notasSeg.setVisibility(View.GONE);

        notas.setText(notasParaEditor(job.notas));
        notasVistas = job.notas;
        versionVista = job.serverVersion;
        tvTecnicoAsignado = findViewById(R.id.tvTecnicoAsignado);
        // Prefer technicianId field; fall back to legacy TECNICO: in notas
        String tecnicoActual = (job.technicianId != null && !job.technicianId.isEmpty())
                ? job.technicianId : extraerTecnico(job.notas);
        actualizarTecnicoUI(tecnicoActual);
        tvTecnicoAsignado.setOnClickListener(v -> mostrarDialogoTecnico());
        // 27D: chip de prioridad
        TextView tvPrioridad = findViewById(R.id.tvPrioridad);
        if (tvPrioridad != null && job.prioridad != null) {
            switch (job.prioridad) {
                case "URGENTE": tvPrioridad.setText("● URGENTE"); tvPrioridad.setTextColor(0xFFE91605); tvPrioridad.setVisibility(android.view.View.VISIBLE); break;
                case "ALTA":    tvPrioridad.setText("▲ ALTA");    tvPrioridad.setTextColor(0xFF171717); tvPrioridad.setVisibility(android.view.View.VISIBLE); break;
                default:        tvPrioridad.setVisibility(android.view.View.GONE); break;
            }
        }
        pintarEstado();
        mostrarUpdatedAt();
        actualizarBoton();
        cargarFotos();
        cargarContactos();
        cargarHistorial();
        cargarInfoCliente();
        cargarServiciosAnteriores();
        cargarEquipmentHistory();
        cargarResumenInspeccion();
        actualizarResumenPropuesta();
        layoutWorkItems  = findViewById(R.id.layoutWorkItems);
        tvSinWorkItems   = findViewById(R.id.tvSinWorkItems);
        tvTotalWorkItems = findViewById(R.id.tvTotalWorkItems);
        TextView btnAgregarWorkItem = findViewById(R.id.btnAgregarWorkItem);
        if (btnAgregarWorkItem != null)
            btnAgregarWorkItem.setOnClickListener(v -> mostrarDialogoAgregarWorkItem());
        cargarWorkItems();

        cargarRepuestosUsados();
        cargarSolicitudesRepuesto();
        cargarCobertura();
        cargarTiempos();
        findViewById(R.id.btnAsignarCobertura).setOnClickListener(v -> mostrarDialogoAsignarCobertura());

        membershipScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String cardNumber = result.getData()
                            .getStringExtra(MembershipScanActivity.EXTRA_CARD_NUMBER);
                    if (cardNumber != null) procesarTarjetaEscaneada(cardNumber);
                }
            });

        Button btnEscanearMembresia = findViewById(R.id.btnEscanearMembresia);
        if (btnEscanearMembresia != null) {
            btnEscanearMembresia.setOnClickListener(v -> {
                Intent i = new Intent(this, MembershipScanActivity.class);
                membershipScanLauncher.launch(i);
            });
        }

        // ── Botón principal ───────────────────────────────────────────────────
        botonPrincipal.setOnClickListener(v -> accionPrincipal());

        // ── «Más acciones»: acciones secundarias de cada estado (una sola acción principal por pantalla) ────
        botonSecundario.setOnClickListener(v -> mostrarCambiarEstado());
        botonSecundario2.setVisibility(View.GONE);

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());
        findViewById(R.id.btnAgregarFoto).setOnClickListener(v -> tomarFotoDirecto());
        // pulsación larga: el menú de antes (categoría y galería) para quien la necesite
        findViewById(R.id.btnAgregarFoto).setOnLongClickListener(v -> { mostrarOpcionesFoto(); return true; });
        findViewById(R.id.btnAgregarContacto).setOnClickListener(v -> mostrarDialogoNuevoContacto());
        btnAvisarCliente.setOnClickListener(v -> mostrarOpcionesAvisarCliente());
        findViewById(R.id.btnAgregarRepuesto).setOnClickListener(v -> mostrarDialogoAgregarRepuesto());
        findViewById(R.id.btnSolicitarRepuesto).setOnClickListener(v -> mostrarDialogoSolicitudRepuesto());
        btnCompartirReporte.setOnClickListener(v -> compartirReporte());
        btnFactura.setOnClickListener(v -> {
            Intent facturaIntent = new Intent(this, InvoiceActivity.class);
            facturaIntent.putExtra(InvoiceActivity.EXTRA_JOB_ID, job.id);
            startActivity(facturaIntent);
        });
        btnCompartirCliente.setOnClickListener(v -> compartirConCliente());
        btnRecordatorio.setOnClickListener(v -> mostrarDialogoRecordatorio());
        btnVerComoCliente.setOnClickListener(v -> {
            Intent si = new Intent(this, StatusPublicoActivity.class);
            si.putExtra(StatusPublicoActivity.EXTRA_CLIENTE,   job.cliente);
            si.putExtra(StatusPublicoActivity.EXTRA_ESTADO,    job.estado);
            si.putExtra(StatusPublicoActivity.EXTRA_TECNICO,   extraerTecnico(job.notas));
            si.putExtra(StatusPublicoActivity.EXTRA_EQUIPO,    job.equipo);
            si.putExtra(StatusPublicoActivity.EXTRA_DIRECCION, job.direccion);
            si.putExtra(StatusPublicoActivity.EXTRA_HORA,      job.hora);
            startActivity(si);
        });
        btnInspeccion.setOnClickListener(v -> {
            Intent i = new Intent(this, InspectionActivity.class);
            i.putExtra(InspectionActivity.EXTRA_JOB_ID, job.id);
            startActivity(i);
        });
        btnPropuesta.setOnClickListener(v -> {
            Intent intent = new Intent(this, ProposalActivity.class);
            intent.putExtra(ProposalActivity.EXTRA_JOB_ID, job.id);
            startActivity(intent);
        });

        // Fase 49: Customer signature button
        if (btnFirmaCliente != null) {
            btnFirmaCliente.setOnClickListener(v -> {
                // La firma oficial es la de «Cerrar trabajo» (candado en el servidor). CustomerSignatureActivity queda en el código, sin uso visible.
                abrirCierre();
            });
        }

        // Fase 55: Technician signature button
        if (btnFirmaTecnico != null) {
            btnFirmaTecnico.setOnClickListener(v -> {
                Intent i = new Intent(this, TechnicianSignatureActivity.class);
                i.putExtra(TechnicianSignatureActivity.EXTRA_JOB_ID, job.id);
                startActivityForResult(i, REQUEST_TECHNICIAN_SIGN);
            });
        }

        TextView btnVerAuditoriaJob = findViewById(R.id.btnVerAuditoriaJob);
        if (btnVerAuditoriaJob != null) {
            btnVerAuditoriaJob.setOnClickListener(v -> {
                Intent i = new Intent(this, AuditLogActivity.class);
                i.putExtra(AuditLogActivity.EXTRA_JOB_ID, job.id);
                startActivity(i);
            });
        }
    }

    private TextView estadoPropuestaServidor;

    private void abrirPropuesta() {
        Intent i = new Intent(this, ProposalActivity.class);
        i.putExtra(ProposalActivity.EXTRA_JOB_ID, job.id);
        startActivity(i);
    }

    /** Estado de la propuesta en el servidor, visible sin salir del trabajo (tocar abre la propuesta). */
    private void mostrarEstadoPropuestaServidor() {
        if (!com.lc.technician.sync.LabSession.conServidor(this) || estadoView == null) return;
        JobRepository.get().getProposalForJobAsync(job.id, p -> {
            boolean hay = p != null && (p.serverEstado != null || p.syncState != 1);
            if (estadoPropuestaServidor == null) {
                if (!hay || !(estadoView.getParent() instanceof android.view.ViewGroup)) return;
                android.view.ViewGroup padre = (android.view.ViewGroup) estadoView.getParent();
                estadoPropuestaServidor = new TextView(this);
                estadoPropuestaServidor.setTextSize(14);
                int pad = (int) (6 * getResources().getDisplayMetrics().density);
                estadoPropuestaServidor.setPadding(0, pad, 0, pad);
                estadoPropuestaServidor.setOnClickListener(v -> abrirPropuesta());
                padre.addView(estadoPropuestaServidor, padre.indexOfChild(estadoView) + 1);
            }
            estadoPropuestaServidor.setVisibility(hay ? View.VISIBLE : View.GONE);
            if (hay) {
                estadoPropuestaServidor.setText("Propuesta: " + ProposalActivity.textoEstado(p));
                estadoPropuestaServidor.setTextColor("APROBADA".equals(p.serverEstado) && p.syncState == 1
                        ? 0xFF171717 : 0xFF62666A);
            }
        });
    }

    private TextView avisoConflicto;
    /** Notas y versión del servidor que mostró el editor (para no pisar lo recibido mientras estaba abierto). */
    private String notasVistas;
    private Integer versionVista;

    /**
     * Aviso de conflicto de sincronización pendiente (lo resuelve el centro de mando). Solo muestra el número y,
     * al tocarlo, lo que este técnico envió según la auditoría local (los códigos de acceso ya van ocultos).
     */
    private void mostrarAvisoConflicto() {
        int n = com.lc.technician.sync.LabSession.conflictos(this, job.id);
        if (avisoConflicto == null) {
            if (n == 0 || estadoView == null || !(estadoView.getParent() instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup padre = (android.view.ViewGroup) estadoView.getParent();
            avisoConflicto = new TextView(this);
            avisoConflicto.setId(View.generateViewId());
            avisoConflicto.setTextColor(0xFFE91605);
            avisoConflicto.setTextSize(14);
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            avisoConflicto.setPadding(0, pad, 0, pad);
            avisoConflicto.setOnClickListener(v -> verConflictos());
            padre.addView(avisoConflicto, padre.indexOfChild(estadoView) + 1);
        }
        avisoConflicto.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        avisoConflicto.setText("⚠ Conflicto de sincronización pendiente (" + n + "). El centro de mando decidirá; "
                + "tu versión quedó guardada. Toca para ver.");
    }

    private void verConflictos() {
        final long id = job.id;
        new Thread(() -> {
            String texto = conflictosDelServidor(id);
            if (texto == null) texto = "Sin conexión con el centro de mando: no se puede confirmar cuáles siguen "
                    + "pendientes.\n\nHistorial local:\n\n" + conflictosLocales(id);
            final String t = texto;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Conflicto de sincronización")
                        .setMessage(t)
                        .setPositiveButton("Entendido", null)
                        .show();
            });
        }).start();
    }

    /** Pendientes primero; los resueltos quedan como historial. null si no hay conexión o sesión. */
    private String conflictosDelServidor(long id) {
        String auth = com.lc.technician.sync.LabSession.authHeader(this);
        if (auth == null) return null;
        try {
            retrofit2.Response<com.google.gson.JsonObject> r =
                    com.lc.technician.sync.ApiClient.get(this).detalleV2(auth, id).execute();
            if (!r.isSuccessful() || r.body() == null) return null;
            com.google.gson.JsonArray cs = r.body().has("conflictos") && r.body().get("conflictos").isJsonArray()
                    ? r.body().getAsJsonArray("conflictos") : new com.google.gson.JsonArray();
            StringBuilder pend = new StringBuilder(), hist = new StringBuilder();
            int nPend = 0;
            for (com.google.gson.JsonElement el : cs) {
                com.google.gson.JsonObject c = el.getAsJsonObject();
                boolean pendiente = "PENDIENTE".equals(str(c, "estado"));
                StringBuilder sb = pendiente ? pend : hist;
                sb.append("n.º ").append(str(c, "id")).append(" · ").append(str(c, "campo"));
                if (!pendiente) sb.append(" · ").append(str(c, "resolucion"));
                sb.append("\nCentro de mando: ").append(str(c, "valorServidor"))
                  .append("\nTu versión: ").append(str(c, "valorTecnico"));
                if (!pendiente) sb.append("\nResultado: ").append(str(c, "valorFinal"));
                sb.append("\n\n");
                if (pendiente) nPend++;
            }
            com.lc.technician.sync.LabSession.setConflictos(this, id, nPend);
            runOnUiThread(this::mostrarAvisoConflicto);
            return (nPend > 0 ? "PENDIENTES (" + nPend + ")\n\n" + pend : "No hay conflictos pendientes.\n\n")
                    + (hist.length() > 0 ? "RESUELTOS (historial)\n\n" + hist : "").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "—";
    }

    private String conflictosLocales(long id) {
        StringBuilder sb = new StringBuilder();
        for (com.lc.technician.model.AuditLogEntity a :
                com.lc.technician.data.LcDatabase.getInstance(this).auditLogDao().getByJob(id)) {
            if (!"SYNC_CONFLICTO".equals(a.action)) continue;
            sb.append(FMT_FECHA.format(new Date(a.timestamp))).append(" · ")
              .append(a.field != null ? a.field : "").append("\n").append(a.newValue != null ? a.newValue : "")
              .append("\n\n");
            if (sb.length() > 2000) break;
        }
        return sb.length() > 0 ? sb.toString().trim() : "Sin detalle local.";
    }

    @Override
    protected void onPause() {
        super.onPause();
        guardarNotasAhora();
    }

    private void guardarNotasAhora() {
        if (job != null) {
            EditText etNotas = findViewById(R.id.detalleNotas);
            if (etNotas != null) {
                // Reconstruct full notas = TECNICO: prefix (if any) + editor content
                String editorText = etNotas.getText().toString();
                String tecnico = extraerTecnico(job.notas);
                String fullNotas = buildNotas(tecnico, editorText);
                JobRepository.get().persistNotas(job.id, fullNotas, notasVistas, versionVista);
                notasVistas = fullNotas;   // lo ya guardado cuenta como visto; la base sigue siendo la que vio
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (job != null && tvResumenInspeccion != null) {
            cargarResumenInspeccion();
        }
        if (job != null && cardHallazgosSummary != null) {
            cargarResumenHallazgos();
        }
        mostrarAvisoConflicto();
        mostrarEstadoPropuestaServidor();
        actualizarResumenPropuesta();
        cargarTiempos();
        cargarHistorial();
        cargarWorkItems();
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    /** Respaldo = valor inicial de settings job_actions.list. */
    private static final String[][] ACCIONES_RESPALDO = {
            {"GO_ENROUTE", "Voy en camino"}, {"ARRIVED", "Llegué"}, {"ASK_APPROVAL", "Pedir aprobación al cliente"},
            {"START_DIRECT", "Iniciar reparación directo"}, {"CLIENT_APPROVED", "Cliente aprobó — Iniciar reparación"},
            {"COMPLETE", "Terminé (Completar)"}, {"PART_HOLD", "Falta repuesto (Part Hold)"}, {"PAUSE", "Pausar trabajo"},
            {"RESUME", "Reanudar reparación"}, {"CLIENT_CANCELLED", "Cliente canceló"}, {"NO_ACCESS", "Cliente no está en casa"},
            {"NEED_ORDER", "Necesito una orden"}};

    private void manejarOpcionMenu(String estado, String opcion) {
        switch (opcion) {
            case "GO_ENROUTE":
                aplicarEstado(Job.STATUS_EN_ROUTE, "En ruta hacia el cliente"); break;
            case "ARRIVED":
                aplicarEstado(Job.STATUS_ARRIVED, "Llegada registrada"); break;
            case "ASK_APPROVAL":
                aplicarEstado(Job.STATUS_AWAITING_APPROVAL, "Esperando aprobación del cliente");
                if (com.lc.technician.sync.LabSession.conServidor(this)) abrirPropuesta();   // se prepara y envía allí
                break;
            case "START_DIRECT":
                aplicarEstado(Job.STATUS_EN_REPARACION, "Reparación iniciada directamente"); break;
            case "CLIENT_APPROVED":
                if (com.lc.technician.sync.LabSession.conServidor(this)) {
                    // sin evidencia válida (firma del cliente confirmada por el servidor) no se marca aprobado
                    JobRepository.get().getProposalForJobAsync(job.id, p -> {
                        boolean ok = p != null && p.syncState == 1 && "APROBADA".equals(p.serverEstado);
                        if (ok) {
                            aplicarEstado(Job.STATUS_EN_REPARACION, "Cliente aprobó (firma " + p.firmanteServidor
                                    + ", propuesta rev " + p.serverRevision + ")");
                        } else {
                            new AlertDialog.Builder(this)
                                    .setTitle("Sin aprobación del cliente")
                                    .setMessage("Todavía no hay una aprobación firmada por el cliente y confirmada por "
                                            + "el servidor.\n\nEstado: " + (p == null ? "sin propuesta"
                                            : ProposalActivity.textoEstado(p)))
                                    .setPositiveButton("Ver propuesta", (d, w) -> abrirPropuesta())
                                    .setNegativeButton("Cerrar", null)
                                    .show();
                        }
                    });
                    break;
                }
                aplicarEstado(Job.STATUS_EN_REPARACION, "Reparación iniciada"); break;
            case "COMPLETE":
            case "PART_HOLD":
                abrirCierre(); break;   // candado: diagnóstico 50+ caracteres, 1 foto y firma o negativa
            case "PAUSE":
                aplicarEstado(Job.STATUS_ON_HOLD, "Trabajo pausado"); break;
            case "RESUME":
                aplicarEstado(Job.STATUS_EN_REPARACION, "Reparación reanudada"); break;
            case "CLIENT_CANCELLED":
                confirmarCancelacion(); break;
            case "NO_ACCESS":
                aplicarEstado(Job.STATUS_NOT_HOME, "Cliente no está · se avisó a Control"); break;
            case "NEED_ORDER":
                abrirNuevaOrden(); break;
        }
    }

    /** «Cancelar»: confirma y pide el motivo (opcional). El servidor libera la oferta abierta y avisa a Control. */
    private void confirmarCancelacion() {
        final EditText motivo = new EditText(this);
        motivo.setHint("Motivo de la cancelación (opcional)");
        motivo.setMinLines(2);
        motivo.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("¿Estás seguro?")
                .setMessage("Vas a cancelar el trabajo #" + job.id + " de " + job.cliente + ".")
                .setView(motivo)
                .setPositiveButton("Sí, cancelar", (d, w) -> {
                    String m = motivo.getText().toString().trim().replace('\n', ' ');
                    EditText etNotas = findViewById(R.id.detalleNotas);
                    if (etNotas != null) {
                        String actual = etNotas.getText().toString();
                        etNotas.setText(actual + (actual.isEmpty() ? "" : "\n") + "[CANCELACIÓN] " + (m.isEmpty() ? "Sin motivo indicado" : m));
                    }
                    guardarNotasAhora();
                    aplicarEstado(Job.STATUS_CANCELLED, "Trabajo cancelado");
                })
                .setNegativeButton("No", null)
                .show();
    }

    /** «Nueva Orden»: abre el alta con cliente, dirección y propiedad de esta orden. No toca la orden actual. */
    private void abrirNuevaOrden() {
        Intent i = new Intent(this, RequestServiceActivity.class);
        i.putExtra("prefill_cliente", job.cliente);
        i.putExtra("prefill_direccion", job.direccion);
        i.putExtra("prefill_telefono", job.telefono);
        if (job.customerId != null) i.putExtra("prefill_customer_id", job.customerId.longValue());
        if (job.propertyId != null) i.putExtra("prefill_property_id", job.propertyId.longValue());
        startActivity(i);
    }

    /** «Pagado / Completado»: cómo se cobró. Tarjeta = pago de PRUEBA (sin cobro real); efectivo = el técnico confirma que recibió el dinero. */
    private void confirmarPagado() {
        new AlertDialog.Builder(this)
                .setTitle("¿Cómo se cobró el trabajo?")
                .setItems(new String[]{"Cobrado con tarjeta (prueba)", "Cobrado en efectivo"}, (d, which) -> {
                    if (which == 0) confirmarPagoTarjeta(); else confirmarPagoEfectivo();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmarPagoTarjeta() {
        new AlertDialog.Builder(this)
                .setTitle("Registrar pago de prueba")
                .setMessage("Se registra un pago de prueba (sin cobro real), la factura pasa a pagada y la orden a PAGADO. "
                        + "Requiere que la orden tenga factura emitida.")
                .setPositiveButton("Registrar pago", (d, w) -> aplicarEstado(Job.STATUS_PAID, "Pago de prueba enviado · el servidor lo confirma al sincronizar"))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Efectivo: el servidor crea el pago CASH, marca la factura PAGADA y deja el aviso al cliente. La orden sigue COMPLETADA. */
    private void confirmarPagoEfectivo() {
        if (!com.lc.technician.BuildConfig.CLOUD_LOGIN) {
            Toast.makeText(this, "El pago en efectivo requiere sesión con el servidor", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Cobrado en efectivo")
                .setMessage("Confirma que recibiste el pago en efectivo. La factura se marca como pagada (efectivo) y el cliente lo verá en su historial.")
                .setPositiveButton("Sí, lo recibí", (d, w) -> java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    String msg;
                    boolean ok = false;
                    try {
                        com.lc.technician.sync.SupabaseClient sb = com.lc.technician.sync.SupabaseClient.get(this);
                        java.util.Map<String, Object> cuerpo = new java.util.HashMap<>();
                        cuerpo.put("idempotencyKey", "cash-" + job.id + "-" + java.util.UUID.randomUUID());
                        retrofit2.Response<okhttp3.ResponseBody> r = sb.api().payCash(sb.authHeader(), job.id, cuerpo).execute();
                        String txt = r.isSuccessful() ? (r.body() != null ? r.body().string() : "{}") : (r.errorBody() != null ? r.errorBody().string() : "{}");
                        org.json.JSONObject o = txt.trim().startsWith("{") ? new org.json.JSONObject(txt) : new org.json.JSONObject();
                        if (r.isSuccessful()) {
                            ok = true;
                            msg = (o.optBoolean("replay") ? "Esta orden ya estaba pagada" : "Pago en efectivo confirmado")
                                    + (o.has("numero") ? " · " + o.optString("numero") : "")
                                    + (o.has("monto") ? String.format(java.util.Locale.US, " · $%.2f", o.optDouble("monto")) : "");
                        } else {
                            msg = o.optString("mensaje", o.optString("error", "No se pudo registrar el pago (" + r.code() + ")"));
                        }
                    } catch (Exception e) {
                        msg = "Sin conexión: el pago en efectivo no se registró; vuelve a intentarlo con red";
                    }
                    final String fm = msg;
                    final boolean fok = ok;
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        Toast.makeText(this, fm, Toast.LENGTH_LONG).show();
                        if (fok) { JobRepository.get().agregarEvento(job.id, "PAGO", "Pago en efectivo confirmado"); cargarHistorial(); }
                    });
                }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void registrarNota(String descripcion) {
        JobRepository.get().agregarEvento(job.id, "NOTA", descripcion);
        Toast.makeText(this, descripcion, Toast.LENGTH_SHORT).show();
        cargarHistorial();
    }

    private void aplicarEstado(String next, String toast) {
        String prev = job.estado != null ? job.estado : "";
        job.estado = next;
        JobRepository.get().persistEstado(job.id, next, prev);
        // La hoja de estados también deja la hora (antes solo la dejaban los botones de «Registro de tiempos»):
        // «En camino» registra la salida y «Llegué» la llegada, una sola vez por orden.
        if (Job.STATUS_EN_ROUTE.equals(next)) registrarTiempoAuto("ON_MY_WAY");
        else if (Job.STATUS_ARRIVED.equals(next)) registrarTiempoAuto("ARRIVED");
        else if (esSalidaDelSitio(next)) {
            // «Salida del sitio» en los cinco casos de cierre; cada salida deja su propia hora (la última es la que cuenta)
            JobRepository.get().registrarTimeEvent(job.id, "OFF_SITE", null, () -> { cargarTiempos(); cargarHistorial(); });
        }
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        pintarEstado();
        mostrarUpdatedAt();
        actualizarBoton();
        cargarHistorial();
    }

    /** Abre la pantalla de cierre. Las notas se guardan localmente al salir de esta pantalla (onPause) y se sincronizan allí. */
    private void abrirCierre() {
        EditText notasView = findViewById(R.id.detalleNotas);
        String diag = notasView != null ? notasView.getText().toString().trim() : "";
        Intent i = new Intent(this, CierreActivity.class);
        i.putExtra(CierreActivity.EXTRA_JOB, job.id);
        i.putExtra(CierreActivity.EXTRA_DIAG, diag.length());
        i.putExtra(CierreActivity.EXTRA_FOTOS, fotos != null ? fotos.size() : 0);
        cierreLauncher.launch(i);
    }

    private void pintarEstado() {
        int color;
        switch (job.estado != null ? job.estado : "") {
            case Job.STATUS_DONE: case Job.STATUS_PAID: color = 0xFF137333; break;
            case Job.STATUS_ARRIVED:            color = 0xFF171717; break;
            case Job.STATUS_EN_ROUTE:           color = 0xFF171717; break;
            case Job.STATUS_AWAITING_APPROVAL:  color = 0xFF171717; break;
            case Job.STATUS_EN_REPARACION:      color = 0xFFE91605; break;
            case Job.STATUS_PART_HOLD:          color = 0xFF62666A; break;
            case Job.STATUS_ON_HOLD:            color = 0xFF62666A; break;
            default:                            color = 0xFF62666A; break;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(6f);
        estadoView.setText(com.lc.technician.sync.Listas.etiquetaEstado(this, job.estado));
        estadoView.setBackground(bg);
    }

    /** Id de la acción principal de cada estado (una sola por pantalla); null si el estado no tiene acción de estado. */
    private String idAccionPrincipal() {
        switch (job.estado != null ? job.estado : "") {
            case Job.STATUS_PENDING:            return "GO_ENROUTE";
            case Job.STATUS_EN_ROUTE:           return "ARRIVED";
            case Job.STATUS_ARRIVED:            return "START_DIRECT";
            case Job.STATUS_AWAITING_APPROVAL:  return "CLIENT_APPROVED";
            case Job.STATUS_EN_REPARACION:      return "COMPLETE";   // abre el cierre
            case Job.STATUS_PART_HOLD:
            case Job.STATUS_ON_HOLD:
            case Job.STATUS_MANAGER:
            case Job.STATUS_PARTS_PRELOADED:    return "RESUME";
            case Job.STATUS_NOT_HOME:           return "GO_ENROUTE";
            default:                            return null;
        }
    }

    /** Acciones secundarias del estado (ids de job_actions.list). Las utilidades de la orden se añaden aparte. */
    private String[] idsSecundarios() {
        switch (job.estado != null ? job.estado : "") {
            case Job.STATUS_PENDING:            return new String[]{"CLIENT_CANCELLED", "NEED_ORDER"};
            case Job.STATUS_EN_ROUTE:           return new String[]{"CLIENT_CANCELLED", "NEED_ORDER"};
            case Job.STATUS_ARRIVED:            return new String[]{"ASK_APPROVAL", "NO_ACCESS", "NEED_ORDER"};
            case Job.STATUS_AWAITING_APPROVAL:  return new String[]{"CLIENT_CANCELLED"};
            case Job.STATUS_EN_REPARACION:      return new String[]{"PAUSE", "NEED_ORDER"};
            case Job.STATUS_PART_HOLD:
            case Job.STATUS_ON_HOLD:
            case Job.STATUS_MANAGER:
            case Job.STATUS_PARTS_PRELOADED:    return new String[]{"CLIENT_CANCELLED"};
            case Job.STATUS_NOT_HOME:           return new String[]{"CLIENT_CANCELLED", "NEED_ORDER"};
            default:                            return new String[]{};
        }
    }

    private void actualizarBoton() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(28f);
        botonSecundario2.setVisibility(View.GONE);
        btnCompartirReporte.setVisibility(View.GONE);
        if (btnFactura != null) btnFactura.setVisibility(View.GONE);
        if (btnCompartirCliente != null) btnCompartirCliente.setVisibility(View.GONE);
        if (btnRecordatorio != null) btnRecordatorio.setVisibility(View.GONE);
        if (btnVerComoCliente != null) btnVerComoCliente.setVisibility(View.GONE);

        String id = idAccionPrincipal();
        if (id != null) {
            // En reparación el botón abre el cierre; en los demás estados ejecuta la acción de ese estado
            String texto = "COMPLETE".equals(id) ? "CERRAR TRABAJO" : acc(id);
            setBotonPrincipal(bg, texto, 0xFFE91605, true);
        } else {
            setBotonPrincipal(bg, "FACTURA", 0xFFE91605, btnFactura != null);   // trabajo completado: la factura es lo siguiente
        }
        // «Más acciones» agrupa todo lo demás (acciones de excepción y utilidades) en un solo botón
        botonSecundario.setText("Cambiar estado  ⌃");
        GradientDrawable rojo = new GradientDrawable();
        rojo.setCornerRadius(dp(12));
        rojo.setColor(0xFFE91605);
        botonSecundario.setBackground(rojo);
        botonSecundario.setTextColor(0xFFFFFFFF);
        botonPrincipal.setVisibility(View.GONE);
        actualizarBadgeEstado();
        botonSecundario.setVisibility(View.VISIBLE);
    }

    /** Un solo botón principal por estado: ejecuta la acción de ese estado, sin menú intermedio. */
    private void accionPrincipal() {
        String id = idAccionPrincipal();
        String estado = job.estado != null ? job.estado : "";
        if (id == null) { if (btnFactura != null) btnFactura.performClick(); return; }
        manejarOpcionMenu(estado, id);
    }

    // ── Diseño premium: cabecera #ID + badge, acciones rápidas, pestañas y hoja «Cambiar estado» ──────────────────────

    private TextView tvBadgeEstado;
    private View layoutTiemposRegistrados;
    private TextView tvTiemposRegistrados;
    private boolean cabeceraArmada;

    private void actualizarBadgeEstado() {
        armarCabeceraPremium();
        if (tvBadgeEstado == null) return;
        String e = job.estado != null ? job.estado : "";
        tvBadgeEstado.setText(com.lc.technician.sync.Listas.etiquetaEstado(this, e));
        int color;
        switch (e) {
            case Job.STATUS_DONE: case Job.STATUS_PAID: color = 0xFF16A34A; break;
            case Job.STATUS_MANAGER: case Job.STATUS_NOT_HOME: color = 0xFFF59E0B; break;
            case Job.STATUS_EN_REPARACION:
            case Job.STATUS_EN_ROUTE: case Job.STATUS_ARRIVED: case Job.STATUS_AWAITING_APPROVAL: color = 0xFFE91605; break;
            default: color = 0xFF62666A;
        }
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(40f);
        g.setColor(color);
        tvBadgeEstado.setBackground(g);
    }

    private void armarCabeceraPremium() {
        if (cabeceraArmada) return;
        cabeceraArmada = true;
        android.widget.LinearLayout header = findViewById(R.id.header);
        TextView cli = findViewById(R.id.detalleCliente);
        if (cli != null) {
            cli.setText("#" + job.id);
            cli.setTextSize(24f);
            cli.setTypeface(null, android.graphics.Typeface.BOLD);
            cli.setTextColor(0xFF0A0A0A);
        }
        if (estadoView != null) estadoView.setVisibility(View.GONE);   // el estado ya va en la píldora de la cabecera
        tvBadgeEstado = new TextView(this);
        tvBadgeEstado.setTextColor(0xFFFFFFFF);
        tvBadgeEstado.setTextSize(12f);
        tvBadgeEstado.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBadgeEstado.setPadding(dp(12), dp(5), dp(12), dp(5));
        header.addView(tvBadgeEstado);

        final android.widget.ScrollView sv = encontrarScroll(findViewById(android.R.id.content));
        if (sv == null || !(sv.getChildAt(0) instanceof android.widget.LinearLayout)) return;
        final android.widget.LinearLayout col = (android.widget.LinearLayout) sv.getChildAt(0);

        // Acciones rápidas: Llamar · Mensajes · GPS · Cliente
        android.widget.LinearLayout acciones = new android.widget.LinearLayout(this);
        acciones.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        acciones.setPadding(0, dp(4), 0, dp(8));
        addAccion(acciones, R.drawable.m_phone, "Llamar", v -> {
            if (job.telefono != null && !job.telefono.isEmpty())
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + job.telefono)));
        });
        addAccion(acciones, R.drawable.m_chat, "Mensajes", v -> startActivity(new Intent(this, ChatActivity.class)
                .putExtra("job", job.id).putExtra("cliente", job.cliente)));
        addAccion(acciones, R.drawable.m_nav, "GPS", v -> { if (job.direccion != null && !job.direccion.isEmpty()) abrirMapa(job.direccion); });
        addAccion(acciones, R.drawable.m_person, "Cliente", v -> irASeccion(sv, col, "CONTACTOS"));
        col.addView(acciones, 0);
        // Bloque de datos del cliente como la maqueta: nombre, dirección con pin, teléfono con ícono y foto a la derecha
        android.widget.LinearLayout datos = new android.widget.LinearLayout(this);
        datos.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        datos.setPadding(0, dp(4), 0, dp(8));
        android.widget.LinearLayout izq = new android.widget.LinearLayout(this);
        izq.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView nombre = new TextView(this);
        nombre.setText(job.cliente);
        nombre.setTextSize(24f);
        nombre.setTypeface(null, android.graphics.Typeface.BOLD);
        nombre.setTextColor(0xFF0A0A0A);
        izq.addView(nombre);
        if (job.direccion != null && !job.direccion.isEmpty()) {
            izq.addView(filaIcono(R.drawable.m_pin, 0xFF62666A, job.direccion, 0xFF62666A, v -> abrirMapa(job.direccion)));
        }
        if (job.telefono != null && !job.telefono.isEmpty()) {
            izq.addView(filaIcono(R.drawable.m_phone, 0xFF2563EB, job.telefono, 0xFF2563EB,
                    v -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + job.telefono)))));
        }
        datos.addView(izq, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.graphics.Bitmap fb = primeraFotoBitmap();
        if (fb != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(fb);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ib = new GradientDrawable();
            ib.setCornerRadius(dp(12));
            ib.setColor(0xFFE5E5E8);
            iv.setBackground(ib);
            iv.setClipToOutline(true);
            android.widget.LinearLayout.LayoutParams ip = new android.widget.LinearLayout.LayoutParams(dp(96), dp(96));
            ip.setMarginStart(dp(12));
            datos.addView(iv, ip);
        }
        col.addView(datos, 0);
        // Lo que ya muestra el bloque de arriba (dirección, teléfono, hora, descripción) se oculta de la lista antigua;
        // los TextView siguen existiendo para que el resto del código no cambie.
        ocultarPar(R.id.detalleDireccion); ocultarPar(R.id.detalleTelefono); ocultarPar(R.id.detalleHora); ocultarPar(R.id.detalleDescripcion);
        // Equipo y encabezado «INFORMACIÓN DEL TRABAJO» ya no tienen contenido propio aquí: se ocultan si el equipo está vacío
        View tituloInfo = buscarTexto(col, "INFORMACIÓN DEL TRABAJO");
        if (tituloInfo != null) tituloInfo.setVisibility(View.GONE);
        if (job.equipo == null || job.equipo.trim().isEmpty()) ocultarPar(R.id.detalleEquipo);
        else {
            TextView eqv = findViewById(R.id.detalleEquipo);
            if (eqv != null) eqv.setText(job.equipo);
        }
        // Tarjeta «Descripción del trabajo» con etiquetas (equipo y prioridad si existen)
        android.widget.LinearLayout desc = new android.widget.LinearLayout(this);
        desc.setOrientation(android.widget.LinearLayout.VERTICAL);
        desc.setBackgroundResource(R.drawable.bg_card_f5);
        desc.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView dt = new TextView(this);
        dt.setText("Descripción del trabajo"); dt.setTextSize(15f); dt.setTypeface(null, android.graphics.Typeface.BOLD); dt.setTextColor(0xFF0A0A0A);
        TextView dv = new TextView(this);
        dv.setText(job.descripcion == null ? "" : job.descripcion); dv.setTextSize(14f); dv.setTextColor(0xFF62666A);
        dv.setPadding(0, dp(6), 0, 0);
        desc.addView(dt); desc.addView(dv);
        boolean hayEq = job.equipo != null && !job.equipo.trim().isEmpty() && !"Por asignar".equals(job.equipo.trim());
        boolean hayPr = Job.etiquetaPrioridad(job.prioridad) != null;
        String textoCat = com.lc.technician.ui.adapter.JobAdapter.etiquetaCategoria(com.lc.technician.sync.Listas.categoriaDe(this, job.id));
        if (textoCat != null || hayEq || hayPr) {
            android.widget.LinearLayout tg = new android.widget.LinearLayout(this);
            tg.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            tg.setPadding(0, dp(10), 0, 0);
            if (textoCat != null) tg.addView(etiqueta(textoCat, 0xFF0A0A0A));
            if (hayEq) tg.addView(etiqueta(job.equipo.trim(), 0xFF0A0A0A));
            if (hayPr) tg.addView(etiqueta(Job.etiquetaPrioridad(job.prioridad), Job.colorPrioridad(job.prioridad)));
            desc.addView(tg);
        }
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(8);
        col.addView(desc, Math.min(col.getChildCount(), 3), dlp);
        View eq = findViewById(R.id.detalleDescripcion);
        if (eq != null && eq.getParent() instanceof android.view.ViewGroup && job.direccion != null && !job.direccion.isEmpty()) {
            android.view.ViewGroup pg = (android.view.ViewGroup) eq.getParent();
            TextView maps = new TextView(this);
            maps.setText("Abrir en Google Maps");
            maps.setGravity(android.view.Gravity.CENTER);
            maps.setTextColor(0xFFE91605);
            maps.setTextSize(14f);
            maps.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable mb = new GradientDrawable();
            mb.setCornerRadius(dp(12));
            mb.setStroke(dp(1), 0xFFE91605);
            mb.setColor(0xFFFFFFFF);
            maps.setBackground(mb);
            maps.setOnClickListener(v -> abrirMapa(job.direccion));
            android.view.ViewGroup.MarginLayoutParams mlp = pg instanceof android.widget.LinearLayout
                    ? new android.widget.LinearLayout.LayoutParams(-1, dp(52)) : null;
            if (mlp != null) {
                mlp.topMargin = dp(12);
                pg.addView(maps, pg.indexOfChild(eq) + 1, mlp);
                // Tarjeta de mapa: placeholder con pin hasta tener la API key de Google Maps (pendiente documentado)
                TextView tituloDir = new TextView(this);
                tituloDir.setText("Dirección");
                tituloDir.setTextSize(16f); tituloDir.setTypeface(null, android.graphics.Typeface.BOLD); tituloDir.setTextColor(0xFF0A0A0A);
                android.widget.LinearLayout.LayoutParams tdl = new android.widget.LinearLayout.LayoutParams(-1, -2);
                tdl.topMargin = dp(16);
                pg.addView(tituloDir, pg.indexOfChild(eq) + 1, tdl);
                android.widget.FrameLayout mapa = new android.widget.FrameLayout(this);
                GradientDrawable mg = new GradientDrawable();
                mg.setCornerRadius(dp(12));
                mg.setColor(0xFFE8EAED);
                mapa.setBackground(mg);
                android.widget.ImageView pin = new android.widget.ImageView(this);
                pin.setImageResource(R.drawable.m_pin);
                pin.setColorFilter(0xFFE91605);
                mapa.addView(pin, new android.widget.FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.CENTER));
                TextView dir = new TextView(this);
                dir.setText(job.direccion);
                dir.setTextSize(12f);
                dir.setTextColor(0xFF62666A);
                dir.setMaxLines(1);
                dir.setEllipsize(android.text.TextUtils.TruncateAt.END);
                dir.setPadding(dp(12), 0, dp(12), dp(8));
                mapa.addView(dir, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
                mapa.setOnClickListener(v -> abrirMapa(job.direccion));
                android.widget.LinearLayout.LayoutParams mpl = new android.widget.LinearLayout.LayoutParams(-1, dp(140));
                mpl.topMargin = dp(8);
                pg.addView(mapa, pg.indexOfChild(tituloDir) + 1, mpl);
            }
        }

        // Pestañas: Info · Fotos · Mediciones · Partes (llevan a la sección correspondiente de esta misma pantalla)
        android.widget.LinearLayout tabs = new android.widget.LinearLayout(this);
        tabs.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        String[] nombres = {"Info", "Fotos", "Mediciones", "Partes"};
        final String[] ancla = {null, "EVIDENCIAS FOTOGRÁFICAS", "INSPECCIÓN TÉCNICA", "REPUESTOS USADOS"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(nombres[i]);
            t.setGravity(android.view.Gravity.CENTER);
            t.setTextSize(14f);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setTextColor(i == 0 ? 0xFFE91605 : 0xFF62666A);
            t.setTypeface(null, i == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setBackground(subrayado(i == 0));
            t.setOnClickListener(v -> {
                for (int k = 0; k < tabs.getChildCount(); k++) {
                    TextView o = (TextView) tabs.getChildAt(k);
                    o.setTextColor(k == idx ? 0xFFE91605 : 0xFF62666A);
                    o.setTypeface(null, k == idx ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    o.setBackground(subrayado(k == idx));
                }
                if (ancla[idx] == null) sv.smoothScrollTo(0, 0); else irASeccion(sv, col, ancla[idx]);
            });
            tabs.addView(t, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        }
        col.addView(tabs, 2);
    }

    private android.graphics.drawable.Drawable subrayado(boolean activa) {
        GradientDrawable linea = new GradientDrawable();
        linea.setColor(activa ? 0xFFE91605 : 0xFFE5E5E5);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0x00000000);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fondo, linea});
        ld.setLayerGravity(1, android.view.Gravity.BOTTOM);
        ld.setLayerHeight(1, dp(activa ? 3 : 1));
        return ld;
    }

    private View filaIcono(int icono, int colorIcono, String texto, int colorTexto, View.OnClickListener ir) {
        android.widget.LinearLayout f = new android.widget.LinearLayout(this);
        f.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        f.setGravity(android.view.Gravity.CENTER_VERTICAL);
        f.setPadding(0, dp(6), 0, dp(2));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(icono);
        iv.setColorFilter(colorIcono);
        f.addView(iv, new android.widget.LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = new TextView(this);
        t.setText(texto); t.setTextSize(15f); t.setTextColor(colorTexto);
        t.setPadding(dp(8), 0, 0, 0);
        f.addView(t);
        f.setOnClickListener(ir);
        return f;
    }

    private TextView etiqueta(String texto, int color) {
        TextView t = new TextView(this);
        t.setText(texto); t.setTextSize(12f); t.setTypeface(null, android.graphics.Typeface.BOLD); t.setTextColor(color);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14)); g.setColor(0xFFFFFFFF); g.setStroke(dp(1), 0xFFE5E5E5);
        t.setBackground(g);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    /** Oculta una vista de la lista antigua y la etiqueta que la precede (la vista sigue existiendo). */
    private void ocultarPar(int id) {
        View v = findViewById(id);
        if (v == null) return;
        v.setVisibility(View.GONE);
        if (v.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v.getParent();
            int i = g.indexOfChild(v);
            if (i > 0) g.getChildAt(i - 1).setVisibility(View.GONE);
        }
    }

    private android.graphics.Bitmap primeraFotoBitmap() {
        try {
            for (com.lc.technician.model.PhotoEntity p : JobRepository.get().getPhotosForJob(job.id)) {
                if (p.path == null || !new java.io.File(p.path).exists()) continue;
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(p.path, o);
                int ss = 1;
                while (o.outWidth / (ss * 2) >= 240) ss *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = ss;
                return android.graphics.BitmapFactory.decodeFile(p.path, o);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void addAccion(android.widget.LinearLayout fila, int icono, String texto, View.OnClickListener ir) {
        android.widget.LinearLayout b = new android.widget.LinearLayout(this);
        b.setOrientation(android.widget.LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER);
        b.setPadding(0, dp(10), 0, dp(10));
        b.setBackgroundResource(R.drawable.bg_surface);
        android.widget.ImageView i = new android.widget.ImageView(this);
        i.setImageResource(icono);
        i.setColorFilter(0xFF0A0A0A);
        TextView t = new TextView(this);
        t.setText(texto); t.setTextSize(12f); t.setGravity(android.view.Gravity.CENTER); t.setTextColor(0xFF0A0A0A);
        b.addView(i, new android.widget.LinearLayout.LayoutParams(dp(24), dp(24)));
        b.addView(t);
        b.setOnClickListener(ir);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        fila.addView(b, lp);
    }

    private void irASeccion(final android.widget.ScrollView sv, final android.widget.LinearLayout col, String titulo) {
        View v = buscarTexto(col, titulo);
        if (v == null) return;
        int y = 0;
        View cur = v;
        while (cur != null && cur != col) { y += cur.getTop(); cur = (View) cur.getParent(); }
        sv.smoothScrollTo(0, Math.max(0, y - dp(8)));
    }

    private View buscarTexto(View v, String t) {
        if (v instanceof TextView && t.equals(((TextView) v).getText().toString())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = buscarTexto(g.getChildAt(i), t);
                if (r != null) return r;
            }
        }
        return null;
    }

    private android.widget.ScrollView encontrarScroll(View v) {
        if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ScrollView r = encontrarScroll(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Círculo de color con glifo blanco, como los íconos de la hoja «Cambiar estado» de la maqueta. */
    private View iconoEstado(int color, String nombre) {
        int glifo;
        switch (nombre) {
            case "En camino": glifo = R.drawable.m_car; break;
            case "Completar": case "Siguiente": glifo = R.drawable.m_check; break;
            case "Requiere Gerente": glifo = R.drawable.m_person; break;
            case "Cliente No Está": glifo = R.drawable.m_door; break;
            case "Necesita Repuesto": glifo = R.drawable.m_wrench; break;
            case "Repuesto Pre-cargado": glifo = R.drawable.m_box; break;
            case "Cancelar": glifo = R.drawable.m_x; break;
            case "Nueva Orden": glifo = R.drawable.m_plus; break;
            default: glifo = -1;
        }
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(color);
        f.setBackground(c);
        if (glifo != -1) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(glifo);
            iv.setColorFilter(0xFFFFFFFF);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(22), dp(22));
            lp.gravity = android.view.Gravity.CENTER;
            f.addView(iv, lp);
        } else {
            TextView t = new TextView(this);
            t.setText("$"); t.setTextColor(0xFFFFFFFF); t.setTextSize(20f); t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setGravity(android.view.Gravity.CENTER);
            f.addView(t, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        return f;
    }

    /** Hoja inferior «Cambiar estado del trabajo» con las 9 opciones. Las opciones sin función real quedan atenuadas. */
    private void mostrarCambiarEstado() {
        final String estado = job.estado != null ? job.estado : "";
        final boolean abierta = !Job.esCompletado(estado) && !Job.STATUS_CANCELLED.equals(estado);
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout hoja = new android.widget.LinearLayout(this);
        hoja.setOrientation(android.widget.LinearLayout.VERTICAL);
        hoja.setPadding(dp(20), dp(16), dp(20), dp(20));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0xFFFFFFFF);
        fondo.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        hoja.setBackground(fondo);
        android.widget.LinearLayout cab = new android.widget.LinearLayout(this);
        cab.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        cab.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titulo = new TextView(this);
        titulo.setText("Cambiar estado del trabajo");
        titulo.setTextSize(18f); titulo.setTypeface(null, android.graphics.Typeface.BOLD); titulo.setTextColor(0xFF0A0A0A);
        titulo.setPadding(0, 0, 0, dp(8));
        cab.addView(titulo, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.ImageView cerrarX = new android.widget.ImageView(this);
        cerrarX.setImageResource(R.drawable.m_x);
        cerrarX.setColorFilter(0xFF0A0A0A);
        cerrarX.setOnClickListener(v -> dlg.dismiss());
        cab.addView(cerrarX, new android.widget.LinearLayout.LayoutParams(dp(24), dp(24)));
        hoja.addView(cab);
        android.widget.ScrollView cuerpo = new android.widget.ScrollView(this);
        android.widget.LinearLayout filas = new android.widget.LinearLayout(this);
        filas.setOrientation(android.widget.LinearLayout.VERTICAL);
        cuerpo.addView(filas);
        hoja.addView(cuerpo, new android.widget.LinearLayout.LayoutParams(-1, -2));
        final boolean pend = Job.STATUS_PENDING.equals(estado), rep = Job.STATUS_EN_REPARACION.equals(estado);
        Object[][] op = {
            {"En camino", "Voy al trabajo", 0xFF2563EB, pend, "GO_ENROUTE"},
            {"Completar", "Trabajo terminado (sin pago)", 0xFF16A34A, rep, "COMPLETE"},
            {"Pagado / Completado", "Trabajo terminado y pagado", 0xFF16A34A, Job.STATUS_DONE.equals(estado), "#PAGADO"},
            {"Requiere Gerente", "Necesita revisión de gerente", 0xFFF59E0B, abierta && !Job.STATUS_MANAGER.equals(estado), "#GERENTE"},
            {"Necesita Repuesto", "Esperando parte", 0xFF7C3AED, rep, "PART_HOLD"},
            {"Repuesto Pre-cargado", "Parte ya asignada", 0xFF7C3AED, abierta && !Job.STATUS_PARTS_PRELOADED.equals(estado), "#PRECARGADO"},
            {"Cliente No Está", "Cliente no disponible en la dirección", 0xFFF59E0B, abierta && !Job.STATUS_NOT_HOME.equals(estado), "NO_ACCESS"},
            {"Cancelar", "Trabajo cancelado", 0xFFE91605, abierta, "CLIENT_CANCELLED"},
            {"Nueva Orden", "Crear nueva orden desde este cliente", 0xFF62666A, true, "NEED_ORDER"}};
        // Siguiente paso del flujo (llegué, iniciar reparación, cerrar trabajo, factura…): sigue disponible, ahora dentro de la hoja
        final String idPrincipal = idAccionPrincipal();
        final String textoPrincipal = idPrincipal == null ? "Factura" : ("COMPLETE".equals(idPrincipal) ? "Cerrar trabajo" : acc(idPrincipal).substring(0, 1) + acc(idPrincipal).substring(1).toLowerCase(java.util.Locale.ROOT));
        final boolean yaEnLas9 = "GO_ENROUTE".equals(idPrincipal) || "COMPLETE".equals(idPrincipal);
        if (!yaEnLas9) {
            android.widget.LinearLayout f0 = new android.widget.LinearLayout(this);
            f0.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            f0.setGravity(android.view.Gravity.CENTER_VERTICAL);
            f0.setPadding(0, dp(10), 0, dp(10));
            f0.addView(iconoEstado(0xFFE91605, "Siguiente"), new android.widget.LinearLayout.LayoutParams(dp(40), dp(40)));
            ((android.widget.LinearLayout.LayoutParams) f0.getChildAt(0).getLayoutParams()).setMarginEnd(dp(14));
            android.widget.LinearLayout t0 = new android.widget.LinearLayout(this);
            t0.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView a0 = new TextView(this);
            a0.setText(textoPrincipal); a0.setTextSize(16f); a0.setTypeface(null, android.graphics.Typeface.BOLD); a0.setTextColor(0xFF0A0A0A);
            TextView b0 = new TextView(this);
            b0.setText("Siguiente paso del trabajo"); b0.setTextSize(13f); b0.setTextColor(0xFF62666A);
            t0.addView(a0); t0.addView(b0);
            f0.addView(t0);
            f0.setOnClickListener(v -> { dlg.dismiss(); accionPrincipal(); });
            filas.addView(f0);
        }
        for (final Object[] o : op) {
            final boolean on = (Boolean) o[3];
            android.widget.LinearLayout f = new android.widget.LinearLayout(this);
            f.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            f.setGravity(android.view.Gravity.CENTER_VERTICAL);
            f.setPadding(0, dp(10), 0, dp(10));
            f.setAlpha(on ? 1f : 0.4f);
            f.addView(iconoEstado((Integer) o[2], (String) o[0]), new android.widget.LinearLayout.LayoutParams(dp(40), dp(40)));
            ((android.widget.LinearLayout.LayoutParams) f.getChildAt(0).getLayoutParams()).setMarginEnd(dp(14));
            android.widget.LinearLayout t = new android.widget.LinearLayout(this);
            t.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView a = new TextView(this);
            a.setText((String) o[0]); a.setTextSize(16f); a.setTypeface(null, android.graphics.Typeface.BOLD); a.setTextColor(0xFF0A0A0A);
            TextView b = new TextView(this);
            b.setText(on || !"#PAGADO".equals(o[4]) ? (String) o[1] : "Disponible cuando el trabajo esté completado");
            b.setTextSize(13f); b.setTextColor(0xFF62666A);
            t.addView(a); t.addView(b);
            f.addView(t);
            if (on) f.setOnClickListener(v -> {
                dlg.dismiss();
                String id = (String) o[4];
                if ("#GERENTE".equals(id)) aplicarEstado(Job.STATUS_MANAGER, "Se avisó a Control: requiere gerente");
                else if ("#PAGADO".equals(id)) confirmarPagado();
                else if ("#PRECARGADO".equals(id)) aplicarEstado(Job.STATUS_PARTS_PRELOADED, "Repuesto pre-cargado registrado");
                else manejarOpcionMenu(estado, id);
            });
            filas.addView(f);
        }
        TextView mas = new TextView(this);
        mas.setText("Más opciones del trabajo…");
        mas.setTextSize(14f); mas.setTextColor(0xFFE91605); mas.setTypeface(null, android.graphics.Typeface.BOLD);
        mas.setPadding(0, dp(14), 0, dp(4));
        mas.setOnClickListener(v -> { dlg.dismiss(); mostrarMasAcciones(); });
        filas.addView(mas);
        dlg.setContentView(hoja);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.BOTTOM);
        }
        dlg.show();
    }

    private void mostrarMasAcciones() {
        String estado = job.estado != null ? job.estado : "";
        final com.lc.technician.sync.Listas.Lista acc = com.lc.technician.sync.Listas.lista(this, "job_actions.list", ACCIONES_RESPALDO, null);
        java.util.List<String> etiquetas = new java.util.ArrayList<>();
        java.util.List<Runnable> hacer = new java.util.ArrayList<>();
        for (String id : idsSecundarios()) {
            int ix = acc.indexOf(id);
            etiquetas.add(ix >= 0 ? acc.labels[ix] : id);
            hacer.add(() -> manejarOpcionMenu(estado, id));
        }
        if (Job.STATUS_PENDING.equals(estado)) {
            if (btnRecordatorio != null) { etiquetas.add("Recordatorio"); hacer.add(() -> btnRecordatorio.performClick()); }
            if (btnVerComoCliente != null) { etiquetas.add("Ver como cliente"); hacer.add(() -> btnVerComoCliente.performClick()); }
        }
        if (idAccionPrincipal() == null) {   // completado
            etiquetas.add("Compartir reporte"); hacer.add(() -> btnCompartirReporte.performClick());
            if (btnCompartirCliente != null) { etiquetas.add("Compartir con cliente"); hacer.add(() -> btnCompartirCliente.performClick()); }
            if (btnVerComoCliente != null) { etiquetas.add("Ver como cliente"); hacer.add(() -> btnVerComoCliente.performClick()); }
        }
        if (etiquetas.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Más acciones")
                .setItems(etiquetas.toArray(new String[0]), (d, which) -> hacer.get(which).run())
                .show();
    }

    /** Texto en mayúsculas de una acción del menú (editable en Control → Listas editables → Acciones del menú de estado). */
    private String acc(String id) {
        com.lc.technician.sync.Listas.Lista l = com.lc.technician.sync.Listas.lista(this, "job_actions.list", ACCIONES_RESPALDO, null);
        int ix = l.indexOf(id);
        return (ix >= 0 ? l.labels[ix] : id).toUpperCase(java.util.Locale.ROOT);
    }

    private void setBotonPrincipal(GradientDrawable bg, String texto, int color, boolean enabled) {
        bg.setColor(color);
        botonPrincipal.setText(texto);
        botonPrincipal.setBackground(bg);
        botonPrincipal.setEnabled(enabled);
    }

    private void mostrarUpdatedAt() {
        if (job.updatedAt != null) {
            updatedAtView.setText(FMT_FECHA.format(new Date(job.updatedAt)));
        } else {
            updatedAtView.setText("Sin cambios recientes");
            updatedAtView.setTextColor(0xFFB0B0B0);
        }
    }

    // ── Fotos ─────────────────────────────────────────────────────────────────

    private void cargarFotos() {
        JobRepository.get().getPhotosForJobAsync(job.id, fotosResult -> {
            fotos = fotosResult;
            layoutFotoSecciones.removeAllViews();

            String[] cats   = {"ANTES",   "DESPUÉS", "EQUIPO",   "NAMEPLATE", null};
            int[]    colors = {0xFFE91605, 0xFF171717, 0xFF62666A, 0xFF767676, 0xFF999999};
            String[] labels = {"ANTES",   "DESPUÉS", "EQUIPO",   "NAMEPLATE", "SIN CATEGORÍA"};

            float density = getResources().getDisplayMetrics().density;
            boolean hayFotos = false;

            for (int ci = 0; ci < cats.length; ci++) {
                final String cat = cats[ci];
                java.util.List<PhotoEntity> grupo = new java.util.ArrayList<>();
                for (PhotoEntity p : fotos) {
                    boolean coincide = cat == null
                            ? (p.categoria == null || p.categoria.isEmpty())
                            : cat.equals(p.categoria);
                    if (coincide) grupo.add(p);
                }
                if (grupo.isEmpty()) continue;
                hayFotos = true;

                TextView header = new TextView(this);
                header.setText(labels[ci]);
                header.setTextColor(colors[ci]);
                header.setTextSize(11f);
                header.setTypeface(null, Typeface.BOLD);
                header.setLetterSpacing(0.08f);
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                hp.topMargin = (int) (ci == 0 ? 0 : 16 * density);
                hp.bottomMargin = (int) (6 * density);
                header.setLayoutParams(hp);
                layoutFotoSecciones.addView(header);

                RecyclerView rv = new RecyclerView(this);
                rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
                rv.setAdapter(new PhotoAdapter(grupo, photo -> {
                    Intent i = new Intent(this, PhotoViewActivity.class);
                    i.putExtra("photo_path", photo.path);
                    startActivity(i);
                }));
                layoutFotoSecciones.addView(rv);
            }

            tvSinFotos.setVisibility(hayFotos ? View.GONE : View.VISIBLE);
            layoutFotoSecciones.setVisibility(hayFotos ? View.VISIBLE : View.GONE);
        });
    }

    /** Abre la app de mapas del teléfono (la predeterminada) con la dirección; sin ninguna, abre Google Maps en el navegador. */
    private void abrirMapa(String direccion) {
        String q = Uri.encode(direccion);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + q)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + q)));
            } catch (Exception e2) {
                Toast.makeText(this, "No se pudo abrir el mapa", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** «Tomar foto»: abre la cámara de una vez. La categoría sale del estado de la orden (antes / después). */
    private void tomarFotoDirecto() {
        String e = job.estado == null ? "" : job.estado.toUpperCase(Locale.ROOT);
        pendingCategoria = (e.contains("REPARACI") || e.contains("COMPLET")) ? "DESPUÉS" : "ANTES";
        launcherPermisoCamara.launch(Manifest.permission.CAMERA);
    }

    private void mostrarOpcionesFoto() {
        // categorías editables en Control (settings photo_categories.list)
        final com.lc.technician.sync.Listas.Lista pcat = com.lc.technician.sync.Listas.lista(this, "photo_categories.list",
                new String[][]{{"ANTES", "Antes"}, {"DESPUÉS", "Después"}, {"EQUIPO", "Equipo"}, {"NAMEPLATE", "Nameplate"}}, null);
        new AlertDialog.Builder(this)
                .setTitle("Categoría de la foto")
                .setItems(pcat.labels, (d, which) -> {
                    pendingCategoria = pcat.ids[which];
                    mostrarFuenteFoto();
                })
                .show();
    }

    private void mostrarFuenteFoto() {
        // orígenes editables en Control (settings photo_sources.list); la foto sintética solo existe en la variante de laboratorio
        final com.lc.technician.sync.Listas.Lista src = com.lc.technician.sync.Listas.lista(this, "photo_sources.list",
                new String[][]{{"CAMERA", "Tomar foto"}, {"GALLERY", "Elegir de galería"}}, null);
        final int n = src.ids.length;
        String[] opciones = new String[n + (BuildConfig.DEBUG ? 1 : 0)];
        System.arraycopy(src.labels, 0, opciones, 0, n);
        if (BuildConfig.DEBUG) opciones[n] = "Foto de prueba (laboratorio)";
        new AlertDialog.Builder(this)
                .setTitle("Origen de la foto")
                .setItems(opciones, (dialog, which) -> {
                    String id = which < n ? src.ids[which] : "LAB";
                    if ("CAMERA".equals(id)) launcherPermisoCamara.launch(Manifest.permission.CAMERA);
                    else if ("GALLERY".equals(id)) launcherGaleria.launch("image/*");
                    else crearFotoDePrueba();
                })
                .show();
    }

    /** Imagen JPEG generada en la app (rotulada como prueba) para QA sin usar fotos reales. */
    private void crearFotoDePrueba() {
        if (!BuildConfig.DEBUG) return;
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(1280, 960,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            String cat = pendingCategoria != null ? pendingCategoria : "PRUEBA";
            cv.drawColor("ANTES".equals(cat) ? 0xFF171717 : "DESPUÉS".equals(cat) ? 0xFF62666A : 0xFF171717);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(72f);
            cv.drawText("FOTO DE PRUEBA · L&C LAB", 60, 200, p);
            p.setTextSize(56f);
            cv.drawText("Categoría: " + cat, 60, 380, p);
            cv.drawText("Orden: " + job.id, 60, 480, p);
            cv.drawText(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()), 60, 580, p);
            p.setTextSize(40f);
            cv.drawText("Imagen sintética generada por la app DEV (no es una foto real)", 60, 860, p);
            File dir = getExternalFilesDir("fotos");
            if (dir != null) dir.mkdirs();
            File dest = new File(dir, "foto_prueba_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(dest)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            }
            bmp.recycle();
            agregarFoto(dest.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo crear la foto de prueba", Toast.LENGTH_SHORT).show();
        }
    }

    private void abrirCamara() {
        try {
            File dir = getExternalFilesDir("fotos");
            if (dir != null) dir.mkdirs();
            tempFotoFile = File.createTempFile("foto_" + System.currentTimeMillis(), ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", tempFotoFile);
            launcherCamara.launch(uri);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show();
        }
    }

    private void guardarFotoDesdeGaleria(Uri uri) {
        try {
            File dir = getExternalFilesDir("fotos");
            if (dir != null) dir.mkdirs();
            File dest = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            agregarFoto(dest.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar la foto", Toast.LENGTH_SHORT).show();
        }
    }

    private void agregarFoto(String path) {
        PhotoEntity photo = new PhotoEntity();
        photo.jobId = job.id;
        photo.path = path;
        photo.tipo = "evidencia";
        photo.categoria = pendingCategoria;
        photo.timestamp = System.currentTimeMillis();
        JobRepository.get().insertPhoto(photo);
        cargarFotos();
        cargarHistorial();
    }

    // ── Contactos ─────────────────────────────────────────────────────────────

    private void cargarContactos() {
        // Use context-aware loader: job > property > customer contacts (deduplicated)
        Long customerId = (job.customerId != null && job.customerId > 0) ? job.customerId : null;
        Long propertyId = (job.propertyId != null && job.propertyId > 0) ? job.propertyId : null;
        JobRepository.get().getContactsForJobContextAsync(job.id, customerId, propertyId, fresh -> {
            if (contactAdapter == null) {
                contactos = fresh;
                contactAdapter = new ContactAdapter(contactos, this::confirmarBorrarContacto);
                recyclerContactos.setLayoutManager(new LinearLayoutManager(this));
                recyclerContactos.setAdapter(contactAdapter);
            } else {
                contactos.clear();
                contactos.addAll(fresh);
                contactAdapter.notifyDataSetChanged();
            }
            boolean hay = !contactos.isEmpty();
            tvSinContactos.setVisibility(hay ? View.GONE : View.VISIBLE);
            recyclerContactos.setVisibility(hay ? View.VISIBLE : View.GONE);
        });
    }

    private void mostrarDialogoNuevoContacto() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View form = inflater.inflate(R.layout.dialog_nuevo_contacto, null);

        EditText etNombre     = form.findViewById(R.id.etContactoNombre);
        EditText etTelefono   = form.findViewById(R.id.etContactoTelefono);
        EditText etEmail      = form.findViewById(R.id.etContactoEmail);
        RadioGroup rgRol      = form.findViewById(R.id.rgRol);
        CheckBox cbPrincipal  = form.findViewById(R.id.checkPrincipal);
        CheckBox cbCliente    = form.findViewById(R.id.checkParaCliente);
        CheckBox cbPropiedad  = form.findViewById(R.id.checkParaPropiedad);
        Button btnGuardar     = form.findViewById(R.id.btnGuardarContacto);

        boolean hasCustomer  = job.customerId != null && job.customerId > 0;
        boolean hasProperty  = job.propertyId != null && job.propertyId > 0;

        // Hide "save for customer" if job has no customer linked
        if (cbCliente != null) {
            cbCliente.setVisibility(hasCustomer ? View.VISIBLE : View.GONE);
        }
        // Hide "save for property" if job has no property linked
        if (cbPropiedad != null) {
            cbPropiedad.setVisibility(hasProperty ? View.VISIBLE : View.GONE);
        }

        // Mutual exclusion: checking property unchecks customer-wide and vice versa
        if (cbCliente != null && cbPropiedad != null) {
            cbCliente.setOnCheckedChangeListener((btn, checked) -> {
                if (checked && cbPropiedad.isChecked()) cbPropiedad.setChecked(false);
            });
            cbPropiedad.setOnCheckedChangeListener((btn, checked) -> {
                if (checked && cbCliente.isChecked()) cbCliente.setChecked(false);
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nuevo contacto")
                .setView(form)
                .create();

        btnGuardar.setOnClickListener(v -> {
            String nombre = etNombre.getText().toString().trim();
            if (nombre.isEmpty()) {
                etNombre.setError("Nombre requerido");
                return;
            }

            String rol;
            int rid = rgRol.getCheckedRadioButtonId();
            if (rid == R.id.rbInquilino)      rol = "INQUILINO";
            else if (rid == R.id.rbPagador)   rol = "PAGADOR";
            else if (rid == R.id.rbFirmante)  rol = "FIRMANTE";
            else                               rol = "DUEÑO";

            ContactEntity c = new ContactEntity();
            c.jobId       = job.id;
            c.nombre      = nombre;
            c.telefono    = etTelefono.getText().toString().trim();
            c.email       = etEmail.getText().toString().trim();
            c.rol         = rol;
            c.esPrincipal = cbPrincipal.isChecked();

            boolean saveForPropiedad = cbPropiedad != null
                    && cbPropiedad.getVisibility() == View.VISIBLE
                    && cbPropiedad.isChecked();

            boolean saveForCustomer = !saveForPropiedad
                    && (cbCliente == null || cbCliente.getVisibility() != View.VISIBLE
                        || cbCliente.isChecked());

            if (saveForPropiedad && hasProperty) {
                // Property-scoped: link to both customer and property
                c.customerId = job.customerId;
                c.propertyId = job.propertyId;
            } else if (saveForCustomer && hasCustomer) {
                c.customerId = job.customerId;
                // propertyId remains null → visible across all properties for this customer
            }
            // else: job-only contact (neither box checked)

            JobRepository.get().addContact(c);
            dialog.dismiss();
            cargarContactos();
            cargarHistorial();
        });

        dialog.show();
    }

    private void confirmarBorrarContacto(ContactEntity c) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar contacto")
                .setMessage("¿Eliminar a " + c.nombre + "?")
                .setPositiveButton("Eliminar", (d, w) -> {
                    JobRepository.get().deleteContact(c.id, job.id, c.nombre);
                    cargarContactos();
                    cargarHistorial();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ── Reporte PDF ───────────────────────────────────────────────────────────

    private void compartirReporte() {
        if (!Job.STATUS_DONE.equals(job.estado)) {
            Toast.makeText(this, "Solo puedes compartir trabajos completados", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Generando reporte…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<PhotoEntity>  fotosSnap     = JobRepository.get().getPhotosForJob(job.id);
                List<ContactEntity> contactSnap  = JobRepository.get().getContactsForJob(job.id);
                List<JobEventEntity> eventSnap   = JobRepository.get().getEventsForJob(job.id);
                List<InspectionItemEntity> inspecSnap = JobRepository.get().getInspectionItems(job.id);
                List<FieldMeasurementEntity> medSnap  = JobRepository.get().getFieldMeasurements(job.id);
                ProposalEntity propSnap = JobRepository.get().getProposalForJob(job.id);
                List<ProposalOptionEntity> propOptSnap = propSnap != null
                        ? JobRepository.get().getOptionsForProposal(propSnap.id)
                        : new java.util.ArrayList<>();
                SignatureEntity firmaSnap = JobRepository.get().getSignatureForJob(job.id);
                TimeStats tiemposSnap = JobRepository.get().calcularTiempos(job.id);
                CustomerEntity customerSnap = (job.customerId != null && job.customerId > 0)
                        ? JobRepository.get().getCustomerById(job.customerId) : null;
                List<Job> prevJobsSnap = (job.customerId != null && job.customerId > 0)
                        ? JobRepository.get().getJobsByCustomer(job.customerId, job.id)
                        : new java.util.ArrayList<>();
                List<JobPartEntity> partesSnap = JobRepository.get().getJobPartsForJob(job.id);
                java.util.Map<Long, PartEntity> partesMapSnap = new java.util.HashMap<>();
                for (JobPartEntity jp : partesSnap) {
                    PartEntity pe = JobRepository.get().getPartById(jp.partId);
                    if (pe != null) partesMapSnap.put(pe.id, pe);
                }
                List<WorkItemEntity> workItemsSnap = JobRepository.get().getWorkItemsForJob(job.id);

                java.io.File pdf = ReportePdfGenerator.generar(
                        this, job, fotosSnap, contactSnap, eventSnap, inspecSnap, medSnap,
                        propSnap, propOptSnap, firmaSnap, tiemposSnap, customerSnap, prevJobsSnap,
                        partesSnap, partesMapSnap, workItemsSnap);
                JobRepository.get().agregarEvento(job.id, "REPORTE",
                        "Reporte PDF generado y compartido");
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", pdf);
                String fecha = new java.text.SimpleDateFormat("d MMM yyyy",
                        java.util.Locale.forLanguageTag("es"))
                        .format(new java.util.Date());
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/pdf");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.putExtra(Intent.EXTRA_SUBJECT,
                        "Reporte de servicio — " + job.cliente);
                share.putExtra(Intent.EXTRA_TEXT,
                        "Adjunto el reporte del servicio realizado el " + fecha + ".");
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    cargarHistorial();
                    startActivity(Intent.createChooser(share, "Compartir reporte"));
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Error al generar el PDF: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Compartir con cliente ─────────────────────────────────────────────────

    private void compartirConCliente() {
        Toast.makeText(this, "Generando resumen para cliente…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<PhotoEntity> fotosSnap = JobRepository.get().getPhotosForJob(job.id);
                SignatureEntity firmaSnap   = JobRepository.get().getSignatureForJob(job.id);
                java.io.File pdf = ReportePdfGenerator.generarReporteCliente(
                        this, job, fotosSnap, firmaSnap);
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", pdf);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/pdf");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.putExtra(Intent.EXTRA_SUBJECT, "Resumen de servicio — " + job.cliente);
                share.putExtra(Intent.EXTRA_TEXT,
                        "Estimado " + job.cliente + ", adjuntamos el resumen de su servicio.");
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() ->
                        startActivity(Intent.createChooser(share, "Compartir con cliente")));
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Recordatorio ──────────────────────────────────────────────────────────

    private void mostrarDialogoRecordatorio() {
        String[] opciones = {"En 1 hora", "En 3 horas", "Mañana 9 AM", "Cancelar"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Programar recordatorio")
                .setItems(opciones, (d, which) -> {
                    if (which == 3) return;
                    long delay;
                    if (which == 0) delay = 60 * 60 * 1000L;
                    else if (which == 1) delay = 3 * 60 * 60 * 1000L;
                    else {
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 9);
                        cal.set(java.util.Calendar.MINUTE, 0);
                        cal.set(java.util.Calendar.SECOND, 0);
                        delay = cal.getTimeInMillis() - System.currentTimeMillis();
                    }
                    programarRecordatorio(delay);
                })
                .show();
    }

    private void programarRecordatorio(long delayMs) {
        NotificationHelper.crearCanalNotificaciones(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 99);
            }
        }
        Intent intent = new Intent(this, RecordatorioReceiver.class);
        intent.putExtra("job_id", job.id);
        intent.putExtra("cliente", job.cliente);
        intent.putExtra("descripcion", job.descripcion != null ? job.descripcion : job.equipo);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                this, (int) job.id, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs, pi);
            Toast.makeText(this, "Recordatorio programado", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Inspección ────────────────────────────────────────────────────────────

    private void cargarResumenInspeccion() {
        JobRepository.get().getInspectionDataAsync(job.id, (items, meds) -> {
            if (items.isEmpty() && meds.isEmpty()) {
                tvResumenInspeccion.setText("Sin inspecci\u00f3n registrada");
            } else {
                long pass = 0, issues = 0;
                for (InspectionItemEntity e : items) {
                    if ("PASS".equals(e.estado)) pass++;
                    else if ("ISSUE".equals(e.estado)) issues++;
                }
                tvResumenInspeccion.setText(pass + " OK \u00b7 " + issues + " issue(s) \u00b7 " + meds.size() + " mediciones");
            }
        });
    }

    // ── Hallazgos Summary (Phase 8) ───────────────────────────────────────────

    private void cargarResumenHallazgos() {
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            int critical = db.findingDao().getFindingCountBySeverity(job.id, "CRITICAL");
            int high     = db.findingDao().getFindingCountBySeverity(job.id, "HIGH");
            int medium   = db.findingDao().getFindingCountBySeverity(job.id, "MEDIUM");
            int low      = db.findingDao().getFindingCountBySeverity(job.id, "LOW");
            int total    = critical + high + medium + low;

            runOnUiThread(() -> {
                if (total > 0) {
                    cardHallazgosSummary.setVisibility(android.view.View.VISIBLE);
                    tvFindingCritical.setText("● " + critical);
                    tvFindingHigh.setText("▲ " + high);
                    tvFindingMedium.setText("■ " + medium);
                    tvFindingLow.setText("▽ " + low);
                } else {
                    cardHallazgosSummary.setVisibility(android.view.View.GONE);
                }
            });
        });
    }

    // ── Propuesta ─────────────────────────────────────────────────────────────

    private void actualizarResumenPropuesta() {
        if (job == null || tvResumenPropuesta == null) return;
        JobRepository.get().getProposalDataAsync(job.id, data -> {
            ProposalEntity p               = (ProposalEntity) data[0];
            @SuppressWarnings("unchecked")
            List<ProposalOptionEntity> opciones = (List<ProposalOptionEntity>) data[1];
            SignatureEntity firma           = (SignatureEntity) data[2];

            if (p == null) {
                tvResumenPropuesta.setText("Sin propuesta");
            } else if (opciones.isEmpty()) {
                tvResumenPropuesta.setText("Sin propuesta");
            } else if (p.opcionAceptada != null) {
                double total = 0;
                for (ProposalOptionEntity o : opciones) {
                    if (o.nivel.equals(p.opcionAceptada)) { total = o.totalCalculado; break; }
                }
                // FASE 16: show coverage breakdown if applicable
                CoverageTypeEntity covJd = null;
                if (job.coverageTypeId != null && job.coverageTypeId > 0) {
                    try { covJd = LcDatabase.getInstance(this).coverageDao().getById(job.coverageTypeId); }
                    catch (Exception ignored) {}
                }
                com.lc.technician.util.CoverageCalculator.Result covRes =
                        com.lc.technician.util.CoverageCalculator.calcular(covJd, total);
                String resumen = "\u2713 Aceptada: " + p.opcionAceptada +
                                 " $" + String.format(java.util.Locale.US, "%,.0f", total);
                if (covRes.hasCoverage) {
                    resumen += " · Cliente paga: $" + String.format(java.util.Locale.US, "%,.0f", covRes.totalCliente);
                }
                tvResumenPropuesta.setText(resumen);
            } else {
                StringBuilder sb = new StringBuilder();
                for (ProposalOptionEntity o : opciones) {
                    if (sb.length() > 0) sb.append("  \u00b7  ");
                    sb.append(o.nivel).append(" $").append(String.format(java.util.Locale.US, "%,.0f", o.totalCalculado));
                }
                // FASE 16: show "desde $X cliente paga: $Y" for cheapest option if coverage
                CoverageTypeEntity covJd2 = null;
                if (job.coverageTypeId != null && job.coverageTypeId > 0) {
                    try { covJd2 = LcDatabase.getInstance(this).coverageDao().getById(job.coverageTypeId); }
                    catch (Exception ignored) {}
                }
                if (covJd2 != null && !"NONE".equals(covJd2.code) && covJd2.coveragePercentage > 0 && !opciones.isEmpty()) {
                    double minTotal = Double.MAX_VALUE;
                    for (ProposalOptionEntity o : opciones) if (o.totalCalculado < minTotal) minTotal = o.totalCalculado;
                    com.lc.technician.util.CoverageCalculator.Result r =
                            com.lc.technician.util.CoverageCalculator.calcular(covJd2, minTotal);
                    sb.append(" (desde $").append(String.format(java.util.Locale.US, "%,.0f", r.totalCliente)).append(" cliente)");
                }
                tvResumenPropuesta.setText(sb.toString());
            }

            TextView tvFirma = findViewById(R.id.tvFirmaInfo);
            if (tvFirma != null) {
                if (firma != null || (p != null && p.isLocked)) {
                    String firmaNombre = firma != null ? firma.nombreFirmante : "cliente";
                    tvFirma.setText("FIRMADA \u2713  " + firmaNombre);
                    tvFirma.setTextColor(0xFFFFFFFF);
                    tvFirma.setBackgroundColor(0xFF171717);
                    tvFirma.setPadding(16, 8, 16, 8);
                    tvFirma.setVisibility(View.VISIBLE);
                } else {
                    tvFirma.setVisibility(View.GONE);
                }
            }
        });
    }

    // ── Cliente ───────────────────────────────────────────────────────────────

    private void cargarInfoCliente() {
        View headerView = findViewById(R.id.tvClienteHeader);
        View cardView = findViewById(R.id.layoutClienteCard);

        if (headerView != null) headerView.setVisibility(View.GONE);
        if (cardView != null) cardView.setVisibility(View.GONE);

        if (job.customerId != null && job.customerId > 0) {
            JobRepository.get().getAllCustomersAsync(customers -> {
                for (CustomerEntity c : customers) {
                    if (c.id == job.customerId) {
                        bindClienteEnVista(headerView, cardView, c);
                        return;
                    }
                }
            });
            return;
        }

        // Fallback: job sin customerId → buscar por nombre
        if (job.cliente == null || job.cliente.trim().isEmpty()
                || job.cliente.equals("Cliente nuevo")) {
            return;
        }
        final String nombreBuscado = job.cliente.trim();
        JobRepository.get().getAllCustomersAsync(customers -> {
            for (CustomerEntity c : customers) {
                if (c.nombre != null && c.nombre.trim().equalsIgnoreCase(nombreBuscado)) {
                    // Re-vincular en memoria para que otros métodos lo usen
                    job.customerId = c.id;
                    bindClienteEnVista(headerView, cardView, c);
                    // Reintentar cargar servicios anteriores con el customerId resuelto
                    cargarServiciosAnteriores();
                    return;
                }
            }
        });
    }

    private void bindClienteEnVista(View headerView, View cardView, CustomerEntity c) {
        if (headerView != null) headerView.setVisibility(View.VISIBLE);
        if (cardView != null) cardView.setVisibility(View.VISIBLE);

        TextView tvNom = findViewById(R.id.tvClienteNombre);
        if (tvNom != null) tvNom.setText(c.nombre != null ? c.nombre : "");

        TextView tvTel = findViewById(R.id.tvClienteTelefono);
        if (tvTel != null) {
            if (c.telefono != null && !c.telefono.isEmpty()) {
                tvTel.setText(c.telefono);
                tvTel.setVisibility(View.VISIBLE);
            } else tvTel.setVisibility(View.GONE);
        }

        TextView tvEmail = findViewById(R.id.tvClienteEmail);
        if (tvEmail != null) {
            if (c.email != null && !c.email.isEmpty()) {
                tvEmail.setText(c.email);
                tvEmail.setVisibility(View.VISIBLE);
            } else tvEmail.setVisibility(View.GONE);
        }

        TextView tvNotas = findViewById(R.id.tvClienteNotas);
        if (tvNotas != null) {
            if (c.notas != null && !c.notas.isEmpty()) {
                tvNotas.setText(c.notas);
                tvNotas.setVisibility(View.VISIBLE);
            } else tvNotas.setVisibility(View.GONE);
        }

        TextView tvChip = findViewById(R.id.tvMembershipChip);
        if (tvChip != null) {
            android.content.SharedPreferences mp =
                    getSharedPreferences("lc_membership", MODE_PRIVATE);
            String mem = mp.getString("membership_" + c.id, "NONE");
            long expira = mp.getLong("warranty_expira_" + c.id, 0);
            boolean vencida = "WARRANTY".equals(mem) && expira > 0
                    && expira < System.currentTimeMillis();
            if ("NONE".equals(mem)) {
                tvChip.setVisibility(View.GONE);
            } else if (vencida) {
                tvChip.setText("WARRANTY VENCIDA");
                tvChip.setBackgroundColor(0xFFE91605);
                tvChip.setVisibility(View.VISIBLE);
            } else {
                tvChip.setText(mem);
                tvChip.setBackgroundColor("PREMIUM".equals(mem) ? 0xFF171717 : 0xFF62666A);
                tvChip.setVisibility(View.VISIBLE);
            }
            tvChip.setOnClickListener(vv -> mostrarDialogoMembership(c.id));
        }
    }

    private void mostrarDialogoMembership(long customerId) {
        String[] opciones = {"NONE", "BASIC", "PREMIUM", "WARRANTY"};
        android.content.SharedPreferences mp =
                getSharedPreferences("lc_membership", MODE_PRIVATE);
        String actual = mp.getString("membership_" + customerId, "NONE");
        int checked = 0;
        for (int i = 0; i < opciones.length; i++) {
            if (opciones[i].equals(actual)) { checked = i; break; }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Membresía / Garantía")
                .setSingleChoiceItems(opciones, checked, (d, which) -> {
                    mp.edit().putString("membership_" + customerId, opciones[which]).apply();
                    d.dismiss();
                    cargarInfoCliente();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void cargarServiciosAnteriores() {
        layoutServiciosAnteriores = findViewById(R.id.layoutServiciosAnteriores);
        tvSinServiciosAnteriores  = findViewById(R.id.tvSinServiciosAnteriores);
        if (layoutServiciosAnteriores == null) return;

        View headerServicios = findViewById(R.id.tvServiciosAnterioresHeader);

        if (job.customerId == null || job.customerId == 0) {
            // Sin cliente vinculado: ocultar TODA la sección
            if (headerServicios != null) headerServicios.setVisibility(View.GONE);
            if (layoutServiciosAnteriores != null) layoutServiciosAnteriores.setVisibility(View.GONE);
            if (tvSinServiciosAnteriores != null) tvSinServiciosAnteriores.setVisibility(View.GONE);
            return;
        }

        if (headerServicios != null) headerServicios.setVisibility(View.VISIBLE);
        if (layoutServiciosAnteriores != null) layoutServiciosAnteriores.setVisibility(View.VISIBLE);

        JobRepository.get().getJobsByCustomerAsync(job.customerId, job.id, jobs -> {
            layoutServiciosAnteriores.removeAllViews();
            if (jobs.isEmpty()) {
                if (tvSinServiciosAnteriores != null) tvSinServiciosAnteriores.setVisibility(View.VISIBLE);
                return;
            }
            if (tvSinServiciosAnteriores != null) tvSinServiciosAnteriores.setVisibility(View.GONE);

            float density = getResources().getDisplayMetrics().density;
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "d MMM yyyy, h:mm a", java.util.Locale.forLanguageTag("es"));

            for (Job j : jobs) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                int p = (int)(12 * density);
                card.setPadding(p, p, p, p);
                card.setBackgroundResource(R.drawable.bg_surface);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int)(8 * density);
                card.setLayoutParams(lp);

                TextView tvFecha = new TextView(this);
                tvFecha.setTextColor(0xFF9A9A9A);
                tvFecha.setTextSize(12f);
                long ts = j.updatedAt != null ? j.updatedAt : 0;
                String fechaStr;
                if (ts > 0) {
                    try {
                        fechaStr = fmt.format(new java.util.Date(ts));
                    } catch (Exception e) {
                        Log.e("JobDetail", "Error formateando fecha id=" + j.id + " ts=" + ts, e);
                        fechaStr = "Fecha desconocida";
                    }
                } else {
                    fechaStr = "Fecha desconocida";
                }
                tvFecha.setText(fechaStr);
                card.addView(tvFecha);

                LinearLayout fila = new LinearLayout(this);
                fila.setOrientation(LinearLayout.HORIZONTAL);
                fila.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                flp.topMargin = (int)(4 * density);
                fila.setLayoutParams(flp);

                TextView tvDesc = new TextView(this);
                tvDesc.setTextColor(0xFF191919);
                tvDesc.setTextSize(14f);
                tvDesc.setText(j.descripcion != null && !j.descripcion.isEmpty() ? j.descripcion : "Servicio");
                tvDesc.setMaxLines(2);
                tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvDesc.setLayoutParams(dlp);
                fila.addView(tvDesc);

                TextView chip = new TextView(this);
                chip.setTextSize(10f);
                chip.setTextColor(0xFFFFFFFF);
                chip.setPadding((int)(8*density),(int)(3*density),(int)(8*density),(int)(3*density));
                android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                chipBg.setCornerRadius(6f * density);
                chipBg.setColor(estadoColor(j.estado));
                chip.setBackground(chipBg);
                chip.setText(j.estado != null ? j.estado : "");
                chip.setTypeface(null, android.graphics.Typeface.BOLD);
                fila.addView(chip);

                card.addView(fila);

                final long targetId = j.id;
                card.setOnClickListener(v -> {
                    Intent i = new Intent(this, JobDetailActivity.class);
                    i.putExtra("job_id", targetId);
                    startActivity(i);
                });
                layoutServiciosAnteriores.addView(card);
            }
        });
    }

    private void cargarEquipmentHistory() {
        tvEquipmentHistoryHeader = findViewById(R.id.tvEquipmentHistoryHeader);
        layoutEquipmentHistory   = findViewById(R.id.layoutEquipmentHistory);
        tvSinEquipmentHistory    = findViewById(R.id.tvSinEquipmentHistory);
        if (layoutEquipmentHistory == null) return;

        if (job.equipmentId == null || job.equipmentId == 0) {
            // No equipment linked — keep section hidden
            return;
        }

        // Show section header
        if (tvEquipmentHistoryHeader != null) tvEquipmentHistoryHeader.setVisibility(View.VISIBLE);

        JobRepository.get().getJobsByEquipmentAsync(job.equipmentId, job.id, equipJobs -> {
            if (layoutEquipmentHistory == null) return;
            layoutEquipmentHistory.setVisibility(View.VISIBLE);
            layoutEquipmentHistory.removeAllViews();

            if (equipJobs.isEmpty()) {
                if (tvSinEquipmentHistory != null) tvSinEquipmentHistory.setVisibility(View.VISIBLE);
                return;
            }
            if (tvSinEquipmentHistory != null) tvSinEquipmentHistory.setVisibility(View.GONE);

            float density = getResources().getDisplayMetrics().density;
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "d MMM yyyy", java.util.Locale.forLanguageTag("es"));

            for (com.lc.technician.model.JobEntity j : equipJobs) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                int p = (int)(12 * density);
                card.setPadding(p, p, p, p);
                card.setBackgroundResource(R.drawable.bg_surface);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int)(6 * density);
                card.setLayoutParams(lp);

                TextView tvFecha = new TextView(this);
                tvFecha.setTextColor(0xFF9A9A9A);
                tvFecha.setTextSize(12f);
                String fechaStr = "Fecha desconocida";
                if (j.updatedAt != null && j.updatedAt > 0) {
                    try { fechaStr = fmt.format(new java.util.Date(j.updatedAt)); }
                    catch (Exception ignored) { }
                }
                tvFecha.setText(fechaStr);
                card.addView(tvFecha);

                TextView tvDesc = new TextView(this);
                tvDesc.setTextColor(0xFF191919);
                tvDesc.setTextSize(13f);
                tvDesc.setText(j.descripcion != null && !j.descripcion.isEmpty() ? j.descripcion : "Servicio");
                tvDesc.setMaxLines(2);
                tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = (int)(2 * density);
                tvDesc.setLayoutParams(dlp);
                card.addView(tvDesc);

                TextView tvEstadoChip = new TextView(this);
                tvEstadoChip.setTextSize(10f);
                tvEstadoChip.setTextColor(0xFFFFFFFF);
                tvEstadoChip.setPadding((int)(8*density),(int)(2*density),(int)(8*density),(int)(2*density));
                android.graphics.drawable.GradientDrawable chipBg =
                        new android.graphics.drawable.GradientDrawable();
                chipBg.setCornerRadius(6f * density);
                chipBg.setColor(estadoColor(j.estado));
                tvEstadoChip.setBackground(chipBg);
                tvEstadoChip.setText(j.estado != null ? j.estado : "");
                tvEstadoChip.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                elp.topMargin = (int)(4 * density);
                tvEstadoChip.setLayoutParams(elp);
                card.addView(tvEstadoChip);

                final long targetId = j.id;
                card.setOnClickListener(v -> {
                    Intent i = new Intent(this, JobDetailActivity.class);
                    i.putExtra("job_id", targetId);
                    startActivity(i);
                });
                layoutEquipmentHistory.addView(card);
            }
        });
    }

    private int estadoColor(String estado) {
        if (estado == null) return 0xFFE91605;
        switch (estado) {
            case Job.STATUS_DONE: case Job.STATUS_PAID: return 0xFF137333;
            case Job.STATUS_EN_ROUTE:           return 0xFF171717;
            case Job.STATUS_ARRIVED:            return 0xFF171717;
            case Job.STATUS_AWAITING_APPROVAL:  return 0xFF171717;
            case Job.STATUS_EN_REPARACION:      return 0xFFE91605;
            case Job.STATUS_PART_HOLD:          return 0xFF62666A;
            case Job.STATUS_ON_HOLD:            return 0xFF62666A;
            default:                            return 0xFF62666A;
        }
    }

    // ── Historial ─────────────────────────────────────────────────────────────

    private void cargarHistorial() {
        JobRepository.get().getEventsForJobAsync(job.id, fresh -> {
            if (historialAdapter == null) {
                eventos = fresh;
                historialAdapter = new JobEventAdapter(eventos);
                recyclerHistorial.setLayoutManager(new LinearLayoutManager(this));
                recyclerHistorial.setAdapter(historialAdapter);
            } else {
                eventos.clear();
                eventos.addAll(fresh);
                historialAdapter.notifyDataSetChanged();
            }
            if (eventos.isEmpty()) {
                tvSinHistorial.setVisibility(View.VISIBLE);
                recyclerHistorial.setVisibility(View.GONE);
            } else {
                tvSinHistorial.setVisibility(View.GONE);
                recyclerHistorial.setVisibility(View.VISIBLE);
            }
        });
    }

    // ── Avisar al cliente ─────────────────────────────────────────────────────

    private void mostrarOpcionesAvisarCliente() {
        String telefono = job.telefono != null ? job.telefono.trim() : "";
        String email = "";
        String nombreContacto = job.cliente != null ? job.cliente : "Cliente";

        if (contactos != null) {
            for (ContactEntity c : contactos) {
                if (c.esPrincipal) {
                    if (c.telefono != null && !c.telefono.isEmpty()) telefono = c.telefono.trim();
                    if (c.email != null && !c.email.isEmpty()) email = c.email.trim();
                    if (c.nombre != null && !c.nombre.isEmpty()) nombreContacto = c.nombre;
                    break;
                }
            }
            if (email.isEmpty() && !contactos.isEmpty()) {
                ContactEntity c = contactos.get(0);
                if (c.email != null && !c.email.isEmpty()) email = c.email.trim();
            }
        }

        final String fTelefono = telefono;
        final String fEmail    = email;
        final String mensaje   = construirMensajeAviso(nombreContacto);

        final com.lc.technician.sync.Listas.Lista canales = com.lc.technician.sync.Listas.lista(this, "share_channels.list",
                new String[][]{{"WHATSAPP", "WhatsApp"}, {"SMS", "SMS"}, {"EMAIL", "Email"}}, null);
        new AlertDialog.Builder(this)
                .setTitle("¿Cómo avisar al cliente?")
                .setItems(canales.labels, (dialog, which) -> {
                    switch (canales.ids[which]) {
                        case "WHATSAPP": abrirWhatsApp(fTelefono, mensaje); break;
                        case "SMS": abrirSMS(fTelefono, mensaje);      break;
                        default: abrirEmail(fEmail, mensaje);       break;
                    }
                })
                .show();
    }

    private String construirMensajeAviso(String nombre) {
        String dir  = job.direccion != null ? job.direccion : "";
        String hora = job.hora      != null ? job.hora      : "";
        String equi = job.equipo    != null ? job.equipo    : "el equipo";
        String est  = job.estado    != null ? job.estado    : "";
        switch (est) {
            case Job.STATUS_EN_ROUTE:
                return "Hola " + nombre + ", ya voy en camino. Llegar\u00e9 en breve a " + dir + ".";
            case Job.STATUS_ARRIVED:
                return "Hola " + nombre + ", ya llegu\u00e9 a " + dir + " y estoy listo para iniciar el servicio.";
            case Job.STATUS_AWAITING_APPROVAL:
                return "Hola " + nombre + ", le comparto el diagn\u00f3stico y la propuesta de servicio. Por favor confirme si desea proceder.";
            case Job.STATUS_EN_REPARACION:
                return "Hola " + nombre + ", ya inici\u00e9 la reparaci\u00f3n de " + equi + ". Le aviso cuando terminemos.";
            case Job.STATUS_PART_HOLD:
                return "Hola " + nombre + ", necesitamos esperar la llegada de un repuesto para continuar con el servicio. Le avisamos en cuanto tengamos novedades.";
            case Job.STATUS_DONE:
                return "Hola " + nombre + ", el servicio de " + equi + " ha sido completado. \u00a1Gracias por su preferencia!";
            default:
                return "Hola " + nombre + ", le confirmamos su cita de servicio para hoy a las " + hora + " en " + dir + ". Cualquier pregunta estamos a la orden.";
        }
    }

    // ── Tiempos del técnico ───────────────────────────────────────────────────


    /** Estados en los que el técnico deja el sitio: completar, necesita repuesto, cliente no está, cancelar y requiere gerente. */
    private static boolean esSalidaDelSitio(String estado) {
        return Job.STATUS_DONE.equals(estado) || Job.STATUS_PART_HOLD.equals(estado) || Job.STATUS_NOT_HOME.equals(estado)
                || Job.STATUS_CANCELLED.equals(estado) || Job.STATUS_MANAGER.equals(estado);
    }

    /** Registra la hora de un cambio de estado solo si esa orden aún no tiene ese evento (no pisa lo marcado a mano). */
    private void registrarTiempoAuto(final String tipo) {
        JobRepository.get().getLatestTimeEventAsync(job.id, tipo, existing -> {
            if (existing == null) {
                JobRepository.get().registrarTimeEvent(job.id, tipo, null, () -> {
                    cargarTiempos();
                    cargarHistorial();
                });
            }
        });
    }


    // ── Work Items ────────────────────────────────────────────────────────────

    private void cargarWorkItems() {
        if (layoutWorkItems == null) return;
        JobRepository.get().getWorkItemsAsync(job.id, items -> {
            layoutWorkItems.removeAllViews();
            if (items.isEmpty()) {
                tvSinWorkItems.setVisibility(View.VISIBLE);
                tvTotalWorkItems.setVisibility(View.GONE);
                return;
            }
            tvSinWorkItems.setVisibility(View.GONE);
            float density = getResources().getDisplayMetrics().density;
            double grandTotal = 0;
            for (WorkItemEntity w : items) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                int p = (int)(10 * density);
                card.setPadding(p, p, p, p);
                card.setBackgroundResource(R.drawable.bg_surface);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int)(6 * density);
                card.setLayoutParams(lp);

                // Chip tipo
                TextView chip = new TextView(this);
                chip.setText(w.tipo);
                chip.setTextSize(9f);
                chip.setTextColor(0xFFFFFFFF);
                chip.setTypeface(null, Typeface.BOLD);
                chip.setPadding((int)(5*density),(int)(2*density),(int)(5*density),(int)(2*density));
                android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                chipBg.setCornerRadius(4f * density);
                int chipColor;
                switch (w.tipo != null ? w.tipo : "") {
                    case WorkItemEntity.TYPE_LABOR:   chipColor = 0xFFE91605; break;
                    case WorkItemEntity.TYPE_PART:    chipColor = 0xFF171717; break;
                    case WorkItemEntity.TYPE_SERVICE: chipColor = 0xFF62666A; break;
                    case WorkItemEntity.TYPE_FEE:     chipColor = 0xFFE91605; break;
                    default:                          chipColor = 0xFF171717; break;
                }
                chipBg.setColor(chipColor);
                chip.setBackground(chipBg);
                LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                chlp.rightMargin = (int)(8 * density);
                chip.setLayoutParams(chlp);
                card.addView(chip);

                // Center
                LinearLayout center = new LinearLayout(this);
                center.setOrientation(LinearLayout.VERTICAL);
                center.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvDesc = new TextView(this);
                tvDesc.setText(w.descripcion != null ? w.descripcion : "");
                tvDesc.setTextColor(0xFF191919);
                tvDesc.setTextSize(13f);
                center.addView(tvDesc);

                double cant  = w.cantidad       != null ? w.cantidad       : 1.0;
                double price = w.precioUnitario != null ? w.precioUnitario : 0.0;
                TextView tvMeta = new TextView(this);
                tvMeta.setText(String.format(java.util.Locale.US, "%.0f \u00d7 $%.2f", cant, price));
                tvMeta.setTextColor(0xFF767676);
                tvMeta.setTextSize(11f);
                center.addView(tvMeta);
                card.addView(center);

                // Total
                double t = w.total != null ? w.total : 0.0;
                TextView tvTot = new TextView(this);
                tvTot.setText(String.format(java.util.Locale.US, "$%.2f", t));
                tvTot.setTextColor(0xFF191919);
                tvTot.setTextSize(13f);
                tvTot.setTypeface(null, Typeface.BOLD);
                card.addView(tvTot);

                final WorkItemEntity wFinal = w;
                card.setOnClickListener(v -> mostrarDialogoWorkItem(wFinal));
                card.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Eliminar item")
                            .setMessage("¿Eliminar \"" + wFinal.descripcion + "\"? Quedará anulado en el historial.")
                            .setPositiveButton("Eliminar", (d, wh) ->
                                    JobRepository.get().deleteWorkItemAsync(wFinal, this::cargarWorkItems))
                            .setNegativeButton("Cancelar", null)
                            .show();
                    return true;
                });

                layoutWorkItems.addView(card);
                grandTotal += t;
            }
            tvTotalWorkItems.setText(String.format(java.util.Locale.US, "Total: $%.2f", grandTotal));
            tvTotalWorkItems.setVisibility(View.VISIBLE);
        });
    }

    private void mostrarDialogoAgregarWorkItem() {
        mostrarDialogoWorkItem(null);
    }

    /** Agregar (existente == null) o editar una partida de trabajo con el mismo diálogo. */
    private void mostrarDialogoWorkItem(WorkItemEntity existente) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(48, 16, 48, 0);

        // tipos de partida editables en Control (settings work_item_types.list): se muestra el texto, se guarda el id
        final com.lc.technician.sync.Listas.Lista tiposL = com.lc.technician.sync.Listas.lista(this, "work_item_types.list",
                new String[][]{{"LABOR", "Mano de obra"}, {"PART", "Repuesto"}, {"SERVICE", "Servicio"}, {"FEE", "Cargo"}, {"OTHER", "Otro"}}, null);
        final String[] tiposIds = tiposL.ids;
        android.widget.Spinner spinnerTipo = new android.widget.Spinner(this);
        spinnerTipo.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, tiposL.labels));
        container.addView(spinnerTipo);

        EditText etDesc = new EditText(this);
        etDesc.setHint("Descripción");
        container.addView(etDesc);

        EditText etCant = new EditText(this);
        etCant.setHint("Cantidad");
        etCant.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etCant.setText("1");
        container.addView(etCant);

        EditText etPrecio = new EditText(this);
        etPrecio.setHint("Precio unitario ($)");
        etPrecio.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etPrecio.setText("0");
        container.addView(etPrecio);

        TextView tvSubtotal = new TextView(this);
        tvSubtotal.setTextColor(0xFF171717);
        tvSubtotal.setTextSize(13f);
        tvSubtotal.setText("Subtotal: $0.00");
        container.addView(tvSubtotal);

        android.text.TextWatcher liveCalc = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                try {
                    double cant  = Double.parseDouble(etCant.getText().toString());
                    double price = Double.parseDouble(etPrecio.getText().toString());
                    tvSubtotal.setText(String.format(java.util.Locale.US, "Subtotal: $%.2f", cant * price));
                } catch (Exception ignored) { tvSubtotal.setText("Subtotal: —"); }
            }
        };
        etCant.addTextChangedListener(liveCalc);
        etPrecio.addTextChangedListener(liveCalc);
        if (existente != null) {
            int ti = tiposL.indexOf(existente.tipo);
            if (ti >= 0) spinnerTipo.setSelection(ti);
            etDesc.setText(existente.descripcion);
            if (existente.cantidad != null) etCant.setText(String.valueOf(existente.cantidad));
            if (existente.precioUnitario != null) etPrecio.setText(String.valueOf(existente.precioUnitario));
        }

        new AlertDialog.Builder(this)
                .setTitle(existente == null ? "Agregar ítem de trabajo" : "Editar ítem de trabajo")
                .setView(container)
                .setPositiveButton("Guardar", (d, w) -> {
                    String tipo = tiposIds[spinnerTipo.getSelectedItemPosition()];
                    String desc = etDesc.getText().toString().trim();
                    if (desc.isEmpty()) {
                        Toast.makeText(this, "Descripción requerida", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double cant, precio;
                    try { cant  = Double.parseDouble(etCant.getText().toString()); }  catch (Exception e) { cant  = 1; }
                    try { precio = Double.parseDouble(etPrecio.getText().toString()); } catch (Exception e) { precio = 0; }
                    if (cant <= 0) {
                        Toast.makeText(this, "La cantidad debe ser mayor a 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (precio < 0) {
                        Toast.makeText(this, "El precio no puede ser negativo", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (existente != null) {
                        existente.tipo           = tipo;
                        existente.descripcion    = desc;
                        existente.cantidad       = cant;
                        existente.precioUnitario = precio;
                        existente.unidad         = WorkItemEntity.TYPE_LABOR.equals(tipo) ? "Hour" : "Each";
                        JobRepository.get().updateWorkItemAsync(existente, this::cargarWorkItems);
                        return;
                    }
                    long now = System.currentTimeMillis();
                    WorkItemEntity wi = new WorkItemEntity();
                    wi.jobId          = job.id;
                    wi.tipo           = tipo;
                    wi.descripcion    = desc;
                    wi.cantidad       = cant;
                    wi.precioUnitario = precio;
                    wi.total          = cant * precio;
                    wi.totalLinea     = cant * precio;
                    wi.unidad         = WorkItemEntity.TYPE_LABOR.equals(tipo) ? "Hour" : "Each";
                    wi.timestamp      = now;
                    wi.createdAt      = now;
                    wi.updatedAt      = now;
                    JobRepository.get().addWorkItemAsync(wi, id -> cargarWorkItems());
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ── Repuestos ─────────────────────────────────────────────────────────────

    private void cargarRepuestosUsados() {
        JobRepository.get().getJobPartsAsync(job.id, jobParts -> {
            layoutRepuestos.removeAllViews();
            if (jobParts.isEmpty()) {
                tvSinRepuestos.setVisibility(View.VISIBLE);
            } else {
                tvSinRepuestos.setVisibility(View.GONE);
                for (JobPartEntity jp : jobParts) {
                    View row = getLayoutInflater().inflate(R.layout.item_job_part, layoutRepuestos, false);
                    ((TextView) row.findViewById(R.id.tvJobPartNombre)).setText("Cargando…");
                    ((TextView) row.findViewById(R.id.tvJobPartCantidad))
                            .setText(jp.cantidad + "x  $" + String.format(java.util.Locale.US, "%.2f", jp.precioUnitario));
                    row.setOnLongClickListener(v -> {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Eliminar repuesto")
                                .setMessage("¿Quitar este repuesto del trabajo?")
                                .setPositiveButton("Eliminar", (d, w) ->
                                        JobRepository.get().removeJobPartAsync(jp, this::cargarRepuestosUsados))
                                .setNegativeButton("Cancelar", null)
                                .show();
                        return true;
                    });
                    JobRepository.get().getAllPartsAsync(allParts -> {
                        for (PartEntity p : allParts) {
                            if (p.id == jp.partId) {
                                ((TextView) row.findViewById(R.id.tvJobPartNombre)).setText(p.nombre);
                                break;
                            }
                        }
                    });
                    layoutRepuestos.addView(row);
                }
            }
        });
    }

    private void mostrarDialogoAgregarRepuesto() {
        JobRepository.get().getAllPartsAsync(partes -> {
            if (partes.isEmpty()) {
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
                b.setTitle("Sin repuestos")
                 .setMessage("No hay repuestos en el inventario. Agrega repuestos en Perfil → Mis repuestos.")
                 .setPositiveButton("OK", null)
                 .show();
                return;
            }
            String[] nombres = new String[partes.size()];
            for (int i = 0; i < partes.size(); i++) {
                PartEntity p = partes.get(i);
                nombres[i] = p.nombre + "  (stock: " + p.stockActual + ")";
            }
            final int[] selIdx = {0};
            android.widget.EditText etCantidad = new android.widget.EditText(this);
            etCantidad.setHint("Cantidad");
            etCantidad.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etCantidad.setText("1");

            android.widget.LinearLayout container = new android.widget.LinearLayout(this);
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setPadding(48, 16, 48, 0);

            android.widget.Spinner spinner = new android.widget.Spinner(this);
            android.widget.ArrayAdapter<String> adp = new android.widget.ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_dropdown_item, nombres);
            spinner.setAdapter(adp);
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { selIdx[0] = pos; }
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
            container.addView(spinner);
            container.addView(etCantidad);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Agregar repuesto")
                    .setView(container)
                    .setPositiveButton("Agregar", (d, w) -> {
                        PartEntity selected = partes.get(selIdx[0]);
                        int cant;
                        try { cant = Integer.parseInt(etCantidad.getText().toString().trim()); }
                        catch (Exception e) { cant = 1; }
                        if (cant <= 0) cant = 1;
                        final int cantFinal = cant;
                        if (selected.stockActual <= 0) {
                            // Stock=0: require confirmation before adding
                            new android.app.AlertDialog.Builder(this)
                                    .setTitle("Sin stock")
                                    .setMessage("\"" + selected.nombre + "\" no está en inventario. "
                                            + "¿Solicitar al proveedor y agregar de todas formas?")
                                    .setPositiveButton("Solicitar y agregar", (d2, w2) ->
                                            JobRepository.get().addJobPartAsync(
                                                    job.id, selected.id, cantFinal,
                                                    selected.costo, selected.precio,
                                                    "Pendiente de proveedor",
                                                    id -> cargarRepuestosUsados()))
                                    .setNegativeButton("Cancelar", null)
                                    .show();
                        } else {
                            JobRepository.get().addJobPartAsync(
                                    job.id, selected.id, cantFinal,
                                    selected.costo, selected.precio,
                                    null,
                                    id -> cargarRepuestosUsados());
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    /** «Tiempos registrados»: solo lectura, y solo si hay al menos una hora. Las horas las deja el cambio de estado. */
    private void cargarTiempos() {
        if (layoutTiemposRegistrados == null || tvTiemposRegistrados == null) return;
        JobRepository.get().calcularTiemposAsync(job.id, stats -> {
            java.util.List<String> partes = new java.util.ArrayList<>();
            if (stats.onMyWay != null) partes.add("En camino: " + FMT_HORA.format(new Date(stats.onMyWay.timestamp)));
            if (stats.arrived != null) partes.add("Llegué: " + FMT_HORA.format(new Date(stats.arrived.timestamp)));
            if (stats.offSite != null) {
                partes.add((Job.esCompletado(job.estado) ? "Completado: " : "Salida del sitio: ") + FMT_HORA.format(new Date(stats.offSite.timestamp)));
            }
            layoutTiemposRegistrados.setVisibility(partes.isEmpty() ? View.GONE : View.VISIBLE);
            tvTiemposRegistrados.setText(android.text.TextUtils.join("  |  ", partes));
        });
    }


    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long minutes) {
        if (minutes < 0) return "—";
        if (minutes < 60) return minutes + " min";
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? (h + "h") : (h + "h " + m + "min");
    }

    private void abrirWhatsApp(String telefono, String mensaje) {
        if (telefono.isEmpty()) {
            Toast.makeText(this, "No hay teléfono registrado", Toast.LENGTH_SHORT).show();
            return;
        }
        String num = telefono.replaceAll("[^\\d+]", "");

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(mensaje)));
        intent.setPackage("com.whatsapp");
        try {
            startActivity(intent);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por WhatsApp");
            cargarHistorial();
            return;
        } catch (android.content.ActivityNotFoundException e1) { /* siguiente */ }

        intent.setPackage("com.whatsapp.w4b");
        try {
            startActivity(intent);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por WhatsApp");
            cargarHistorial();
            return;
        } catch (android.content.ActivityNotFoundException e2) { /* siguiente */ }

        try {
            Intent i3 = new Intent(Intent.ACTION_VIEW);
            i3.setData(Uri.parse("whatsapp://send?phone=" + num + "&text=" + Uri.encode(mensaje)));
            startActivity(i3);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por WhatsApp");
            cargarHistorial();
            return;
        } catch (android.content.ActivityNotFoundException e3) { /* siguiente */ }

        try {
            Intent i4 = new Intent(Intent.ACTION_VIEW);
            i4.setData(Uri.parse("https://api.whatsapp.com/send?phone=" + num + "&text=" + Uri.encode(mensaje)));
            startActivity(i4);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por WhatsApp");
            cargarHistorial();
        } catch (Exception e4) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }

    private void abrirSMS(String telefono, String mensaje) {
        if (telefono.isEmpty()) {
            Toast.makeText(this, "No hay teléfono registrado", Toast.LENGTH_SHORT).show();
            return;
        }
        String num = telefono.replaceAll("[^\\d+]", "");

        try {
            Intent i = new Intent(Intent.ACTION_SENDTO);
            i.setData(Uri.parse("smsto:" + num));
            i.putExtra("sms_body", mensaje);
            startActivity(i);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por SMS");
            cargarHistorial();
            return;
        } catch (Exception e) { }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse("sms:" + num));
            i.putExtra("sms_body", mensaje);
            startActivity(i);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por SMS");
            cargarHistorial();
            return;
        } catch (Exception e) { }

        try {
            Intent i = new Intent(Intent.ACTION_SENDTO);
            i.setData(Uri.parse("sms:" + num));
            i.putExtra("sms_body", mensaje);
            startActivity(i);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por SMS");
            cargarHistorial();
            return;
        } catch (Exception e) { }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setType("vnd.android-dir/mms-sms");
            i.putExtra("address", num);
            i.putExtra("sms_body", mensaje);
            startActivity(i);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por SMS");
            cargarHistorial();
            return;
        } catch (Exception e) { }

        Toast.makeText(this, "No hay app de mensajes disponible", Toast.LENGTH_LONG).show();
    }

    private void abrirEmail(String email, String mensaje) {
        if (email.isEmpty()) {
            Toast.makeText(this, "No hay email registrado", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:" + email));
            intent.putExtra(Intent.EXTRA_SUBJECT, "Aviso de servicio — " + (job.cliente != null ? job.cliente : ""));
            intent.putExtra(Intent.EXTRA_TEXT, mensaje);
            startActivity(intent);
            JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por Email");
            cargarHistorial();
        } catch (android.content.ActivityNotFoundException e) {
            try {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("message/rfc822");
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
                intent.putExtra(Intent.EXTRA_SUBJECT, "Aviso de servicio — " + (job.cliente != null ? job.cliente : ""));
                intent.putExtra(Intent.EXTRA_TEXT, mensaje);
                startActivity(Intent.createChooser(intent, "Enviar email"));
                JobRepository.get().agregarEvento(job.id, "AVISO", "Aviso enviado al cliente por Email");
                cargarHistorial();
            } catch (Exception e2) {
                Toast.makeText(this, "No hay app de correo instalada. Instala Gmail u otra.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error al abrir correo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Diálogo de bloqueo ────────────────────────────────────────────────────

    private void mostrarDialogoBloqueoCierre(String mensaje) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int px24 = (int) (24 * getResources().getDisplayMetrics().density);
        int px16 = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(px24, px24, px24, px16);
        layout.setBackgroundColor(0xFFFFFFFF);

        TextView titulo = new TextView(this);
        titulo.setText("Antes de cerrar");
        titulo.setTextSize(20f);
        titulo.setTypeface(null, Typeface.BOLD);
        titulo.setTextColor(0xFF191919);
        LinearLayout.LayoutParams tituloParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tituloParams.bottomMargin = px16;
        titulo.setLayoutParams(tituloParams);
        layout.addView(titulo);

        TextView cuerpo = new TextView(this);
        cuerpo.setText(mensaje);
        cuerpo.setTextSize(15f);
        cuerpo.setTextColor(0xFF191919);
        LinearLayout.LayoutParams cuerpoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cuerpoParams.bottomMargin = px24;
        cuerpo.setLayoutParams(cuerpoParams);
        layout.addView(cuerpo);

        Button btnEntendido = new Button(this);
        btnEntendido.setText("ENTENDIDO");
        btnEntendido.setAllCaps(true);
        btnEntendido.setTextColor(0xFFFFFFFF);
        btnEntendido.setTextSize(14f);
        btnEntendido.setTypeface(null, Typeface.BOLD);
        btnEntendido.setPadding(0, px16, 0, px16);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFE91605);
        btnBg.setCornerRadius(56f * getResources().getDisplayMetrics().density);
        btnEntendido.setBackground(btnBg);
        btnEntendido.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(btnEntendido);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        btnEntendido.setOnClickListener(vv -> dialog.dismiss());
        dialog.show();
    }

    // ── Técnico asignado ─────────────────────────────────────────────────────

    private String extraerTecnico(String notas) {
        if (notas == null || notas.isEmpty()) return "Sin asignar";
        for (String line : notas.split("\n")) {
            if (line.startsWith("TECNICO:")) {
                String nombre = line.substring(8).trim();
                return nombre.isEmpty() ? "Sin asignar" : nombre;
            }
        }
        return "Sin asignar";
    }

    private String notasParaEditor(String notas) {
        if (notas == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : notas.split("\n", -1)) {
            if (!line.startsWith("TECNICO:")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String buildNotas(String tecnico, String editorText) {
        String base = editorText != null ? editorText.trim() : "";
        if ("Sin asignar".equals(tecnico) || tecnico == null || tecnico.isEmpty()) {
            return base;
        }
        return "TECNICO:" + tecnico + (base.isEmpty() ? "" : "\n" + base);
    }

    private void actualizarTecnicoUI(String tecnico) {
        if (tvTecnicoAsignado == null) return;
        tvTecnicoAsignado.setText(tecnico);
        boolean sinAsignar = "Sin asignar".equals(tecnico);
        tvTecnicoAsignado.setTextColor(sinAsignar ? 0xFF9A9A9A : 0xFF191919);
    }

    private void mostrarDialogoTecnico() {
        // Build list: current session user first, then seed options
        java.util.List<String> techList = new java.util.ArrayList<>();
        String sessionUser = SessionManager.get(this).getTechnicianName();
        if (sessionUser != null && !sessionUser.isEmpty()) techList.add(sessionUser);
        for (String n : new String[]{
                com.lc.technician.model.Technician.NOMBRE,
                "Juan Pérez", "Carlos Méndez", "María Rodríguez"}) {
            if (!techList.contains(n)) techList.add(n);
        }
        techList.add("Otro...");
        techList.add("Sin asignar");
        String[] opciones = techList.toArray(new String[0]);

        new AlertDialog.Builder(this)
            .setTitle("Técnico asignado")
            .setItems(opciones, (dialog, which) -> {
                String seleccion = opciones[which];
                if ("Otro...".equals(seleccion)) {
                    android.widget.EditText input = new android.widget.EditText(this);
                    input.setHint("Nombre del técnico");
                    input.setPadding(48, 24, 48, 24);
                    new AlertDialog.Builder(this)
                        .setTitle("Técnico personalizado")
                        .setView(input)
                        .setPositiveButton("Guardar", (d, w) -> {
                            String nombre = input.getText().toString().trim();
                            if (!nombre.isEmpty()) guardarTecnicoAsignado(nombre);
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                } else {
                    guardarTecnicoAsignado(seleccion);
                }
            })
            .show();
    }

    private void guardarTecnicoAsignado(String nombre) {
        if (com.lc.technician.sync.LabSession.activa(this)) {
            // Con sincronización v2 la asignación es del centro de mando (el servidor la ignora si viene del
            // teléfono): no se cambia nada localmente ni se reescriben las notas.
            Toast.makeText(this, "La asignación la hace el centro de mando", Toast.LENGTH_LONG).show();
            return;
        }
        String actor = SessionManager.get(this).getTechnicianName();
        boolean sinAsignar = "Sin asignar".equals(nombre) || nombre == null || nombre.isEmpty();

        if (sinAsignar) {
            job.technicianId = null;
            actualizarTecnicoUI("Sin asignar");
            JobRepository.get().unassignTechnician(job.id, actor, null);
        } else {
            job.technicianId = nombre;
            actualizarTecnicoUI(nombre);
            JobRepository.get().assignTechnician(job.id, nombre, actor, null);
        }

        // Keep legacy TECNICO: in notas for backward compat with existing data
        android.widget.EditText etNotas = findViewById(R.id.detalleNotas);
        String editorText = etNotas != null ? etNotas.getText().toString() : notasParaEditor(job.notas);
        String fullNotas = buildNotas(sinAsignar ? "Sin asignar" : nombre, editorText);
        if (etNotas != null) etNotas.setText(notasParaEditor(fullNotas));
        // mismo guardado seguro que al salir: base = lo que mostró el editor (no pisa notas recibidas)
        JobRepository.get().persistNotas(job.id, fullNotas, notasVistas, versionVista);
        notasVistas = fullNotas;

        if (!sinAsignar) {
            try {
                com.lc.technician.util.NotificationHelper.notificarJobAsignado(
                    this, job.id,
                    job.cliente != null ? job.cliente : "",
                    job.hora != null ? job.hora : "",
                    false
                );
            } catch (Exception ex) {
                android.util.Log.e("JobDetail", "Error notificando", ex);
            }
        }
        Toast.makeText(this, sinAsignar ? "Desasignado" : "Técnico: " + nombre, Toast.LENGTH_SHORT).show();
    }

    // FASE 13: Solicitud de repuestos desde JobDetail
    private void mostrarDialogoSolicitudRepuesto() {
        JobRepository.get().getAllPartsAsync(partes -> {
            if (partes.isEmpty()) {
                Toast.makeText(this, "No hay repuestos en inventario", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] nombres = new String[partes.size()];
            for (int i = 0; i < partes.size(); i++) {
                PartEntity p = partes.get(i);
                nombres[i] = p.nombre + "  (stock: " + p.stockActual + ")";
            }
            final int[] selIdx = {0};

            android.widget.LinearLayout container = new android.widget.LinearLayout(this);
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setPadding(48, 16, 48, 0);

            android.widget.Spinner spinner = new android.widget.Spinner(this);
            android.widget.ArrayAdapter<String> adp = new android.widget.ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_dropdown_item, nombres);
            spinner.setAdapter(adp);
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { selIdx[0] = pos; }
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });

            android.widget.EditText etCantidad = new android.widget.EditText(this);
            etCantidad.setHint("Cantidad");
            etCantidad.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etCantidad.setText("1");

            android.widget.EditText etNotas = new android.widget.EditText(this);
            etNotas.setHint("Notas (opcional)");
            etNotas.setMinLines(2);

            android.widget.TextView tvTotal = new android.widget.TextView(this);
            tvTotal.setText("Total estimado: $0.00");
            tvTotal.setPadding(0, 12, 0, 0);

            container.addView(spinner);
            container.addView(etCantidad);
            container.addView(etNotas);
            container.addView(tvTotal);

            android.text.TextWatcher watcher = new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                    int cant = 1;
                    try { cant = Integer.parseInt(etCantidad.getText().toString().trim()); } catch (Exception e) { cant = 1; }
                    if (cant <= 0) cant = 1;
                    PartEntity sel = partes.get(selIdx[0]);
                    double total = cant * sel.precio;
                    tvTotal.setText("Total estimado: $" + String.format(java.util.Locale.US, "%.2f", total));
                }
                public void afterTextChanged(android.text.Editable s) {}
            };
            etCantidad.addTextChangedListener(watcher);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Solicitar Repuestos")
                    .setView(container)
                    .setPositiveButton("Enviar Solicitud", (d, w) -> {
                        PartEntity selected = partes.get(selIdx[0]);
                        int cant;
                        try { cant = Integer.parseInt(etCantidad.getText().toString().trim()); }
                        catch (Exception e) { cant = 1; }
                        if (cant <= 0) cant = 1;
                        final int cantFinal = cant;
                        String notas = etNotas.getText().toString().trim();
                        double total = cantFinal * selected.precio;
                        String itemsJson = "[{\"partId\":" + selected.id +
                                ",\"qty\":" + cantFinal +
                                ",\"nombre\":\"" + selected.nombre.replace("\"", "\\\"") +
                                "\",\"precio\":" + selected.precio + "}]";
                        PartsRequestEntity req = new PartsRequestEntity();
                        req.jobId = job.id;
                        req.technicianId = 1;
                        req.status = PartsRequestEntity.STATUS_PENDING;
                        req.itemsJson = itemsJson;
                        req.totalEstimated = total;
                        req.notes = notas.isEmpty() ? null : notas;
                        req.createdAt = System.currentTimeMillis();
                        req.updatedAt = System.currentTimeMillis();
                        JobRepository.get().addPartsRequestAsync(req, id -> {
                            Toast.makeText(this, "Solicitud enviada", Toast.LENGTH_SHORT).show();
                            cargarSolicitudesRepuesto();
                        });
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    private void cargarSolicitudesRepuesto() {
        JobRepository.get().getPartsRequestsByJobAsync(job.id, solicitudes -> {
            layoutSolicitudes.removeAllViews();
            if (solicitudes.isEmpty()) {
                tvSinSolicitudes.setVisibility(View.VISIBLE);
            } else {
                tvSinSolicitudes.setVisibility(View.GONE);
                for (PartsRequestEntity req : solicitudes) {
                    android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setPadding(0, 10, 0, 10);

                    android.widget.TextView badge = new android.widget.TextView(this);
                    int badgeColor = PartsRequestEntity.STATUS_APPROVED.equals(req.status) ? 0xFF171717 :
                                     PartsRequestEntity.STATUS_REJECTED.equals(req.status) ? 0xFFE91605 : 0xFF62666A;
                    badge.setBackgroundColor(badgeColor);
                    badge.setTextColor(0xFFFFFFFF);
                    badge.setText(req.status);
                    badge.setTextSize(11);
                    badge.setPadding(14, 4, 14, 4);

                    android.widget.TextView info = new android.widget.TextView(this);
                    info.setText("  Solicitud #" + req.id + " — $" +
                            String.format(java.util.Locale.US, "%.2f", req.totalEstimated));
                    info.setTextSize(13);

                    row.addView(badge);
                    row.addView(info);
                    layoutSolicitudes.addView(row);
                }
            }
        });
    }

    // ── FASE 15: Coverage ──────────────────────────────────────────────────────

    private void cargarCobertura() {
        if (job == null) return;
        new Thread(() -> {
            CoverageTypeEntity cov = null;
            if (job.coverageTypeId != null && job.coverageTypeId > 0) {
                cov = LcDatabase.getInstance(this).coverageDao().getById(job.coverageTypeId);
            }
            final CoverageTypeEntity coverage = cov;
            runOnUiThread(() -> {
                if (tvCoberturaActual == null) return;
                if (coverage == null || "NONE".equals(coverage.code)) {
                    tvCoberturaActual.setText("Sin cobertura");
                    tvCoberturaDetalle.setVisibility(View.GONE);
                    tvCoberturaAuth.setVisibility(View.GONE);
                } else {
                    tvCoberturaActual.setText(coverage.displayName);
                    String detalle = "Deducible: $" + String.format(Locale.US, "%.0f", coverage.deductibleAmount)
                            + "  ·  Cubre: " + String.format(Locale.US, "%.0f", coverage.coveragePercentage) + "%";
                    if (coverage.requiresApproval) detalle += "  ·  Requiere aprobación";
                    tvCoberturaDetalle.setText(detalle);
                    tvCoberturaDetalle.setVisibility(View.VISIBLE);
                    if (job.coverageAuthNumber != null && !job.coverageAuthNumber.isEmpty()) {
                        tvCoberturaAuth.setText("Auth: " + job.coverageAuthNumber);
                        tvCoberturaAuth.setVisibility(View.VISIBLE);
                    } else {
                        tvCoberturaAuth.setVisibility(View.GONE);
                    }
                }
            });
        }).start();
    }

    private void mostrarDialogoAsignarCobertura() {
        new Thread(() -> {
            List<CoverageTypeEntity> tipos = LcDatabase.getInstance(this).coverageDao().getAllActive();
            runOnUiThread(() -> {
                if (tipos.isEmpty()) {
                    Toast.makeText(this, "No hay tipos de cobertura disponibles", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] nombres = new String[tipos.size()];
                for (int i = 0; i < tipos.size(); i++) nombres[i] = tipos.get(i).displayName;

                android.widget.Spinner spinner = new android.widget.Spinner(this);
                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, nombres);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);

                // Pre-select current
                if (job.coverageTypeId != null) {
                    for (int i = 0; i < tipos.size(); i++) {
                        if (tipos.get(i).id == job.coverageTypeId) { spinner.setSelection(i); break; }
                    }
                }

                EditText etAuth = new EditText(this);
                etAuth.setHint("Número de autorización (opcional)");
                if (job.coverageAuthNumber != null) etAuth.setText(job.coverageAuthNumber);

                LinearLayout layout = new LinearLayout(this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(48, 16, 48, 8);
                layout.addView(spinner);
                layout.addView(etAuth);

                new AlertDialog.Builder(this)
                        .setTitle("Asignar Cobertura")
                        .setView(layout)
                        .setPositiveButton("Guardar", (d, w) -> {
                            int idx = spinner.getSelectedItemPosition();
                            CoverageTypeEntity sel = tipos.get(idx);
                            job.coverageTypeId = sel.id;
                            job.coverageAuthNumber = etAuth.getText().toString().trim();
                            new Thread(() -> {
                                LcDatabase.getInstance(this).jobDao().insert(job.toEntity());
                                runOnUiThread(this::cargarCobertura);
                            }).start();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            });
        }).start();
    }

    private void procesarTarjetaEscaneada(String cardNumber) {
        new Thread(() -> {
            try {
                MembershipCardDao dao = LcDatabase.getInstance(this).membershipCardDao();
                MembershipCardEntity card = dao.getByCardNumber(cardNumber);
                if (card == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Tarjeta no encontrada: " + cardNumber, Toast.LENGTH_LONG).show());
                    return;
                }

                // Actualizar cobertura del job si aplica
                if (card.coverageTypeId != null) {
                    job.coverageTypeId = card.coverageTypeId;
                    LcDatabase.getInstance(this).jobDao().insert(job.toEntity());
                }

                // Marcar servicio en la tarjeta
                long now = System.currentTimeMillis();
                dao.marcarServicio(card.id, now);

                // FASE 26: Registrar check-in en historial
                try {
                    com.lc.technician.model.MembershipCheckInEntity checkIn =
                            new com.lc.technician.model.MembershipCheckInEntity();
                    checkIn.cardId = card.id;
                    checkIn.jobId = job != null ? job.id : null;
                    checkIn.checkInDate = now;
                    checkIn.technicianName = SessionManager.get(this).getTechnicianName();
                    checkIn.notes = "Check-in tarjeta " + card.cardNumber;
                    checkIn.createdAt = now;
                    LcDatabase.getInstance(this).membershipCheckInDao().insert(checkIn);
                } catch (Exception exCheckin) {
                    android.util.Log.e("JobDetail", "Error registrando check-in", exCheckin);
                }

                // Obtener info de cobertura
                String coverageName = "";
                double deductible = 0;
                double coveragePct = 0;
                if (card.coverageTypeId != null) {
                    CoverageTypeEntity cov = LcDatabase.getInstance(this)
                            .coverageDao().getById(card.coverageTypeId);
                    if (cov != null) {
                        coverageName = cov.displayName;
                        deductible = cov.deductibleAmount;
                        coveragePct = cov.coveragePercentage;
                    }
                }

                final String covNameFinal = coverageName;
                final double dedFinal = deductible;
                final double pctFinal = coveragePct;

                runOnUiThread(() -> mostrarDialogoCheckIn(card, covNameFinal, dedFinal, pctFinal));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void mostrarDialogoCheckIn(MembershipCardEntity card, String covName,
                                       double deductible, double coveragePct) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM yyyy",
                java.util.Locale.forLanguageTag("es"));

        boolean vencida = card.expiryDate < System.currentTimeMillis();

        String estadoColor = MembershipCardEntity.STATUS_ACTIVE.equals(card.status) ? "✓" :
                MembershipCardEntity.STATUS_SUSPENDED.equals(card.status) ? "!" : "✕";
        String pagoColor = MembershipCardEntity.PAYMENT_PAID.equals(card.paymentStatus) ? "✓" :
                MembershipCardEntity.PAYMENT_OVERDUE.equals(card.paymentStatus) ? "✕" : "!";

        StringBuilder sb = new StringBuilder();
        sb.append("📋 Tarjeta: ").append(card.cardNumber).append("\n\n");
        sb.append("🏆 Membresía: ").append(covName.isEmpty() ? "Sin tipo" : covName).append("\n");
        sb.append(estadoColor).append(" Estado: ").append(card.status).append("\n");
        sb.append(pagoColor).append(" Pago: ").append(card.paymentStatus).append("\n");
        if (deductible > 0) {
            sb.append("💰 Deducible: $").append(String.format("%.0f", deductible)).append("\n");
            sb.append("📊 Cobertura: ").append(String.format("%.0f", coveragePct)).append("%\n");
        }
        sb.append("\n📅 Vence: ").append(sdf.format(new java.util.Date(card.expiryDate)));
        if (vencida) sb.append(" ✕ VENCIDA");
        sb.append("\n");
        if (card.lastServiceDate != null) {
            sb.append("🔧 Último servicio: ").append(
                    sdf.format(new java.util.Date(card.lastServiceDate))).append("\n");
        }
        if (card.nextServiceDate != null) {
            sb.append("📆 Próximo mantenimiento: ").append(
                    sdf.format(new java.util.Date(card.nextServiceDate))).append("\n");
        }
        if (card.notes != null && !card.notes.isEmpty()) {
            sb.append("\n📝 Notas: ").append(card.notes);
        }

        new AlertDialog.Builder(this)
                .setTitle("Check-in de Membresía")
                .setMessage(sb.toString())
                .setPositiveButton("CONTINUAR", null)
                .show();

        // Refrescar UI de cobertura
        if (card.coverageTypeId != null) {
            cargarCobertura();
        }
    }
}
