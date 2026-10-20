package com.lc.technician;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.PropertyEntity;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RequestServiceActivity extends AppCompatActivity {

    private static final Set<String> REQUIERE_CODIGO = new HashSet<>(Arrays.asList(
            "Código puerta", "Garaje", "Lockbox", "Llave escondida"
    ));
    private static final String LLAVE_ESCONDIDA = "Llave escondida";
    private static final String FUENTE_GEOCODER = "GEOCODER";

    private View codigoContainer;
    private String selectedTipoLugar    = "Casa";
    private String selectedMetodoAcceso = "Ninguno";

    private AutoCompleteTextView acCliente, acDireccion, acEquipo;
    private EditText etTelefono, etHora, etDescripcion, etCodigo,
                     etInstrucciones, etMascota, etContactoNombre, etContactoTelefono;
    private RadioGroup radioEnCasa;
    private ScrollView scrollForm;
    private TextView tvUbicacionEstado, tvCodigoLabel;
    private Button btnSolicitar;

    private long selectedCustomerId  = 0;
    private long selectedPropertyId  = 0;
    private long selectedEquipmentId = 0;

    private boolean pendingClienteSelection  = false;
    private boolean pendingDireccionSelection = false;
    private boolean pendingEquipoSelection    = false;

    // Fecha/hora elegida con el selector (0 = sin elegir)
    private long scheduledMillis = 0;

    // Ubicación verificada con Geocoder; null = dirección escrita sin verificar
    private Double latitud, longitud;
    private String textoVerificado;
    private boolean geocodificando = false;

    // Envío: evita duplicados y conserva lo ya creado si hay que reintentar
    private boolean enviando = false;
    private boolean enConfirmacion = false;
    private long clienteForzadoId = 0;   // >0 cliente existente, -1 cliente nuevo
    private long[] idsResueltos = null;  // cliente/propiedad/equipo ya creados en un intento anterior
    private long solicitudId = 0;

    private final List<CustomerEntity>  clientesList   = new ArrayList<>();
    private final List<PropertyEntity>  propiedadesList = new ArrayList<>();
    private final List<EquipmentEntity> equiposList     = new ArrayList<>();
    private final List<TextView>        chipsTipoLugar  = new ArrayList<>();
    private final List<TextView>        chipsMetodoAcceso = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request_service);
        // Edge-to-edge (Android 15+): barra de estado arriba; navegación o teclado abajo
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        InsetsHelper.padSystemBarsAndIme(findViewById(R.id.rootRequest));

        if (savedInstanceState != null) restaurarSeleccion(savedInstanceState);

        codigoContainer    = findViewById(R.id.codigoContainer);
        tvCodigoLabel      = findViewById(R.id.tvCodigoLabel);
        acCliente          = findViewById(R.id.etCliente);
        acDireccion        = findViewById(R.id.etDireccion);
        acEquipo           = findViewById(R.id.etEquipo);
        etTelefono         = findViewById(R.id.etTelefono);
        etHora             = findViewById(R.id.etHora);
        etDescripcion      = findViewById(R.id.etDescripcion);
        etCodigo           = findViewById(R.id.etCodigo);
        etInstrucciones    = findViewById(R.id.etInstrucciones);
        etMascota          = findViewById(R.id.etMascota);
        etContactoNombre   = findViewById(R.id.etContactoNombre);
        etContactoTelefono = findViewById(R.id.etContactoTelefono);
        radioEnCasa        = findViewById(R.id.radioEnCasa);
        scrollForm         = findViewById(R.id.scrollForm);
        tvUbicacionEstado  = findViewById(R.id.tvUbicacionEstado);
        btnSolicitar       = findViewById(R.id.btnSolicitar);

        // Atrás (flecha o gesto del sistema): no perder lo escrito sin preguntar
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { intentarSalir(); }
        });
        findViewById(R.id.btnAtras).setOnClickListener(v -> intentarSalir());

        // Ajustar marcador: no hay mapa interactivo; verifica la dirección con el Geocoder de Android
        findViewById(R.id.btnAjustarMarcador).setOnClickListener(v -> mostrarOpcionesUbicacion());
        acDireccion.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { verificarDireccion(); return true; }
            return false;
        });
        // La lupa del campo también busca
        acDireccion.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP && acDireccion.getCompoundDrawablesRelative()[2] != null) {
                int lupaInicio = acDireccion.getWidth() - acDireccion.getPaddingEnd()
                        - acDireccion.getCompoundDrawablesRelative()[2].getBounds().width();
                if (ev.getX() >= lupaInicio) { verificarDireccion(); return true; }
            }
            return false;
        });

        etHora.setOnClickListener(v -> abrirSelectorFechaHora());

        buildChipGroup(
                (LinearLayout) findViewById(R.id.containerTipoLugar),
                new String[]{"Casa", "Apartamento", "Hotel", "Oficina", "Otro"},
                3, chipsTipoLugar,
                label -> { selectedTipoLugar = label; actualizarPasos(); }
        );
        activarChip(chipsTipoLugar, selectedTipoLugar);

        buildChipGroup(
                (LinearLayout) findViewById(R.id.containerMetodoAcceso),
                new String[]{"Código puerta", "Garaje", "Lockbox", "Vecino", "Llave escondida", "Ninguno"},
                3, chipsMetodoAcceso,
                label -> { selectedMetodoAcceso = label; aplicarMetodoAcceso(); actualizarPasos(); }
        );
        // Antes el valor por defecto "Ninguno" no se marcaba visualmente
        activarChip(chipsMetodoAcceso, selectedMetodoAcceso);
        aplicarMetodoAcceso();

        cargarClientesSugeridos();

        acCliente.setOnItemClickListener((parent, view, position, id) -> {
            pendingClienteSelection = true;
            String nombre = (String) parent.getItemAtPosition(position);
            for (CustomerEntity c : clientesList) {
                if (c.nombre != null && c.nombre.equals(nombre)) {
                    selectedCustomerId = c.id;
                    if (c.telefono != null && !c.telefono.isEmpty()) etTelefono.setText(c.telefono);
                    cargarPropiedadesDelCliente(c.id);
                    break;
                }
            }
        });

        acDireccion.setOnItemClickListener((parent, view, position, id) -> {
            pendingDireccionSelection = true;
            String dir = (String) parent.getItemAtPosition(position);
            for (PropertyEntity p : propiedadesList) {
                if (p.direccion != null && p.direccion.equals(dir)) {
                    selectedPropertyId = p.id;
                    prellenarCamposPropiedad(p);
                    cargarEquiposDeLaPropiedad(p.id);
                    break;
                }
            }
        });

        acEquipo.setOnItemClickListener((parent, view, position, id) -> {
            pendingEquipoSelection = true;
            String nombre = (String) parent.getItemAtPosition(position);
            for (EquipmentEntity e : equiposList) {
                if (e.nombre != null && e.nombre.equals(nombre)) {
                    selectedEquipmentId = e.id;
                    break;
                }
            }
        });

        acCliente.addTextChangedListener(new SimpleWatcher(() -> {
            if (pendingClienteSelection) { pendingClienteSelection = false; return; }
            selectedCustomerId = 0;
            selectedPropertyId = 0;
            selectedEquipmentId = 0;
            propiedadesList.clear();
            equiposList.clear();
            acDireccion.setAdapter(null);
            acEquipo.setAdapter(null);
        }));

        acDireccion.addTextChangedListener(new SimpleWatcher(() -> {
            // Si cambia el texto, la ubicación verificada deja de corresponder a la dirección
            if (latitud != null && !acDireccion.getText().toString().trim().equals(textoVerificado)) {
                limpiarUbicacion();
            }
            if (pendingDireccionSelection) { pendingDireccionSelection = false; return; }
            selectedPropertyId = 0;
            selectedEquipmentId = 0;
            equiposList.clear();
            acEquipo.setAdapter(null);
        }));

        acEquipo.addTextChangedListener(new SimpleWatcher(() -> {
            if (pendingEquipoSelection) { pendingEquipoSelection = false; return; }
            selectedEquipmentId = 0;
        }));

        // El indicador de pasos refleja lo que ya está completo
        SimpleWatcher pasos = new SimpleWatcher(this::actualizarPasos);
        for (EditText et : new EditText[]{acDireccion, acCliente, etTelefono, etHora,
                etDescripcion, etCodigo}) {
            et.addTextChangedListener(pasos);
        }
        radioEnCasa.setOnCheckedChangeListener((g, id) -> { soltarFocoTexto(); actualizarPasos(); });
        int[][] pasoDestino = {{R.id.stepItem1, R.id.secDireccion}, {R.id.stepItem2, R.id.secPropiedad},
                {R.id.stepItem3, R.id.secDatos}};
        for (int[] p : pasoDestino) {
            final int destino = p[1];
            findViewById(p[0]).setOnClickListener(v -> desplazarA(findViewById(destino)));
        }
        findViewById(R.id.stepItem4).setOnClickListener(v -> submitForm());

        btnSolicitar.setOnClickListener(v -> submitForm());

        actualizarEstadoUbicacion(null);
        actualizarPasos();
    }

    // ── Estado (rotación / proceso recreado) ─────────────────────────────────

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("tipoLugar", selectedTipoLugar);
        out.putString("metodo", selectedMetodoAcceso);
        out.putLong("customerId", selectedCustomerId);
        out.putLong("propertyId", selectedPropertyId);
        out.putLong("equipmentId", selectedEquipmentId);
        out.putLong("scheduled", scheduledMillis);
        if (latitud != null) {
            out.putDouble("lat", latitud);
            out.putDouble("lng", longitud);
            out.putString("textoVerificado", textoVerificado);
        }
        out.putLong("clienteForzado", clienteForzadoId);
        out.putLongArray("idsResueltos", idsResueltos);
        out.putLong("solicitudId", solicitudId);
    }

    private void restaurarSeleccion(Bundle in) {
        selectedTipoLugar    = in.getString("tipoLugar", selectedTipoLugar);
        selectedMetodoAcceso = in.getString("metodo", selectedMetodoAcceso);
        scheduledMillis      = in.getLong("scheduled", 0);
        if (in.containsKey("lat")) {
            latitud = in.getDouble("lat");
            longitud = in.getDouble("lng");
            textoVerificado = in.getString("textoVerificado");
        }
        clienteForzadoId = in.getLong("clienteForzado", 0);
        idsResueltos     = in.getLongArray("idsResueltos");
        solicitudId      = in.getLong("solicitudId", 0);
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        // super restaura el texto de los campos y dispara los TextWatcher, que borran los ids
        super.onRestoreInstanceState(in);
        selectedCustomerId  = in.getLong("customerId", 0);
        selectedPropertyId  = in.getLong("propertyId", 0);
        selectedEquipmentId = in.getLong("equipmentId", 0);
        if (selectedCustomerId > 0) cargarPropiedadesDelCliente(selectedCustomerId);
        actualizarEstadoUbicacion(null);
        actualizarPasos();
    }

    // ── Validación y envío ───────────────────────────────────────────────────

    private void submitForm() {
        if (enviando || enConfirmacion) return;

        String dir = acDireccion.getText().toString().trim();
        if (dir.isEmpty()) { marcarError(acDireccion, "Ingresa la dirección del servicio"); return; }

        String clienteVal = acCliente.getText().toString().trim();
        if (clienteVal.isEmpty()) { marcarError(acCliente, "Ingresa el nombre del cliente"); return; }
        if (clienteVal.length() < 3) { marcarError(acCliente, "El nombre debe tener al menos 3 caracteres"); return; }

        String telefonoVal = etTelefono.getText().toString().trim();
        if (telefonoVal.isEmpty()) { marcarError(etTelefono, "Ingresa el teléfono"); return; }
        if (!telefonoValido(telefonoVal)) {
            marcarError(etTelefono, "Teléfono no válido (10 dígitos, ej. 305 555 0101)"); return;
        }

        if (scheduledMillis == 0) { marcarError(etHora, "Elige la fecha y hora del servicio"); return; }
        if (scheduledMillis < System.currentTimeMillis() - 5 * 60_000L) {
            marcarError(etHora, "La fecha y hora ya pasaron; elige otra"); return;
        }

        String descVal = etDescripcion.getText().toString().trim();
        if (descVal.length() < 10) {
            marcarError(etDescripcion, "Descripción muy corta (mín. 10 caracteres)"); return;
        }

        String telContacto = etContactoTelefono.getText().toString().trim();
        if (!telContacto.isEmpty() && !telefonoValido(telContacto)) {
            marcarError(etContactoTelefono, "Teléfono de respaldo no válido"); return;
        }

        if (selectedCustomerId > 0) {
            clienteForzadoId = selectedCustomerId;
            mostrarResumen();
        } else if (clienteForzadoId != 0) {
            mostrarResumen();  // ya se decidió en un intento anterior
        } else {
            // Mismo nombre que un cliente existente: decide la persona, no se fusiona en silencio
            JobRepository.get().buscarClientePorNombreAsync(clienteVal, existente -> {
                if (isFinishing()) return;
                if (existente == null) { clienteForzadoId = -1; mostrarResumen(); return; }
                String tel = existente.telefono != null && !existente.telefono.isEmpty()
                        ? existente.telefono : "sin teléfono";
                new AlertDialog.Builder(this)
                        .setTitle("Cliente existente")
                        .setMessage("Ya hay un cliente llamado «" + existente.nombre + "» (" + tel
                                + ").\n\n¿Es la misma persona?")
                        .setPositiveButton("Es el mismo", (d, w) -> {
                            clienteForzadoId = existente.id; mostrarResumen(); })
                        .setNegativeButton("Es otro cliente", (d, w) -> {
                            clienteForzadoId = -1; mostrarResumen(); })
                        .setNeutralButton("Cancelar", null)
                        .show();
            });
        }
    }

    private void mostrarResumen() {
        enConfirmacion = true;
        actualizarPasos();
        StringBuilder sb = new StringBuilder();
        linea(sb, "Dirección", acDireccion.getText().toString().trim()
                + (latitud != null ? "\n✓ ubicación verificada" : "\n(sin verificar)"));
        linea(sb, "Cliente", acCliente.getText().toString().trim()
                + (clienteForzadoId > 0 ? " (existente)" : " (nuevo)"));
        linea(sb, "Teléfono", normalizarTelefono(etTelefono.getText().toString().trim()));
        linea(sb, "Fecha y hora", formatoFechaHora(scheduledMillis));
        linea(sb, "Problema", etDescripcion.getText().toString().trim());
        linea(sb, "Equipo", acEquipo.getText().toString().trim());
        linea(sb, "Propiedad", selectedTipoLugar);
        linea(sb, "Cliente presente", radioEnCasa.getCheckedRadioButtonId() == R.id.radioSi ? "Sí" : "No");
        String acceso = selectedMetodoAcceso;
        String complemento = etCodigo.getText().toString().trim();
        if (REQUIERE_CODIGO.contains(selectedMetodoAcceso) && !complemento.isEmpty()) {
            acceso += (LLAVE_ESCONDIDA.equals(selectedMetodoAcceso) ? " · llave: " : " · código: ") + complemento;
        }
        linea(sb, "Acceso", acceso);
        linea(sb, "Instrucciones", etInstrucciones.getText().toString().trim());
        linea(sb, "Mascotas", etMascota.getText().toString().trim());
        linea(sb, "Contacto de respaldo", contactoRespaldo());

        new AlertDialog.Builder(this)
                .setTitle("Confirmar solicitud")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Guardar solicitud", (d, w) -> guardar())
                .setNegativeButton("Corregir", null)
                .setOnDismissListener(d -> { enConfirmacion = false; actualizarPasos(); })
                .show();
    }

    private void guardar() {
        if (enviando) return;
        enviando = true;
        btnSolicitar.setEnabled(false);
        btnSolicitar.setText("GUARDANDO…");

        final boolean presente   = radioEnCasa.getCheckedRadioButtonId() == R.id.radioSi;
        final String  complemento = REQUIERE_CODIGO.contains(selectedMetodoAcceso)
                ? etCodigo.getText().toString().trim() : "";  // campo oculto: no guardar un valor viejo
        final String  fCodigo    = LLAVE_ESCONDIDA.equals(selectedMetodoAcceso) ? "" : complemento;
        final String  fLlave     = LLAVE_ESCONDIDA.equals(selectedMetodoAcceso) ? complemento : "";
        final String  fCliente   = acCliente.getText().toString().trim();
        final String  fTelefono  = normalizarTelefono(etTelefono.getText().toString().trim());
        final String  fDir       = acDireccion.getText().toString().trim();
        final String  fHora      = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(scheduledMillis));
        final String  fDesc      = etDescripcion.getText().toString().trim();
        String equipoVal = acEquipo.getText().toString().trim();
        final String  fEquipo    = equipoVal.isEmpty() ? "Por asignar" : equipoVal;
        final String  fContacto  = contactoRespaldo();
        final String  fInstruc   = etInstrucciones.getText().toString().trim();
        final String  fMascota   = etMascota.getText().toString().trim();
        final String  fTipoLugar = selectedTipoLugar;
        final String  fAcceso    = selectedMetodoAcceso;
        final Double  fLat = latitud, fLng = longitud;
        final String  fFuente = latitud != null ? FUENTE_GEOCODER : null;

        if (idsResueltos != null) {
            crearJob(idsResueltos, fCliente, fTelefono, fDir, fHora, fDesc, fEquipo, fTipoLugar,
                    presente, fAcceso, fCodigo, fLlave, fInstruc, fMascota, fContacto, fLat, fLng, fFuente);
            return;
        }
        JobRepository.get().resolverOCrearEntidades(
                fCliente, fTelefono, fDir, fTipoLugar, presente, fAcceso,
                fCodigo, fLlave, fInstruc, fMascota, fContacto, "", fEquipo,
                clienteForzadoId, fLat, fLng, fFuente,
                ids -> {
                    idsResueltos = ids;
                    crearJob(ids, fCliente, fTelefono, fDir, fHora, fDesc, fEquipo, fTipoLugar,
                            presente, fAcceso, fCodigo, fLlave, fInstruc, fMascota, fContacto,
                            fLat, fLng, fFuente);
                },
                this::errorAlGuardar);
    }

    private void crearJob(long[] ids, String fCliente, String fTelefono, String fDir, String fHora,
                          String fDesc, String fEquipo, String fTipoLugar, boolean fPresente,
                          String fAcceso, String fCodigo, String fLlave, String fInstruc,
                          String fMascota, String fContacto, Double fLat, Double fLng, String fFuente) {
        JobRepository.get().nuevoIdOrdenAsync(idNuevo -> {
            // El id se fija una sola vez: un reintento reemplaza la misma fila (REPLACE), no duplica
            if (solicitudId == 0) solicitudId = idNuevo;
            Job nuevoJob = new Job(
                    solicitudId, fCliente, fTelefono, fDir, fHora, fDesc, fEquipo,
                    Job.STATUS_PENDING, fTipoLugar, fPresente, fAcceso,
                    fCodigo, fLlave, fInstruc, fMascota, fContacto, ""
            );
            nuevoJob.updatedAt = System.currentTimeMillis();
            if (ids[0] > 0) nuevoJob.customerId  = ids[0];
            if (ids[1] > 0) nuevoJob.propertyId  = ids[1];
            if (ids[2] > 0) nuevoJob.equipmentId = ids[2];

            JobEntity extra = new JobEntity();
            extra.prioridad = "NORMAL";
            extra.programadoPara = scheduledMillis;
            extra.estadoDispatch = "QUEUE";
            extra.latitud = fLat;
            extra.longitud = fLng;
            extra.ubicacionFuente = fFuente;

            JobRepository.get().crearSolicitudAsync(nuevoJob, extra,
                    this::solicitudGuardada, this::errorAlGuardar);
        });
    }

    private void solicitudGuardada(JobEntity guardado) {
        // Guardado terminado: el botón deja de decir GUARDANDO, pero sigue desactivado (no hay segundo envío)
        btnSolicitar.setText("SOLICITUD GUARDADA ✓");
        btnSolicitar.setEnabled(false);
        if (isFinishing()) return;
        String red = hayInternet() ? "" : "\nAhora mismo no hay conexión a internet.";
        new AlertDialog.Builder(this)
                .setTitle("Solicitud guardada")
                .setMessage("Nº " + guardado.id
                        + "\nEstado: Pendiente"
                        + "\nProgramada: " + formatoFechaHora(scheduledMillis)
                        + "\n\nGuardada en este dispositivo."
                        + "\nCentro de mando: pendiente de sincronización. Se enviará cuando haya"
                        + " conexión con el servidor de L&C." + red)
                .setCancelable(false)
                .setPositiveButton("Aceptar", (d, w) -> { setResult(RESULT_OK); finish(); })
                .show();
    }

    private void errorAlGuardar(Exception e) {
        enviando = false;
        btnSolicitar.setEnabled(true);
        btnSolicitar.setText("SOLICITAR SERVICIO");
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("No se pudo guardar")
                .setMessage("La solicitud no se guardó. Tus datos siguen en el formulario; puedes"
                        + " intentarlo de nuevo.\n\nDetalle: " + e.getClass().getSimpleName())
                .setPositiveButton("Entendido", null)
                .show();
    }

    private void intentarSalir() {
        if (enviando) return;
        if (!formularioConDatos()) { finish(); return; }
        new AlertDialog.Builder(this)
                .setTitle("¿Descartar solicitud?")
                .setMessage("Se perderá lo que escribiste.")
                .setPositiveButton("Descartar", (d, w) -> finish())
                .setNegativeButton("Seguir editando", null)
                .show();
    }

    private boolean formularioConDatos() {
        for (EditText et : new EditText[]{acDireccion, acCliente, etTelefono, etHora, etDescripcion,
                acEquipo, etCodigo, etInstrucciones, etMascota, etContactoNombre, etContactoTelefono}) {
            if (et.getText().toString().trim().length() > 0) return true;
        }
        return false;
    }

    /** Error junto al campo, desplazando hasta él. */
    private void marcarError(EditText campo, String msg) {
        campo.setError(msg);
        desplazarA(campo);
        if (campo.isFocusable()) {
            campo.requestFocus();
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();  // el selector de fecha no toma foco
        }
    }

    private void desplazarA(View v) {
        if (v == null) return;
        scrollForm.post(() -> {
            int y = 0;
            View cur = v;
            while (cur != null && cur != scrollForm) {
                y += cur.getTop();
                cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            }
            scrollForm.smoothScrollTo(0, Math.max(0, y - dp(24)));
        });
    }

    private static boolean telefonoValido(String tel) {
        String d = tel.replaceAll("\\D", "");
        if (tel.trim().startsWith("+")) return d.length() >= 8 && d.length() <= 15;
        return d.length() == 10 || (d.length() == 11 && d.startsWith("1"));
    }

    /** (305) 555-0101 como el resto de la app; otros formatos se dejan tal cual. */
    private static String normalizarTelefono(String tel) {
        String d = tel.replaceAll("\\D", "");
        if (!tel.trim().startsWith("+") && d.length() == 11 && d.startsWith("1")) d = d.substring(1);
        if (!tel.trim().startsWith("+") && d.length() == 10) {
            return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        }
        return tel.trim();
    }

    private String contactoRespaldo() {
        String nombre = etContactoNombre.getText().toString().trim();
        String tel = etContactoTelefono.getText().toString().trim();
        if (!tel.isEmpty()) tel = normalizarTelefono(tel);
        // Antes, un teléfono de respaldo sin nombre se perdía
        if (nombre.isEmpty()) return tel;
        return tel.isEmpty() ? nombre : nombre + " · " + tel;
    }

    private static void linea(StringBuilder sb, String etiqueta, String valor) {
        if (valor == null || valor.trim().isEmpty()) return;
        sb.append(etiqueta).append(": ").append(valor).append("\n");
    }

    // ── Fecha y hora ─────────────────────────────────────────────────────────

    private void abrirSelectorFechaHora() {
        final Calendar cal = Calendar.getInstance();
        if (scheduledMillis > 0) {
            cal.setTimeInMillis(scheduledMillis);
        } else {
            cal.add(Calendar.HOUR_OF_DAY, 1);
            cal.set(Calendar.MINUTE, 0);
        }
        DatePickerDialog dp = new DatePickerDialog(this, (view, y, m, d) -> {
            cal.set(Calendar.YEAR, y);
            cal.set(Calendar.MONTH, m);
            cal.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tv, h, min) -> {
                cal.set(Calendar.HOUR_OF_DAY, h);
                cal.set(Calendar.MINUTE, min);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                scheduledMillis = cal.getTimeInMillis();
                etHora.setError(null);
                etHora.setText(formatoFechaHora(scheduledMillis));
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dp.show();
    }

    private static String formatoFechaHora(long ms) {
        if (ms == 0) return "";
        Calendar hoy = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        String hora = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(ms));
        boolean mismoDia = hoy.get(Calendar.YEAR) == c.get(Calendar.YEAR)
                && hoy.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR);
        if (mismoDia) return "Hoy · " + hora;
        return new SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("es")).format(new Date(ms))
                + " · " + hora;
    }

    // ── Dirección y ubicación ────────────────────────────────────────────────

    private void mostrarOpcionesUbicacion() {
        List<String> opciones = new ArrayList<>();
        List<Runnable> acciones = new ArrayList<>();
        opciones.add("Buscar y verificar la dirección escrita");
        acciones.add(this::verificarDireccion);
        String dir = acDireccion.getText().toString().trim();
        if (!dir.isEmpty() || latitud != null) {
            opciones.add("Ver en la app de mapas");
            acciones.add(this::abrirEnMapas);
        }
        if (latitud != null) {
            opciones.add("Quitar verificación (usar la dirección escrita)");
            acciones.add(this::limpiarUbicacion);
        }
        new AlertDialog.Builder(this)
                .setTitle("Ubicación del servicio")
                .setItems(opciones.toArray(new String[0]), (d, which) -> acciones.get(which).run())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void verificarDireccion() {
        final String dir = acDireccion.getText().toString().trim();
        if (dir.isEmpty()) { marcarError(acDireccion, "Escribe la dirección primero"); return; }
        if (geocodificando) return;
        if (!Geocoder.isPresent()) {
            actualizarEstadoUbicacion("Verificación no disponible en este dispositivo"
                    + " · se guardará la dirección escrita");
            return;
        }
        if (!hayInternet()) {
            actualizarEstadoUbicacion("Sin conexión: no se puede verificar ahora"
                    + " · se guardará la dirección escrita");
            return;
        }
        geocodificando = true;
        actualizarEstadoUbicacion("Buscando la dirección…");
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocationName(dir, 5, new Geocoder.GeocodeListener() {
                @Override public void onGeocode(List<Address> list) {
                    runOnUiThread(() -> resultadosGeocoder(dir, list, null));
                }
                @Override public void onError(String msg) {
                    runOnUiThread(() -> resultadosGeocoder(dir, null, msg));
                }
            });
        } else {
            new Thread(() -> {
                List<Address> list = null;
                String err = null;
                try {
                    list = geocoder.getFromLocationName(dir, 5);
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                final List<Address> fl = list;
                final String fe = err;
                runOnUiThread(() -> resultadosGeocoder(dir, fl, fe));
            }).start();
        }
    }

    private void resultadosGeocoder(String consulta, List<Address> list, String error) {
        geocodificando = false;
        if (isFinishing() || isDestroyed()) return;
        if (!consulta.equals(acDireccion.getText().toString().trim())) {
            actualizarEstadoUbicacion(null);  // el texto cambió mientras se buscaba
            return;
        }
        if (error != null) {
            actualizarEstadoUbicacion("No se pudo contactar el servicio de ubicación"
                    + " · se guardará la dirección escrita");
            return;
        }
        if (list == null || list.isEmpty()) {
            actualizarEstadoUbicacion("No se encontró esa dirección. Revísala o guárdala sin verificar.");
            return;
        }
        final List<Address> candidatos = new ArrayList<>();
        List<String> textos = new ArrayList<>();
        for (Address a : list) {
            if (!a.hasLatitude() || !a.hasLongitude()) continue;
            candidatos.add(a);
            String linea = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
            textos.add(linea != null ? linea : String.format(Locale.US, "%.5f, %.5f",
                    a.getLatitude(), a.getLongitude()));
        }
        if (candidatos.isEmpty()) {
            actualizarEstadoUbicacion("No se encontró esa dirección. Revísala o guárdala sin verificar.");
            return;
        }
        actualizarEstadoUbicacion(null);
        new AlertDialog.Builder(this)
                .setTitle("Elige la dirección correcta")
                .setItems(textos.toArray(new String[0]),
                        (d, which) -> aplicarUbicacion(candidatos.get(which), textos.get(which)))
                .setNegativeButton("Ninguna · usar lo que escribí", null)
                .show();
    }

    private void aplicarUbicacion(Address a, String linea) {
        latitud = a.getLatitude();
        longitud = a.getLongitude();
        // Con una Property existente se conserva su texto (la búsqueda de duplicados usa la dirección)
        if (selectedPropertyId == 0 && linea != null && !linea.isEmpty()) {
            textoVerificado = linea;
            acDireccion.setText(linea, false);
            acDireccion.setSelection(linea.length());
        } else {
            textoVerificado = acDireccion.getText().toString().trim();
        }
        acDireccion.setError(null);
        actualizarEstadoUbicacion(null);
        actualizarPasos();
    }

    private void limpiarUbicacion() {
        latitud = null;
        longitud = null;
        textoVerificado = null;
        actualizarEstadoUbicacion(null);
    }

    /** Panel del mapa: estado real de la ubicación; un mensaje temporal sustituye al estado. */
    private void actualizarEstadoUbicacion(String mensaje) {
        if (tvUbicacionEstado == null) return;
        TextView pin = findViewById(R.id.tvMapaPin);
        if (mensaje != null) {
            tvUbicacionEstado.setText(mensaje);
            tvUbicacionEstado.setTextColor(ContextCompat.getColor(this, R.color.lc_text_secondary));
            pin.setAlpha(0.45f);
        } else if (latitud != null) {
            tvUbicacionEstado.setText(String.format(Locale.US, "✓ Ubicación verificada\n%.5f, %.5f",
                    latitud, longitud));
            tvUbicacionEstado.setTextColor(ContextCompat.getColor(this, R.color.lc_text));
            pin.setAlpha(1f);
        } else {
            tvUbicacionEstado.setText("Ubicación sin verificar · se guardará la dirección escrita");
            tvUbicacionEstado.setTextColor(ContextCompat.getColor(this, R.color.lc_text_secondary));
            pin.setAlpha(0.45f);
        }
    }

    private void abrirEnMapas() {
        Uri uri;
        if (latitud != null) {
            uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f", latitud, longitud, latitud, longitud));
        } else {
            uri = Uri.parse("geo:0,0?q=" + Uri.encode(acDireccion.getText().toString().trim()));
        }
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        if (i.resolveActivity(getPackageManager()) != null) {
            startActivity(i);
        } else {
            Toast.makeText(this, "No hay una app de mapas instalada", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hayInternet() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // ── Acceso ───────────────────────────────────────────────────────────────

    private void aplicarMetodoAcceso() {
        boolean requiere = REQUIERE_CODIGO.contains(selectedMetodoAcceso);
        codigoContainer.setVisibility(requiere ? View.VISIBLE : View.GONE);
        if (LLAVE_ESCONDIDA.equals(selectedMetodoAcceso)) {
            tvCodigoLabel.setText("Ubicación de la llave");
            etCodigo.setHint("Ej. Debajo de la maceta junto a la puerta");
        } else {
            tvCodigoLabel.setText("Código de acceso");
            etCodigo.setHint("Ej. 1234");
        }
    }

    // ── Indicador de pasos ───────────────────────────────────────────────────

    private void actualizarPasos() {
        if (btnSolicitar == null) return;
        boolean p1 = !acDireccion.getText().toString().trim().isEmpty();
        boolean p2 = !REQUIERE_CODIGO.contains(selectedMetodoAcceso)
                || !etCodigo.getText().toString().trim().isEmpty();
        boolean p3 = acCliente.getText().toString().trim().length() >= 3
                && telefonoValido(etTelefono.getText().toString().trim())
                && scheduledMillis > 0
                && etDescripcion.getText().toString().trim().length() >= 10;
        boolean[] hecho = {p1, p1 && p2, p3, false};
        int actual = enConfirmacion ? 3 : 0;
        if (!enConfirmacion) {
            while (actual < 3 && hecho[actual]) actual++;
        }
        int[] dots = {R.id.stepDot1, R.id.stepDot2, R.id.stepDot3, R.id.stepDot4};
        int[] labels = {R.id.stepLabel1, R.id.stepLabel2, R.id.stepLabel3, R.id.stepLabel4};
        int completos = 0;
        for (int i = 0; i < 4; i++) {
            boolean esActual = i == actual;
            if (hecho[i]) completos++;
            findViewById(dots[i]).setBackground(ContextCompat.getDrawable(this,
                    esActual || hecho[i] ? R.drawable.bg_step_dot_active : R.drawable.bg_step_dot_inactive));
            ((TextView) findViewById(labels[i])).setTextColor(ContextCompat.getColor(this,
                    esActual ? R.color.lc_primary : hecho[i] ? R.color.lc_text : R.color.lc_text_tertiary));
        }
        if (enConfirmacion) completos = 4;
        int barra = Math.max(1, completos);
        ((LinearLayout.LayoutParams) findViewById(R.id.progressDone).getLayoutParams()).weight = barra;
        ((LinearLayout.LayoutParams) findViewById(R.id.progressRest).getLayoutParams()).weight = 4 - barra;
        findViewById(R.id.progressStrip).requestLayout();
    }

    // ── Sugerencias desde Room (sin cambios de comportamiento) ───────────────

    private void cargarClientesSugeridos() {
        JobRepository.get().getAllCustomersAsync(clientes -> {
            clientesList.clear();
            clientesList.addAll(clientes);
            List<String> nombres = new ArrayList<>();
            for (CustomerEntity c : clientes) if (c.nombre != null) nombres.add(c.nombre);
            acCliente.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, nombres));
        });
    }

    private void cargarPropiedadesDelCliente(long customerId) {
        JobRepository.get().getPropertiesByCustomerAsync(customerId, props -> {
            propiedadesList.clear();
            propiedadesList.addAll(props);
            List<String> dirs = new ArrayList<>();
            for (PropertyEntity p : props) if (p.direccion != null) dirs.add(p.direccion);
            acDireccion.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, dirs));
            // Solo se autocompleta si el campo está vacío: no pisar lo que la persona escribió
            if (dirs.size() == 1 && acDireccion.getText().toString().trim().isEmpty()) {
                pendingDireccionSelection = true;
                acDireccion.setText(dirs.get(0), false);
                prellenarCamposPropiedad(propiedadesList.get(0));
                cargarEquiposDeLaPropiedad(propiedadesList.get(0).id);
            }
        });
    }

    private void prellenarCamposPropiedad(PropertyEntity p) {
        selectedPropertyId = p.id;
        if (p.tipoPropiedad != null && !p.tipoPropiedad.isEmpty()) {
            selectedTipoLugar = p.tipoPropiedad;
            activarChip(chipsTipoLugar, p.tipoPropiedad);
        }
        radioEnCasa.check(p.clientePresente ? R.id.radioSi : R.id.radioNo);
        if (p.tipoAcceso != null && !p.tipoAcceso.isEmpty()) {
            selectedMetodoAcceso = p.tipoAcceso;
            activarChip(chipsMetodoAcceso, p.tipoAcceso);
            aplicarMetodoAcceso();
        }
        if (LLAVE_ESCONDIDA.equals(p.tipoAcceso)) {
            if (p.ubicacionLlave != null) etCodigo.setText(p.ubicacionLlave);
        } else if (p.codigoAcceso != null) {
            etCodigo.setText(p.codigoAcceso);
        }
        if (p.instrucciones != null) etInstrucciones.setText(p.instrucciones);
        if (p.mascota       != null) etMascota.setText(p.mascota);
        if (p.latitud != null && p.longitud != null) {
            latitud = p.latitud;
            longitud = p.longitud;
            textoVerificado = p.direccion != null ? p.direccion.trim() : null;
            actualizarEstadoUbicacion(null);
        }
        actualizarPasos();
    }

    private void cargarEquiposDeLaPropiedad(long propertyId) {
        JobRepository.get().getEquipmentByPropertyAsync(propertyId, equipos -> {
            equiposList.clear();
            equiposList.addAll(equipos);
            List<String> nombres = new ArrayList<>();
            for (EquipmentEntity e : equipos) if (e.nombre != null) nombres.add(e.nombre);
            acEquipo.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, nombres));
            if (nombres.size() == 1 && acEquipo.getText().toString().trim().isEmpty()) {
                pendingEquipoSelection = true;
                acEquipo.setText(nombres.get(0), false);
                selectedEquipmentId = equiposList.get(0).id;
            }
        });
    }

    private void activarChip(List<TextView> chips, String label) {
        for (TextView chip : chips) {
            boolean activo = label.equals(chip.getText().toString());
            chip.setBackground(ContextCompat.getDrawable(this,
                    activo ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive));
            chip.setSelected(activo);
        }
    }

    private void buildChipGroup(LinearLayout container, String[] opciones, int perFila,
                                List<TextView> chipRef, OnChipSelected listener) {
        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);
        chipRef.clear();

        for (int i = 0; i < opciones.length; i += perFila) {
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams filaParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) filaParams.topMargin = dp(8);
            fila.setLayoutParams(filaParams);

            int fin = Math.min(i + perFila, opciones.length);
            for (int j = i; j < fin; j++) {
                String opcion = opciones[j];
                TextView chip = new TextView(this);

                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                chipParams.setMarginEnd(dp(8));
                chip.setLayoutParams(chipParams);

                chip.setText(opcion);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                chip.setTextColor(0xFF191919);
                chip.setPadding(dp(16), dp(10), dp(16), dp(10));
                chip.setMinHeight(dp(44));  // área táctil mínima
                chip.setGravity(android.view.Gravity.CENTER);
                chip.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_chip_inactive));
                chip.setSingleLine(true);

                chipRef.add(chip);
                chip.setOnClickListener(v -> {
                    soltarFocoTexto();
                    activarChip(chipRef, opcion);
                    if (listener != null) listener.onSelected(opcion);
                });

                fila.addView(chip);
            }
            container.addView(fila);
        }
    }

    /**
     * Al elegir un chip u opción: si un campo de texto conserva el foco, el ScrollView vuelve
     * a él cuando cambia el diseño (p. ej. aparece el código) y la vista "salta". Se suelta.
     */
    private void soltarFocoTexto() {
        View f = getCurrentFocus();
        if (f instanceof EditText) {
            f.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    interface OnChipSelected {
        void onSelected(String label);
    }

    /** TextWatcher que solo necesita afterTextChanged. */
    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }
}
