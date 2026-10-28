package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.Job;
import com.lc.technician.model.PaymentEntity;
import com.lc.technician.model.WorkItemEntity;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class InvoiceActivity extends AppCompatActivity {

    static final String EXTRA_JOB_ID = "invoice_job_id";
    private static final String PREFS_INVOICES = "lc_invoices";
    private static final double TAX_RATE = 0.07;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es"));

    private Job job;
    private List<WorkItemEntity> workItems;
    private double subtotal = 0;

    private TextView tvInvoiceStatus;
    private TextView tvInvoiceNumero;
    private TextView tvInvoiceFecha;
    private TextView tvInvoiceMetodoPago;
    private LinearLayout layoutLineas;
    private TextView tvSubtotal;
    private TextView tvTax;
    private TextView tvTotal;
    private Button btnMarcarPagado;
    private Button btnGenerarPdf;
    private Button btnCompartir;

    private File pdfFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_invoice);

        View invoiceBottomBar = findViewById(R.id.bottomBar);
        final int invOrigPad = invoiceBottomBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(invoiceBottomBar, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    invOrigPad + nav);
            return insets;
        });

        long jobId = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        job = JobRepository.get().getById(jobId);
        if (job == null) { finish(); return; }

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        tvInvoiceStatus    = findViewById(R.id.tvInvoiceStatus);
        tvInvoiceNumero    = findViewById(R.id.tvInvoiceNumero);
        tvInvoiceFecha     = findViewById(R.id.tvInvoiceFecha);
        tvInvoiceMetodoPago = findViewById(R.id.tvInvoiceMetodoPago);
        layoutLineas       = findViewById(R.id.layoutLineas);
        tvSubtotal         = findViewById(R.id.tvSubtotal);
        tvTax              = findViewById(R.id.tvTax);
        tvTotal            = findViewById(R.id.tvTotal);
        btnMarcarPagado    = findViewById(R.id.btnMarcarPagado);
        btnGenerarPdf      = findViewById(R.id.btnGenerarPdf);
        btnCompartir       = findViewById(R.id.btnCompartir);

        // Invoice metadata
        String numero = generarNumero(job.id);
        tvInvoiceNumero.setText(numero);
        tvInvoiceFecha.setText("Fecha: " + FMT.format(new Date()));

        // Client info
        ((TextView) findViewById(R.id.tvInvCliente)).setText(job.cliente != null ? job.cliente : "");
        ((TextView) findViewById(R.id.tvInvDireccion)).setText(job.direccion != null ? job.direccion : "");
        ((TextView) findViewById(R.id.tvInvTelefono)).setText(job.telefono != null ? job.telefono : "");

        // Load payment status (con sesión de laboratorio la factura la emite el servidor; ver modoServidor)
        modoServidor = com.lc.technician.sync.LabSession.activa(this);
        if (!modoServidor) actualizarEstadoPago();

        // Load work items (con sesión de laboratorio, también los repuestos usados: mismas líneas que el servidor)
        java.util.function.Consumer<List<WorkItemEntity>> alCargar = items -> {
            workItems = items;
            mostrarLineas(items);
        };
        if (com.lc.technician.sync.LabSession.activa(this)) JobRepository.get().getLineasFacturaAsync(job.id, alCargar);
        else JobRepository.get().getWorkItemsAsync(job.id, alCargar);

        btnMarcarPagado.setOnClickListener(v -> mostrarDialogoPago());
        btnGenerarPdf.setOnClickListener(v -> generarPdf());
        btnCompartir.setOnClickListener(v -> compartirPdf());
        if (modoServidor) configurarModoServidor();
    }

    // ── Fase 6: factura emitida por el servidor (sync v2) ──
    private boolean modoServidor;
    private com.lc.technician.model.InvoiceSyncEntity facturaServidor;
    private final android.os.Handler refresco = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { pintarFacturaServidor(); refresco.postDelayed(this, 3000); }
    };

    private void configurarModoServidor() {
        // No se marca PAGADA sin confirmación real de pago: el botón de pago pasa a ser "emitir en el servidor"
        btnMarcarPagado.setText("EMITIR FACTURA EN EL SERVIDOR");
        btnMarcarPagado.setOnClickListener(v -> confirmarEmision());
        pintarFacturaServidor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modoServidor) refresco.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refresco.removeCallbacks(tick);
    }

    private void confirmarEmision() {
        if (!Job.STATUS_DONE.equals(job.estado)) {
            Toast.makeText(this, "Primero completa el trabajo (estado COMPLETADO)", Toast.LENGTH_LONG).show();
            return;
        }
        if (workItems == null || workItems.isEmpty()) {
            Toast.makeText(this, "No hay trabajos realizados para facturar", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Emitir factura")
                .setMessage("El servidor crea la factura con los trabajos sincronizados de esta orden (sin partidas "
                        + "anuladas), calcula el impuesto configurado y la deja disponible para el cliente.\n\n"
                        + "Total en este teléfono: " + String.format(Locale.US, "$%.2f", totalLocal())
                        + "\n\nNo se marca como pagada.")
                .setPositiveButton("EMITIR", (d, w) -> JobRepository.get().solicitarFacturaAsync(job.id, f -> {
                    facturaServidor = f;
                    Toast.makeText(this, "Guardada en el teléfono; se enviará al servidor", Toast.LENGTH_SHORT).show();
                    pintarFacturaServidor();
                }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private java.util.List<com.lc.technician.model.PaymentEntity> pagosLocales = new java.util.ArrayList<>();

    /** Total que calcula el teléfono: (subtotal − descuento autorizado por despacho) + impuesto. */
    private double totalLocal() {
        double desc = Math.min(com.lc.technician.sync.LabSession.descuento(this, job.id), subtotal);
        return Math.round((subtotal - desc) * (1 + TAX_RATE) * 100.0) / 100.0;
    }

    // ── Regla del propietario: advertir y exigir aprobación si difiere de la propuesta aprobada ──
    static final double VARIACION_ALCANCE_PCT = 15.0;
    private Double aprobadoLocal;          // total aprobado por el cliente (propuesta confirmada por el servidor)
    private TextView franjaAviso;

    /** MAYOR | ALCANCE | MENOR | IGUAL | null (sin propuesta aprobada). Mismos umbrales que el servidor. */
    static String clasificar(Double aprobado, double total) {
        if (aprobado == null) return null;
        double dif = Math.round((total - aprobado) * 100.0) / 100.0;
        if (dif > 0.01) return "MAYOR";
        if (dif < -0.01) return aprobado > 0 && -dif / aprobado * 100.0 > VARIACION_ALCANCE_PCT ? "ALCANCE" : "MENOR";
        return "IGUAL";
    }

    static String textoAviso(String tipo, double aprobado, double total, String estadoVariacion, boolean emitida,
                             boolean conAprobacion) {
        double dif = Math.round((total - aprobado) * 100.0) / 100.0;
        String cifras = String.format(Locale.US, "aprobado $%.2f · factura $%.2f", aprobado, total);
        if ("MAYOR".equals(tipo) || "ALCANCE".equals(tipo)) {
            String cab = "MAYOR".equals(tipo)
                    ? String.format(Locale.US, "⚠ SUPERA LO APROBADO EN $%.2f", dif)
                    : String.format(Locale.US, "⚠ CAMBIO DE ALCANCE: $%.2f MENOS (más del %.0f %%)", -dif, VARIACION_ALCANCE_PCT);
            if (emitida) return cab + "\n" + cifras + (conAprobacion ? "\n✓ Diferencia aprobada por el cliente"
                    : "\nEmitida sin aprobación adicional del cliente (antes de la regla)");
            if ("APROBADA".equals(estadoVariacion)) return cab + "\n" + cifras + "\n✓ El cliente aprobó la diferencia: ya se puede emitir";
            if ("PENDIENTE".equals(estadoVariacion)) return cab + "\n" + cifras + "\nEsperando que el cliente apruebe la diferencia en L&C Customer";
            if ("RECHAZADA".equals(estadoVariacion)) return cab + "\n" + cifras + "\n✗ El cliente rechazó la diferencia: revisa el trabajo facturado";
            return cab + "\n" + cifras + "\nRequiere aprobación adicional del cliente antes de emitir";
        }
        if ("MENOR".equals(tipo)) {
            return String.format(Locale.US, "ℹ MENOR QUE LO APROBADO EN $%.2f\n", -dif) + cifras
                    + (emitida ? "\n✓ Revisado y confirmado por el técnico" : "\nRevisa y confirma el importe antes de emitir");
        }
        return null;
    }

    private void pintarAviso(String tipo, double aprobado, double total, String estadoVar, boolean emitida, boolean conAprob) {
        String t = tipo == null ? null : textoAviso(tipo, aprobado, total, estadoVar, emitida, conAprob);
        if (franjaAviso == null) {
            if (t == null || !(tvInvoiceMetodoPago.getParent() instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup padre = (android.view.ViewGroup) tvInvoiceMetodoPago.getParent();
            franjaAviso = new TextView(this);
            franjaAviso.setTextColor(0xFFFFFFFF);
            franjaAviso.setTextSize(15f);
            franjaAviso.setTypeface(null, android.graphics.Typeface.BOLD);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            franjaAviso.setPadding(pad, pad, pad, pad);
            padre.addView(franjaAviso, padre.indexOfChild(tvInvoiceMetodoPago));
        }
        franjaAviso.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t == null) return;
        franjaAviso.setText(t);
        boolean ok = "APROBADA".equals(estadoVar) || (emitida && conAprob) || ("MENOR".equals(tipo) && emitida);
        franjaAviso.setBackgroundColor(ok ? 0xFF2E7D32 : "MENOR".equals(tipo) ? 0xFFEF6C00 : 0xFFB71C1C);
    }

    private void confirmarMenor(double aprobado, double total) {
        new AlertDialog.Builder(this)
                .setTitle("Importe menor que lo aprobado")
                .setMessage(String.format(Locale.US, "Aprobado por el cliente: $%.2f\nFactura: $%.2f\nDiferencia: −$%.2f\n\n"
                        + "Confirmo que revisé el trabajo y que el importe final es correcto. Quedará registrado.",
                        aprobado, total, aprobado - total))
                .setPositiveButton("CONFIRMO Y EMITO", (d, w) -> JobRepository.get().solicitarFacturaAsync(job.id, true, f -> {
                    facturaServidor = f;
                    pintarFacturaServidor();
                }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void pintarFacturaServidor() {
        JobRepository.get().getPagosAsync(job.id, pagos -> pagosLocales = pagos);
        JobRepository.get().getProposalForJobAsync(job.id, p -> aprobadoLocal =
                p != null && p.syncState == 1 && "APROBADA".equals(p.serverEstado) ? p.totalServidor : null);
        JobRepository.get().getInvoiceSyncAsync(job.id, f -> {
            facturaServidor = f;
            String estado;
            StringBuilder detalle = new StringBuilder();
            int color;
            double desc = com.lc.technician.sync.LabSession.descuento(this, job.id);
            if (f == null) {
                estado = "BORRADOR LOCAL"; color = 0xFF757575;
                detalle.append("Aún no emitida en el servidor.");
                if (desc > 0) detalle.append(String.format(Locale.US, "\nDescuento autorizado por despacho: −$%.2f", desc));
            } else if (f.syncState == 0 || f.syncState == 2) {
                estado = "PENDIENTE"; color = 0xFFF57C00;
                detalle.append("Guardada en el teléfono · pendiente de confirmación del servidor.");
            } else if (f.syncState == 3 && "REQUIERE_APROBACION_CLIENTE".equals(f.codigoRechazo)) {
                // no es un error: la regla exige la aprobación adicional del cliente antes de emitir
                estado = "APROBACIÓN CLIENTE"; color = 0xFFEF6C00;
                detalle.append("Sin emitir: ").append(f.motivo);
            } else if (f.syncState == 3 && "CONFIRMAR_MENOR".equals(f.codigoRechazo)) {
                estado = "POR CONFIRMAR"; color = 0xFFEF6C00;
                detalle.append("Sin emitir: ").append(f.motivo);
            } else if (f.syncState == 3 && "CLIENTE_RECHAZO".equals(f.codigoRechazo)) {
                estado = "DIFERENCIA RECHAZADA"; color = 0xFFC62828;
                detalle.append("Sin emitir: ").append(f.motivo);
            } else if (f.syncState == 3) {
                estado = "RECHAZADA"; color = 0xFFC62828;
                detalle.append("El servidor no la emitió: ").append(f.motivo);
            } else {
                estado = f.estadoServidor != null ? f.estadoServidor : "EMITIDA";
                color = "PAGADA".equals(estado) ? 0xFF137333 : 0xFF1565C0;
                double local = totalLocal();
                boolean igual = f.total != null && Math.abs(f.total - local) < 0.01;
                detalle.append("Emitida en el servidor · Nº ").append(f.numero).append("\n")
                       .append(String.format(Locale.US, "Subtotal $%.2f", f.subtotal));
                if (f.descuento != null && f.descuento > 0) detalle.append(String.format(Locale.US, " · descuento −$%.2f", f.descuento));
                detalle.append(String.format(Locale.US, " · impuesto $%.2f · total $%.2f", f.impuesto, f.total))
                       .append(igual ? "\n✓ Coincide con el cálculo del teléfono"
                                     : String.format(Locale.US, "\n⚠ Difiere del teléfono ($%.2f): manda el servidor", local));
                if (f.propuestaTotal != null && f.total != null && Math.abs(f.propuestaTotal - f.total) >= 0.01) {
                    detalle.append(String.format(Locale.US, "\nℹ Propuesta aprobada: $%.2f · facturado según trabajo realizado", f.propuestaTotal));
                }
                if (f.pagado != null) detalle.append(String.format(Locale.US, "\nPagado (confirmado por despacho): $%.2f · saldo $%.2f",
                        f.pagado, f.saldo != null ? f.saldo : 0));
                for (com.lc.technician.model.PaymentEntity p : pagosLocales) {
                    String st = p.syncState == 0 || p.syncState == 2 ? "guardado en el teléfono, sin confirmación del servidor"
                            : p.syncState == 3 ? "rechazado por el servidor: " + p.motivo
                            : "CONFIRMADO".equals(p.serverEstado) ? "confirmado por despacho"
                            : "RECHAZADO".equals(p.serverEstado) ? "rechazado por despacho: " + p.motivo
                            : "reportado · pendiente de confirmación de despacho";
                    detalle.append(String.format(Locale.US, "\n• Pago $%.2f %s · ", p.amount, p.method)).append(st);
                }
                if (f.pdfSyncState == 0 || f.pdfSyncState == 2) detalle.append("\nPDF: pendiente de envío al cliente");
                else if (f.pdfSyncState == 3) detalle.append("\nPDF rechazado: ").append(f.pdfMotivo);
                else if (f.pdfPath != null) detalle.append("\nPDF disponible para el cliente");
                if (f.numero != null) tvInvoiceNumero.setText(f.numero);
            }
            tvInvoiceStatus.setText(estado);
            tvInvoiceStatus.setBackgroundColor(color);
            tvInvoiceMetodoPago.setVisibility(View.VISIBLE);
            tvInvoiceMetodoPago.setText(detalle.toString());
            boolean emitida = f != null && f.syncState == 1 && f.estadoServidor != null && !"BORRADOR".equals(f.estadoServidor);
            boolean enCurso = f != null && (f.syncState == 0 || f.syncState == 2);
            boolean pagada = emitida && "PAGADA".equals(f.estadoServidor);
            boolean pagoEnCurso = false;
            for (com.lc.technician.model.PaymentEntity p : pagosLocales) {
                if (p.syncState == 0 || p.syncState == 2 || (p.syncState == 1 && "REPORTADO".equals(p.serverEstado))) pagoEnCurso = true;
            }
            if (pagada) {
                btnMarcarPagado.setText("✓ PAGADA (confirmada por despacho)");
                btnMarcarPagado.setEnabled(false); btnMarcarPagado.setAlpha(0.6f);
            } else if (emitida) {
                btnMarcarPagado.setText(pagoEnCurso ? "PAGO REPORTADO · ESPERANDO CONFIRMACIÓN" : "REGISTRAR PAGO REPORTADO");
                btnMarcarPagado.setEnabled(!pagoEnCurso); btnMarcarPagado.setAlpha(pagoEnCurso ? 0.6f : 1f);
                btnMarcarPagado.setOnClickListener(v -> dialogoPagoReportado());
            } else {
                btnMarcarPagado.setText(enCurso ? "ENVIANDO…" : "EMITIR FACTURA EN EL SERVIDOR");
                btnMarcarPagado.setEnabled(!enCurso); btnMarcarPagado.setAlpha(enCurso ? 0.6f : 1f);
                btnMarcarPagado.setOnClickListener(v -> confirmarEmision());
            }
            // Comparación con lo aprobado: con la factura emitida manda el servidor; antes, el cálculo del teléfono
            double totalFact = emitida && f.total != null ? f.total : totalLocal();
            Double aprob = f != null && f.aprobadoTotal != null ? f.aprobadoTotal : aprobadoLocal;
            String tipo = clasificar(aprob, totalFact);
            String estVar = f != null ? f.variacionEstado : null;
            pintarAviso(tipo, aprob == null ? 0 : aprob, totalFact, estVar, emitida, f != null && f.variacionId != null);
            if (!emitida && !enCurso && tipo != null && aprob != null) {
                if ("MAYOR".equals(tipo) || "ALCANCE".equals(tipo)) {
                    boolean pendiente = "PENDIENTE".equals(estVar), rechazada = "RECHAZADA".equals(estVar),
                            aprobada = "APROBADA".equals(estVar);
                    btnMarcarPagado.setText(aprobada ? "EMITIR FACTURA (DIFERENCIA APROBADA)"
                            : pendiente ? "ESPERANDO APROBACIÓN DEL CLIENTE"
                            : rechazada ? "EL CLIENTE RECHAZÓ LA DIFERENCIA" : "SOLICITAR APROBACIÓN DE LA DIFERENCIA");
                    btnMarcarPagado.setEnabled(!pendiente);
                    btnMarcarPagado.setAlpha(pendiente ? 0.6f : 1f);
                    btnMarcarPagado.setOnClickListener(v -> JobRepository.get().solicitarFacturaAsync(job.id, fs -> {
                        facturaServidor = fs;
                        pintarFacturaServidor();
                    }));
                } else if ("MENOR".equals(tipo)) {
                    btnMarcarPagado.setText("REVISAR Y CONFIRMAR IMPORTE MENOR");
                    final double a = aprob, tt = totalFact;
                    btnMarcarPagado.setOnClickListener(v -> confirmarMenor(a, tt));
                }
            }
        });
    }

    /** El técnico registra lo que el cliente dijo pagar; la factura no pasa a PAGADA hasta que despacho lo confirma. */
    private void dialogoPagoReportado() {
        // métodos de pago: lista editable desde Control (settings payment_methods.list)
        com.lc.technician.sync.Listas.refrescar(this, false, null);
        final com.lc.technician.sync.Listas.Lista metodosL = com.lc.technician.sync.Listas.lista(this, "payment_methods.list",
                new String[][]{{"EFECTIVO", "Efectivo"}, {"CHEQUE", "Cheque"}, {"TARJETA", "Tarjeta"}, {"ZELLE", "Zelle"},
                        {"TRANSFERENCIA", "Transferencia"}, {"OTRO", "Otro"}}, null);
        final String[] metodos = metodosL.ids;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(48, 16, 48, 0);
        android.widget.Spinner sp = new android.widget.Spinner(this);
        sp.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, metodosL.labels));
        c.addView(sp);
        android.widget.EditText monto = new android.widget.EditText(this);
        monto.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double saldo = facturaServidor != null && facturaServidor.saldo != null ? facturaServidor.saldo
                : facturaServidor != null && facturaServidor.total != null ? facturaServidor.total : 0;
        monto.setText(String.format(Locale.US, "%.2f", saldo));
        monto.setHint("Monto ($)");
        c.addView(monto);
        android.widget.EditText ref = new android.widget.EditText(this);
        ref.setHint("Referencia (opcional)");
        c.addView(ref);
        new AlertDialog.Builder(this)
                .setTitle("Registrar pago reportado")
                .setMessage("Se envía al centro de mando para confirmarlo. La factura no se marca pagada hasta su confirmación.")
                .setView(c)
                .setPositiveButton("REGISTRAR", (d, w) -> {
                    double m;
                    try { m = Double.parseDouble(monto.getText().toString()); } catch (Exception e) { m = 0; }
                    if (m <= 0) { Toast.makeText(this, "Monto inválido", Toast.LENGTH_SHORT).show(); return; }
                    JobRepository.get().reportarPagoAsync(job.id, m, metodos[sp.getSelectedItemPosition()],
                            ref.getText().toString().trim(), this::pintarFacturaServidor);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String generarNumero(long jobId) {
        String fecha = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return "INV-" + fecha + "-" + jobId;
    }

    private void mostrarLineas(List<WorkItemEntity> items) {
        layoutLineas.removeAllViews();
        subtotal = 0;

        if (items == null || items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Sin líneas de servicio");
            empty.setTextColor(0xFF9A9A9A);
            empty.setTextSize(14f);
            empty.setPadding(0, 12, 0, 12);
            layoutLineas.addView(empty);
        } else {
            for (int i = 0; i < items.size(); i++) {
                WorkItemEntity w = items.get(i);
                View line = LayoutInflater.from(this)
                        .inflate(R.layout.item_invoice_line, layoutLineas, false);

                TextView tvTipo = line.findViewById(R.id.tvLineTipo);
                tvTipo.setText(w.tipo != null ? w.tipo : "?");
                tvTipo.setBackgroundColor("LABOR".equals(w.tipo) ? 0xFF1565C0 : 0xFF2E7D32);

                ((TextView) line.findViewById(R.id.tvLineDesc)).setText(
                        w.descripcion != null ? w.descripcion : "");

                double qty = w.cantidad != null ? w.cantidad : 1.0;
                double precio = w.precioUnitario != null ? w.precioUnitario : 0.0;
                ((TextView) line.findViewById(R.id.tvLineQtyPrice)).setText(
                        String.format(Locale.US, "%.1f × $%.2f", qty, precio));

                double lineTotal = w.total != null ? w.total : (qty * precio);
                subtotal += lineTotal;
                ((TextView) line.findViewById(R.id.tvLineTotal)).setText(
                        String.format(Locale.US, "$%.2f", lineTotal));

                layoutLineas.addView(line);

                if (i < items.size() - 1) {
                    View divider = new View(this);
                    divider.setBackgroundColor(0xFFEEEEEE);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    layoutLineas.addView(divider, lp);
                }
            }
        }

        // descuento autorizado por despacho (sync v2): se resta antes del impuesto, como en el servidor
        double desc  = com.lc.technician.sync.LabSession.activa(this)
                ? Math.min(com.lc.technician.sync.LabSession.descuento(this, job.id), subtotal) : 0;
        double tax   = (subtotal - desc) * TAX_RATE;
        double total = subtotal - desc + tax;
        tvSubtotal.setText(desc > 0 ? String.format(Locale.US, "$%.2f − $%.2f", subtotal, desc)
                                    : String.format(Locale.US, "$%.2f", subtotal));
        tvTax.setText(String.format(Locale.US, "$%.2f", tax));
        tvTotal.setText(String.format(Locale.US, "$%.2f", total));
    }

    private void actualizarEstadoPago() {
        // Check Room first, then fall back to SharedPreferences for legacy data
        JobRepository.get().getPaidPaymentAsync(job.id, payment -> {
            if (payment != null) {
                mostrarEstadoPagado(payment.method != null ? payment.method : "", payment.timestamp);
            } else {
                // Legacy fallback: check SharedPreferences
                SharedPreferences prefs = getSharedPreferences(PREFS_INVOICES, MODE_PRIVATE);
                boolean pagado = prefs.getBoolean("invoice_" + job.id + "_pagado", false);
                if (pagado) {
                    String metodo = prefs.getString("invoice_" + job.id + "_metodo", "");
                    long fecha = prefs.getLong("invoice_" + job.id + "_fecha", 0);
                    mostrarEstadoPagado(metodo, fecha);
                } else {
                    tvInvoiceStatus.setText("PENDIENTE");
                    tvInvoiceStatus.setBackgroundColor(0xFFEB1700);
                    btnMarcarPagado.setText("MARCAR COMO PAGADO");
                    btnMarcarPagado.setEnabled(true);
                    btnMarcarPagado.setAlpha(1f);
                    tvInvoiceMetodoPago.setVisibility(View.GONE);
                }
            }
        });
    }

    private void mostrarEstadoPagado(String metodo, long fecha) {
        tvInvoiceStatus.setText("PAGADO");
        tvInvoiceStatus.setBackgroundColor(0xFF137333);
        btnMarcarPagado.setText("✓ PAGADO");
        btnMarcarPagado.setEnabled(false);
        btnMarcarPagado.setAlpha(0.6f);
        String fechaStr = fecha > 0 ? FMT.format(new Date(fecha)) : "";
        if (!metodo.isEmpty()) {
            tvInvoiceMetodoPago.setText("Pagado con " + metodo + (fechaStr.isEmpty() ? "" : " · " + fechaStr));
            tvInvoiceMetodoPago.setVisibility(View.VISIBLE);
        }
    }

    private void mostrarDialogoPago() {
        String[] metodos = com.lc.technician.sync.Listas.lista(this, "payment_methods.list",
                new String[][]{{"EFECTIVO", "Efectivo"}, {"CHEQUE", "Cheque"}, {"TARJETA", "Tarjeta"}, {"ZELLE", "Zelle"},
                        {"TRANSFERENCIA", "Transferencia"}, {"OTRO", "Otro"}}, null).labels;
        new AlertDialog.Builder(this)
                .setTitle("Método de pago")
                .setItems(metodos, (dialog, which) -> registrarPago(metodos[which]))
                .show();
    }

    private void registrarPago(String metodo) {
        long now = System.currentTimeMillis();
        double total = subtotal * (1 + TAX_RATE);

        // Save to Room (primary storage)
        PaymentEntity payment = new PaymentEntity();
        payment.id = "pay_" + job.id + "_" + now;
        payment.jobId = job.id;
        payment.amount = total;
        payment.method = metodo;
        payment.status = "PAID";
        payment.timestamp = now;
        payment.cliente = job.cliente != null ? job.cliente : "";
        JobRepository.get().insertPaymentAsync(payment, null);

        // Keep SharedPreferences in sync for legacy PagosActivity fallback
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_INVOICES, MODE_PRIVATE).edit();
        ed.putBoolean("invoice_" + job.id + "_pagado", true);
        ed.putString("invoice_" + job.id + "_metodo", metodo);
        ed.putLong("invoice_" + job.id + "_fecha", now);
        ed.putFloat("invoice_" + job.id + "_total", (float) total);
        ed.putString("invoice_" + job.id + "_cliente", job.cliente != null ? job.cliente : "");
        ed.apply();
        java.util.Set<String> ids = new java.util.HashSet<>(
                getSharedPreferences(PREFS_INVOICES, MODE_PRIVATE)
                        .getStringSet("pagos_ids", new java.util.HashSet<>()));
        ids.add(String.valueOf(job.id));
        getSharedPreferences(PREFS_INVOICES, MODE_PRIVATE).edit()
                .putStringSet("pagos_ids", ids).apply();

        actualizarEstadoPago();
        Toast.makeText(this, "Pago registrado con " + metodo, Toast.LENGTH_SHORT).show();
    }

    private void generarPdf() {
        if (workItems == null) {
            Toast.makeText(this, "Cargando datos…", Toast.LENGTH_SHORT).show();
            return;
        }
        btnGenerarPdf.setEnabled(false);
        new Thread(() -> {
            try {
                com.lc.technician.model.InvoiceSyncEntity fs = facturaServidor;
                boolean emitida = modoServidor && fs != null && fs.syncState == 1 && fs.numero != null;
                double descPdf = emitida && fs.descuento != null ? fs.descuento
                        : modoServidor ? com.lc.technician.sync.LabSession.descuento(this, job.id) : 0;
                pdfFile = ReportePdfGenerator.generarFactura(this, job, workItems,
                        emitida ? fs.numero : null, emitida ? fs.estadoServidor : null, descPdf);
                if (emitida) JobRepository.get().enviarPdfFacturaAsync(job.id, pdfFile.getAbsolutePath(), null);
                runOnUiThread(() -> {
                    btnGenerarPdf.setEnabled(true);
                    Toast.makeText(this, "PDF guardado: " + pdfFile.getName()
                            + (emitida ? " · se enviará al cliente" : ""), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnGenerarPdf.setEnabled(true);
                    Toast.makeText(this, "Error generando PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void compartirPdf() {
        if (pdfFile == null || !pdfFile.exists()) {
            generarPdf();
            Toast.makeText(this, "Generando PDF, intentá de nuevo en un momento", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/pdf");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir factura"));
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
