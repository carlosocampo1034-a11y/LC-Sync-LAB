package com.lc.technician.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Cola local de la factura de una orden (sync v2). La factura la construye y emite el servidor con los trabajos
 * sincronizados; aquí se guarda la petición (pendiente/en tránsito) y lo que el servidor confirmó.
 */
@Entity(tableName = "invoice_sync")
public class InvoiceSyncEntity {
    @PrimaryKey public long jobId;
    @NonNull public String clientInvoiceId = "";
    /** 0 pendiente · 2 en tránsito · 1 confirmada por el servidor · 3 rechazada */
    public int syncState;
    public Long serverId;
    public String numero;
    public String estadoServidor;
    public Double subtotal;
    public Double impuesto;
    public Double total;
    public String motivo;
    public long updatedAt;

    // PDF generado en el teléfono para el cliente: 1 = nada que enviar · 0 = pendiente · 2 = en tránsito · 3 = rechazado
    public String pdfPath;
    @androidx.room.ColumnInfo(defaultValue = "1") public int pdfSyncState = 1;
    public String pdfMotivo;
    public Double descuento;
    public Double pagado;
    public Double saldo;
    public Double propuestaTotal;

    // Factura frente a la propuesta aprobada (regla: advertir y pedir aprobación; nunca ajustar el importe)
    public Double aprobadoTotal;
    public Double diferencia;
    /** MAYOR | ALCANCE | MENOR | IGUAL */
    public String tipoComparacion;
    public Long variacionId;
    /** PENDIENTE | APROBADA | RECHAZADA | SUPERADA (aprobación adicional del cliente) */
    public String variacionEstado;
    /** código del último rechazo del servidor: REQUIERE_APROBACION_CLIENTE | CONFIRMAR_MENOR | CLIENTE_RECHAZO … */
    public String codigoRechazo;
    @androidx.room.ColumnInfo(defaultValue = "0") public int confirmaMenor;
}
