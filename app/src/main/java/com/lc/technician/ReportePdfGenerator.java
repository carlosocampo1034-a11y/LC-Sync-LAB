package com.lc.technician;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.ContactEntity;
import com.lc.technician.model.CoverageTypeEntity;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.FieldMeasurementEntity;
import com.lc.technician.model.InspectionItemEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.JobEventEntity;
import com.lc.technician.model.JobPartEntity;
import com.lc.technician.model.PartEntity;
import com.lc.technician.model.WorkItemEntity;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.ProposalOptionEntity;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.model.TimeStats;
import com.lc.technician.util.CoverageCalculator;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReportePdfGenerator {

    // Page dimensions: Letter 612 x 792 pt
    private static final int PAGE_W = 612;
    private static final int PAGE_H = 792;
    private static final int MARGIN = 40;
    private static final int CONTENT_W = PAGE_W - MARGIN * 2;
    private static final float LINE_H = 18f;

    private static final int COLOR_BLACK  = Color.rgb(25, 25, 25);
    private static final int COLOR_GRAY   = Color.rgb(118, 118, 118);
    private static final int COLOR_RED    = Color.rgb(235, 23, 0);
    private static final int COLOR_BLUE   = Color.rgb(27, 113, 201);
    private static final int COLOR_BORDER = Color.rgb(220, 220, 220);

    private static final SimpleDateFormat FMT_TS =
            new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.forLanguageTag("es"));
    private static final SimpleDateFormat FMT_SHORT =
            new SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es"));

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos) throws Exception {
        return generar(ctx, job, fotos, contactos, eventos,
                       new java.util.ArrayList<>(), new java.util.ArrayList<>());
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones) throws Exception {
        return generar(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones, null, null);
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions) throws Exception {
        return generar(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones,
                       proposal, propOptions, null);
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions,
                               SignatureEntity signature) throws Exception {
        return generar(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones,
                       proposal, propOptions, signature, null);
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions,
                               SignatureEntity signature,
                               TimeStats timeStats) throws Exception {
        return generar(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones,
                       proposal, propOptions, signature, timeStats, null, null);
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions,
                               SignatureEntity signature,
                               TimeStats timeStats,
                               CustomerEntity customer,
                               List<Job> previousJobs) throws Exception {
        return generar(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones,
                       proposal, propOptions, signature, timeStats, customer, previousJobs,
                       null, null);
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions,
                               SignatureEntity signature,
                               TimeStats timeStats,
                               CustomerEntity customer,
                               List<Job> previousJobs,
                               List<JobPartEntity> partes,
                               java.util.Map<Long, PartEntity> partesMap,
                               List<WorkItemEntity> workItems) throws Exception {
        // delegate to legacy method and inject workItems section
        return generarFull(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones,
                proposal, propOptions, signature, timeStats, customer, previousJobs,
                partes, partesMap, workItems);
    }

    public static File generar(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions,
                               SignatureEntity signature,
                               TimeStats timeStats,
                               CustomerEntity customer,
                               List<Job> previousJobs,
                               List<JobPartEntity> partes,
                               java.util.Map<Long, PartEntity> partesMap) throws Exception {
        return generarFull(ctx, job, fotos, contactos, eventos, inspeccionItems, mediciones,
                proposal, propOptions, signature, timeStats, customer, previousJobs,
                partes, partesMap, null);
    }

    private static File generarFull(Context ctx, Job job,
                               List<PhotoEntity> fotos,
                               List<ContactEntity> contactos,
                               List<JobEventEntity> eventos,
                               List<InspectionItemEntity> inspeccionItems,
                               List<FieldMeasurementEntity> mediciones,
                               ProposalEntity proposal,
                               List<ProposalOptionEntity> propOptions,
                               SignatureEntity signature,
                               TimeStats timeStats,
                               CustomerEntity customer,
                               List<Job> previousJobs,
                               List<JobPartEntity> partes,
                               java.util.Map<Long, PartEntity> partesMap,
                               List<WorkItemEntity> workItems) throws Exception {

        File dir = new File(ctx.getExternalFilesDir(null), "reportes");
        dir.mkdirs();
        File out = new File(dir, "reporte_" + job.id + "_" + System.currentTimeMillis() + ".pdf");

        PdfDocument doc = new PdfDocument();
        String fechaGen = FMT_TS.format(new Date());

        // ── Paints ───────────────────────────────────────────────────────────
        Paint pTitle = makePaint(COLOR_BLACK, 22f, true);
        Paint pSub   = makePaint(COLOR_GRAY,  13f, false);
        Paint pSec   = makePaint(COLOR_RED,   11f, true);
        Paint pLabel = makePaint(COLOR_GRAY,  10f, false);
        Paint pValue = makePaint(COLOR_BLACK, 12f, true);
        Paint pBody  = makePaint(COLOR_BLACK, 11f, false);
        Paint pFoot  = makePaint(COLOR_GRAY,   9f, false);
        Paint pLine  = new Paint();
        pLine.setColor(COLOR_BORDER);
        pLine.setStrokeWidth(1f);

        // ── Página 1 ──────────────────────────────────────────────────────────
        PageCtx pg = startPage(doc, 1);

        // Encabezado
        pg.y = MARGIN;
        pg.canvas.drawText("REPORTE DE SERVICIO", MARGIN, pg.y += 28, pTitle);
        pg.canvas.drawText("L&C Servicios Técnicos", MARGIN, pg.y += 18, pSub);
        pg.canvas.drawText(fechaGen, PAGE_W - MARGIN, pg.y, alignRight(pSub));
        pg.y += 10;
        pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
        pg.y += 20;

        // Sección: Cliente
        if (customer != null) {
            drawSection(pg, "CLIENTE", pSec);
            drawField(pg, "Nombre",   customer.nombre,   pLabel, pValue);
            drawField(pg, "Teléfono", customer.telefono, pLabel, pValue);
            drawField(pg, "Email",    customer.email,    pLabel, pValue);
            pg.y += 6;
        }

        // Sección: Información del trabajo
        drawSection(pg, "INFORMACIÓN DEL TRABAJO", pSec);
        drawField(pg, "Cliente",     job.cliente,     pLabel, pValue);
        drawField(pg, "Dirección",   job.direccion,   pLabel, pValue);
        drawField(pg, "Teléfono",    job.telefono,    pLabel, pValue);
        drawField(pg, "Hora",        job.hora,        pLabel, pValue);
        drawField(pg, "Estado",      job.estado,      pLabel, pValue);
        drawField(pg, "Equipo",      job.equipo,      pLabel, pValue);
        String tecnicoPdf = extraerTecnicoDeNotas(job.notas);
        drawField(pg, "Técnico asignado", tecnicoPdf, pLabel, pValue);
        if (job.descripcion != null && !job.descripcion.isEmpty()) {
            drawLongField(pg, "Descripción", job.descripcion, pLabel, pBody);
        }

        // Sección: Contactos
        pg.y += 10;
        drawSection(pg, "CONTACTOS", pSec);
        if (contactos == null || contactos.isEmpty()) {
            pg.canvas.drawText("Sin contactos registrados", MARGIN, pg.y += LINE_H, pBody);
        } else {
            for (ContactEntity c : contactos) {
                if (pg.y > PAGE_H - MARGIN - 60) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                String chip = c.rol != null ? "[" + c.rol + "] " : "";
                Paint pChip = makePaint(COLOR_BLUE, 10f, true);
                pg.canvas.drawText(chip, MARGIN, pg.y += LINE_H + 4, pChip);
                float chipW = pChip.measureText(chip);
                pg.canvas.drawText(c.nombre != null ? c.nombre : "", MARGIN + chipW, pg.y, pValue);
                String sub = "";
                if (c.telefono != null && !c.telefono.isEmpty()) sub += c.telefono;
                if (c.email    != null && !c.email.isEmpty())    sub += (sub.isEmpty() ? "" : "  ·  ") + c.email;
                if (!sub.isEmpty()) pg.canvas.drawText(sub, MARGIN + 16, pg.y += 14, pBody);
            }
        }

        // Sección: Notas del técnico
        pg.y += 14;
        if (pg.y > PAGE_H - MARGIN - 80) {
            finPage(doc, pg, pFoot, fechaGen);
            pg = startPage(doc, doc.getPages().size() + 1);
            pg.y = MARGIN + 20;
        }
        drawSection(pg, "NOTAS DEL TÉCNICO", pSec);
        String notas = limpiarNotasTecnico(job.notas);
        pg.y = drawWrapped(pg.canvas, notas, MARGIN, pg.y, CONTENT_W, LINE_H, pBody);

        // ── Inspección Técnica ────────────────────────────────────────────────
        if (inspeccionItems != null && !inspeccionItems.isEmpty()) {
            if (pg.y > PAGE_H - MARGIN - 100) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "INSPECCI\u00d3N T\u00c9CNICA", pSec);

            Paint pPass  = makePaint(Color.rgb(19, 115, 51), 10f, true);
            Paint pIssue = makePaint(COLOR_RED, 10f, true);
            Paint pNa    = makePaint(COLOR_GRAY, 10f, true);

            String currentArea = null;
            for (InspectionItemEntity item : inspeccionItems) {
                if (pg.y > PAGE_H - MARGIN - 50) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                if (!item.area.equals(currentArea)) {
                    currentArea = item.area;
                    pg.canvas.drawText(currentArea, MARGIN, pg.y += LINE_H + 4, pLabel);
                }
                String chip;
                Paint pChip;
                if ("PASS".equals(item.estado)) { chip = "[PASS]"; pChip = pPass; }
                else if ("ISSUE".equals(item.estado)) { chip = "[ISSUE]"; pChip = pIssue; }
                else if ("NA".equals(item.estado)) { chip = "[N/A]"; pChip = pNa; }
                else { chip = "[\u2014]"; pChip = pNa; }

                float chipW = pChip.measureText(chip);
                pg.canvas.drawText(chip, MARGIN + 16, pg.y += LINE_H, pChip);
                pg.canvas.drawText(item.nombre != null ? item.nombre : "", MARGIN + 16 + chipW + 6, pg.y, pBody);

                if ("ISSUE".equals(item.estado)) {
                    if (item.hallazgo != null && !item.hallazgo.isEmpty()) {
                        pg.y = drawWrapped(pg.canvas, item.hallazgo, MARGIN + 32, pg.y + 12, CONTENT_W - 32, 14f, pBody);
                    }
                    if (item.resolucion != null && !item.resolucion.isEmpty()) {
                        pg.canvas.drawText("\u2192 Resoluci\u00f3n: " + item.resolucion, MARGIN + 32, pg.y += 14, pBody);
                    }
                }
            }

            if (mediciones != null && !mediciones.isEmpty()) {
                if (pg.y > PAGE_H - MARGIN - 60) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                pg.y += 8;
                pg.canvas.drawText("MEDICIONES", MARGIN, pg.y += 14, pSec);
                pg.y += 4;
                for (FieldMeasurementEntity m : mediciones) {
                    if (pg.y > PAGE_H - MARGIN - 30) {
                        finPage(doc, pg, pFoot, fechaGen);
                        pg = startPage(doc, doc.getPages().size() + 1);
                        pg.y = MARGIN + 20;
                    }
                    String mLabel = m.tipo + "  \u00b7  " +
                            (m.ubicacion != null ? m.ubicacion : "") +
                            "  \u00b7  " + m.valor + " " + m.unidad;
                    pg.canvas.drawText(mLabel, MARGIN, pg.y += LINE_H, pBody);
                    if (m.nota != null && !m.nota.isEmpty()) {
                        pg.canvas.drawText(m.nota, MARGIN + 16, pg.y += 13, pLabel);
                    }
                }
            }
        }

        // ── Propuesta ─────────────────────────────────────────────────────────
        if (proposal != null && propOptions != null && !propOptions.isEmpty()) {
            if (pg.y > PAGE_H - MARGIN - 100) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "PROPUESTA", pSec);

            Paint pGood   = makePaint(Color.rgb(19, 115, 51),  10f, true);
            Paint pBetter = makePaint(Color.rgb(27, 113, 201), 10f, true);
            Paint pBest   = makePaint(Color.rgb(255, 143, 0),  10f, true);

            for (ProposalOptionEntity opt : propOptions) {
                if (pg.y > PAGE_H - MARGIN - 50) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                Paint pChip;
                if ("GOOD".equals(opt.nivel))        pChip = pGood;
                else if ("BETTER".equals(opt.nivel)) pChip = pBetter;
                else                                  pChip = pBest;

                String chip = "[" + opt.nivel + "] ";
                float chipW = pChip.measureText(chip);
                pg.canvas.drawText(chip, MARGIN, pg.y += LINE_H + 4, pChip);
                String titleLine = (opt.titulo != null ? opt.titulo : opt.nivel) +
                        "  —  $" + String.format(java.util.Locale.US, "%,.0f", opt.totalCalculado);
                if (proposal.opcionAceptada != null && proposal.opcionAceptada.equals(opt.nivel)) {
                    titleLine += "  ✓ ACEPTADA";
                }
                pg.canvas.drawText(titleLine, MARGIN + chipW, pg.y, pValue);

                if (opt.descripcion != null && !opt.descripcion.isEmpty()) {
                    pg.y = drawWrapped(pg.canvas, opt.descripcion, MARGIN + 20, pg.y + 13, CONTENT_W - 20, 14f, pBody);
                }
            }

            // If an option was accepted, draw its cost breakdown
            if (proposal.opcionAceptada != null) {
                ProposalOptionEntity accepted = null;
                for (ProposalOptionEntity opt : propOptions) {
                    if (proposal.opcionAceptada.equals(opt.nivel)) { accepted = opt; break; }
                }
                if (accepted != null) {
                    if (pg.y > PAGE_H - MARGIN - 120) {
                        finPage(doc, pg, pFoot, fechaGen);
                        pg = startPage(doc, doc.getPages().size() + 1);
                        pg.y = MARGIN + 20;
                    }
                    pg.y += 8;
                    pg.canvas.drawText("Desglose — " + accepted.nivel, MARGIN, pg.y += LINE_H, pLabel);
                    if (accepted.costoEquipo > 0)
                        drawField(pg, "Equipo",        String.format(java.util.Locale.US, "$%,.2f", accepted.costoEquipo),        pLabel, pBody);
                    if (accepted.costoManoObra > 0)
                        drawField(pg, "Mano de obra",  String.format(java.util.Locale.US, "$%,.2f", accepted.costoManoObra),  pLabel, pBody);
                    if (accepted.costoMateriales > 0)
                        drawField(pg, "Materiales",    String.format(java.util.Locale.US, "$%,.2f", accepted.costoMateriales),    pLabel, pBody);
                    if (accepted.costoPermiso > 0)
                        drawField(pg, "Permiso",       String.format(java.util.Locale.US, "$%,.2f", accepted.costoPermiso),       pLabel, pBody);
                    if (accepted.costoRemocion > 0)
                        drawField(pg, "Remoci\u00f3n", String.format(java.util.Locale.US, "$%,.2f", accepted.costoRemocion), pLabel, pBody);
                    if (accepted.costoAccesorios > 0)
                        drawField(pg, "Accesorios",    String.format(java.util.Locale.US, "$%,.2f", accepted.costoAccesorios),    pLabel, pBody);
                    if (accepted.markupPorcentaje > 0)
                        drawField(pg, "Markup",        accepted.markupPorcentaje + "%",               pLabel, pBody);
                    if (accepted.descuentoMonto > 0)
                        drawField(pg, "Descuento",     String.format(java.util.Locale.US, "-$%,.2f", accepted.descuentoMonto),   pLabel, pBody);
                    if (accepted.impuestoPorcentaje > 0)
                        drawField(pg, "Impuesto",      accepted.impuestoPorcentaje + "%",              pLabel, pBody);
                    drawField(pg, "TOTAL",             String.format(java.util.Locale.US, "$%,.2f", accepted.totalCalculado),     pLabel, pValue);
                    // FASE 16: Coverage breakdown in PDF
                    CoverageTypeEntity covPdf = null;
                    if (job.coverageTypeId != null && job.coverageTypeId > 0) {
                        try { covPdf = LcDatabase.getInstance(ctx).coverageDao().getById(job.coverageTypeId); }
                        catch (Exception ignored) {}
                    }
                    CoverageCalculator.Result covR = CoverageCalculator.calcular(covPdf, accepted.totalCalculado);
                    if (covR.hasCoverage) {
                        Paint pCovLabel = makePaint(Color.rgb(121, 85, 72), 10f, false);
                        Paint pCovValue = makePaint(Color.rgb(46, 125, 50), 11f, true);
                        drawField(pg, "Deducible",                    String.format(java.util.Locale.US, "$%,.2f", covR.deductible),     pCovLabel, pCovLabel);
                        drawField(pg, String.format(java.util.Locale.US, "Cubierto (%.0f%%)", covR.coveragePct), String.format(java.util.Locale.US, "$%,.2f", covR.totalInsurer), pCovLabel, pCovLabel);
                        drawField(pg, "CLIENTE PAGA",                 String.format(java.util.Locale.US, "$%,.2f", covR.totalCliente),    pCovLabel, pCovValue);
                    }
                }
            }
        }

        // ── Tiempos del servicio ──────────────────────────────────────────────
        if (timeStats != null && (timeStats.onMyWay != null || timeStats.arrived != null || timeStats.offSite != null)) {
            if (pg.y > PAGE_H - MARGIN - 120) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "TIEMPOS DEL SERVICIO", pSec);
            SimpleDateFormat fmtHora = new SimpleDateFormat("h:mm a", Locale.forLanguageTag("es"));
            if (timeStats.onMyWay != null)
                drawField(pg, "Salida hacia el cliente",
                        fmtHora.format(new Date(timeStats.onMyWay.timestamp)), pLabel, pValue);
            if (timeStats.arrived != null)
                drawField(pg, "Llegada al sitio",
                        fmtHora.format(new Date(timeStats.arrived.timestamp)), pLabel, pValue);
            if (timeStats.offSite != null)
                drawField(pg, "Salida del sitio",
                        fmtHora.format(new Date(timeStats.offSite.timestamp)), pLabel, pValue);

            StringBuilder resumen = new StringBuilder();
            if (timeStats.tiempoViajeMinutos >= 0) {
                resumen.append("Tiempo de viaje: ").append(pdfFormatDuration(timeStats.tiempoViajeMinutos));
            }
            if (timeStats.tiempoEnSitioMinutos >= 0) {
                if (resumen.length() > 0) resumen.append("  ·  ");
                resumen.append("Tiempo en sitio: ").append(pdfFormatDuration(timeStats.tiempoEnSitioMinutos));
            }
            if (timeStats.tiempoTotalMinutos >= 0) {
                if (resumen.length() > 0) resumen.append("  ·  ");
                resumen.append("Tiempo total: ").append(pdfFormatDuration(timeStats.tiempoTotalMinutos));
            }
            if (resumen.length() > 0) {
                pg.canvas.drawText(resumen.toString(), MARGIN, pg.y += LINE_H + 4, pBody);
            }
        }

        // ── Firma del cliente ─────────────────────────────────────────────────
        if (signature != null) {
            if (pg.y > PAGE_H - MARGIN - 200) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "FIRMA DEL CLIENTE", pSec);
            drawField(pg, "Aceptado por", signature.nombreFirmante, pLabel, pValue);
            drawField(pg, "Fecha", FMT_TS.format(new Date(signature.timestamp)), pLabel, pValue);
            String opStr = signature.opcionAceptada != null ? signature.opcionAceptada : "";
            drawField(pg, "Opci\u00f3n", opStr + String.format(java.util.Locale.US, "  $%,.2f", signature.totalAceptado), pLabel, pValue);
            if (signature.notasFirma != null && !signature.notasFirma.isEmpty()) {
                drawField(pg, "Notas", signature.notasFirma, pLabel, pBody);
            }

            // Draw signature image
            if (signature.imagePath != null) {
                Bitmap sigBmp = decodeBitmap(signature.imagePath);
                if (sigBmp != null) {
                    int maxW = CONTENT_W / 2;
                    int sigH = (int) ((long) sigBmp.getHeight() * maxW / sigBmp.getWidth());
                    if (pg.y + sigH + 30 > PAGE_H - MARGIN - 30) {
                        finPage(doc, pg, pFoot, fechaGen);
                        pg = startPage(doc, doc.getPages().size() + 1);
                        pg.y = MARGIN + 20;
                    }
                    pg.y += 10;
                    android.graphics.RectF sigRect = new android.graphics.RectF(
                            MARGIN, pg.y, MARGIN + maxW, pg.y + sigH);
                    pg.canvas.drawBitmap(sigBmp, null, sigRect, null);
                    pg.y += sigH + 6;
                    pg.canvas.drawText("Firma del cliente", MARGIN, pg.y += 14, pSub);
                    sigBmp.recycle();
                }
            }
        }

        // ── Trabajo realizado ─────────────────────────────────────────────────
        if (workItems != null && !workItems.isEmpty()) {
            if (pg.y > PAGE_H - MARGIN - 100) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "TRABAJO REALIZADO", pSec);

            float colTipo  = MARGIN;
            float colDesc  = MARGIN + 60;
            float colCant2 = MARGIN + CONTENT_W * 0.55f;
            float colPU    = MARGIN + CONTENT_W * 0.70f;
            float colTot2  = MARGIN + CONTENT_W * 0.85f;

            Paint pTh2 = makePaint(COLOR_GRAY, 9f, true);
            pg.canvas.drawText("Tipo",    colTipo,  pg.y += LINE_H, pTh2);
            pg.canvas.drawText("Descripción", colDesc, pg.y,         pTh2);
            pg.canvas.drawText("Cant",    colCant2, pg.y,            pTh2);
            pg.canvas.drawText("P.Unit",  colPU,    pg.y,            pTh2);
            pg.canvas.drawText("Total",   colTot2,  pg.y,            pTh2);
            pg.y += 4;
            pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
            pg.y += 4;

            double totalLabor = 0, totalMateriales = 0;
            for (WorkItemEntity wi : workItems) {
                if (pg.y > PAGE_H - MARGIN - 30) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                double tot = wi.total != null ? wi.total : 0.0;
                double pu  = wi.precioUnitario != null ? wi.precioUnitario : 0.0;
                double q   = wi.cantidad != null ? wi.cantidad : 1.0;
                String tipo = wi.tipo != null ? wi.tipo : "";
                String desc = wi.descripcion != null ? wi.descripcion : "";
                if (desc.length() > 28) desc = desc.substring(0, 26) + "…";
                pg.canvas.drawText(tipo, colTipo,  pg.y += LINE_H, pBody);
                pg.canvas.drawText(desc, colDesc,  pg.y,           pBody);
                pg.canvas.drawText(String.format(java.util.Locale.US, "%.0f", q),   colCant2, pg.y, pBody);
                pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", pu), colPU,    pg.y, pBody);
                pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", tot),colTot2,  pg.y, pBody);
                if ("LABOR".equals(tipo)) totalLabor += tot;
                else totalMateriales += tot;
            }
            pg.y += 6;
            pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
            pg.y += 4;
            Paint pBold = makePaint(COLOR_BLACK, 10f, true);
            pg.canvas.drawText("Mano de obra:", colDesc, pg.y += LINE_H, pBold);
            pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", totalLabor), colTot2, pg.y, pBold);
            pg.canvas.drawText("Materiales:", colDesc, pg.y += LINE_H, pBold);
            pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", totalMateriales), colTot2, pg.y, pBold);
            pg.canvas.drawText("TOTAL:", colDesc, pg.y += LINE_H, pBold);
            pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", totalLabor + totalMateriales), colTot2, pg.y, pBold);
        }

        // ── Repuestos usados ──────────────────────────────────────────────────
        if (partes != null && !partes.isEmpty()) {
            if (pg.y > PAGE_H - MARGIN - 100) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "REPUESTOS USADOS", pSec);

            float colCant  = MARGIN;
            float colNom   = MARGIN + 40;
            float colUnit  = MARGIN + CONTENT_W * 0.6f;
            float colTotal = MARGIN + CONTENT_W * 0.82f;

            Paint pTh = makePaint(COLOR_GRAY, 9f, true);
            pg.canvas.drawText("Cant", colCant, pg.y += LINE_H, pTh);
            pg.canvas.drawText("Nombre", colNom, pg.y, pTh);
            pg.canvas.drawText("P.Unit", colUnit, pg.y, pTh);
            pg.canvas.drawText("Total", colTotal, pg.y, pTh);
            pg.y += 4;
            pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
            pg.y += 4;

            double totalRepuestos = 0;
            for (JobPartEntity jp : partes) {
                if (pg.y > PAGE_H - MARGIN - 30) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                String nombrePart = "Repuesto #" + jp.partId;
                if (partesMap != null && partesMap.containsKey(jp.partId)) {
                    PartEntity pe = partesMap.get(jp.partId);
                    if (pe != null && pe.nombre != null) nombrePart = pe.nombre;
                }
                double subtotal = jp.cantidad * jp.precioUnitario;
                totalRepuestos += subtotal;
                pg.canvas.drawText(String.valueOf(jp.cantidad), colCant, pg.y += LINE_H, pBody);
                pg.canvas.drawText(nombrePart, colNom, pg.y, pBody);
                pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", jp.precioUnitario), colUnit, pg.y, pBody);
                pg.canvas.drawText(String.format(java.util.Locale.US, "$%.2f", subtotal), colTotal, pg.y, pBody);
            }
            pg.y += 4;
            pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
            pg.y += 4;
            String totalStr = String.format(java.util.Locale.US, "TOTAL REPUESTOS:  $%,.2f", totalRepuestos);
            pg.canvas.drawText(totalStr, PAGE_W - MARGIN, pg.y += LINE_H, alignRight(pValue));
        }

        // ── Páginas de fotos ──────────────────────────────────────────────────
        String[] cats   = {"ANTES", "DESPU\u00c9S", "EQUIPO", "NAMEPLATE"}; // DESPUÉS
        if (fotos != null && !fotos.isEmpty()) {
            for (String cat : cats) {
                boolean firstInCat = true;
                for (PhotoEntity photo : fotos) {
                    if (!cat.equals(photo.categoria)) continue;
                    Bitmap bmp = decodeBitmap(photo.path);
                    if (bmp == null) continue;

                    int bmpH = (int) ((long) bmp.getHeight() * CONTENT_W / bmp.getWidth());
                    int needed = bmpH + 50;

                    if (pg.y + needed > PAGE_H - MARGIN - 30) {
                        finPage(doc, pg, pFoot, fechaGen);
                        pg = startPage(doc, doc.getPages().size() + 1);
                        pg.y = MARGIN + 20;
                        firstInCat = true;
                    }

                    if (firstInCat) {
                        drawSection(pg, "EVIDENCIAS — " + cat, pSec);
                        firstInCat = false;
                    }

                    android.graphics.RectF rect = new android.graphics.RectF(
                            MARGIN, pg.y + 6, MARGIN + CONTENT_W, pg.y + 6 + bmpH);
                    pg.canvas.drawBitmap(bmp, null, rect, null);
                    pg.y += bmpH + 10;

                    String caption = cat + "  ·  " + FMT_SHORT.format(new Date(photo.timestamp));
                    pg.canvas.drawText(caption, MARGIN, pg.y += 12, pSub);
                    pg.y += 8;
                    bmp.recycle();
                }
            }
        }

        // ── Historial ──────────────────────────────────────────────────────────
        if (eventos != null && !eventos.isEmpty()) {
            if (pg.y > PAGE_H - MARGIN - 100) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "HISTORIAL DEL TRABAJO", pSec);
            for (JobEventEntity ev : eventos) {
                if (pg.y > PAGE_H - MARGIN - 30) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                String ts = FMT_TS.format(new Date(ev.timestamp));
                pg.canvas.drawText(ts, MARGIN, pg.y += LINE_H, pLabel);
                pg.canvas.drawText(ev.descripcion, MARGIN + 16, pg.y += 13, pBody);
            }
        }

        // ── Servicios anteriores del cliente ─────────────────────────────────
        if (previousJobs != null && !previousJobs.isEmpty()) {
            if (pg.y > PAGE_H - MARGIN - 80) {
                finPage(doc, pg, pFoot, fechaGen);
                pg = startPage(doc, doc.getPages().size() + 1);
                pg.y = MARGIN + 20;
            }
            pg.y += 10;
            drawSection(pg, "SERVICIOS ANTERIORES DEL CLIENTE", pSec);
            for (Job prev : previousJobs) {
                if (pg.y > PAGE_H - MARGIN - 30) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                String fecha = prev.updatedAt != null ? FMT_TS.format(new Date(prev.updatedAt)) : "Sin fecha";
                pg.canvas.drawText(fecha, MARGIN, pg.y += LINE_H, pLabel);
                String desc = (prev.descripcion != null && !prev.descripcion.isEmpty()
                        ? prev.descripcion : "Servicio")
                        + "  ·  " + (prev.estado != null ? prev.estado : "");
                pg.canvas.drawText(desc, MARGIN + 16, pg.y += 13, pBody);
            }
        }

        finPage(doc, pg, pFoot, fechaGen);

        // ── Guardar ────────────────────────────────────────────────────────────
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static class PageCtx {
        PdfDocument.Page page;
        Canvas canvas;
        float y;
    }

    private static PageCtx startPage(PdfDocument doc, int num) {
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, num).create();
        PdfDocument.Page page = doc.startPage(info);
        PageCtx pc = new PageCtx();
        pc.page   = page;
        pc.canvas = page.getCanvas();
        pc.canvas.drawColor(Color.WHITE);
        pc.y = MARGIN;
        return pc;
    }

    private static void finPage(PdfDocument doc, PageCtx pg, Paint pFoot, String fechaGen) {
        String foot = "Generado por L&C  ·  " + fechaGen;
        pFoot.setTextAlign(Paint.Align.CENTER);
        pg.canvas.drawLine(MARGIN, PAGE_H - MARGIN - 14, PAGE_W - MARGIN, PAGE_H - MARGIN - 14,
                makePaint(COLOR_BORDER, 0.5f, false));
        pg.canvas.drawText(foot, PAGE_W / 2f, PAGE_H - MARGIN, pFoot);
        pFoot.setTextAlign(Paint.Align.LEFT);
        doc.finishPage(pg.page);
    }

    private static void drawSection(PageCtx pg, String title, Paint p) {
        pg.y += 8;
        pg.canvas.drawText(title, MARGIN, pg.y += 14, p);
        pg.y += 6;
    }

    private static void drawField(PageCtx pg, String label, String value, Paint pL, Paint pV) {
        if (value == null || value.isEmpty()) return;
        pg.canvas.drawText(label, MARGIN, pg.y += LINE_H, pL);
        pg.canvas.drawText(value, MARGIN + 90, pg.y, pV);
    }

    private static void drawLongField(PageCtx pg, String label, String value, Paint pL, Paint pV) {
        pg.canvas.drawText(label, MARGIN, pg.y += LINE_H, pL);
        pg.y = drawWrapped(pg.canvas, value, MARGIN + 4, pg.y + 13, CONTENT_W - 4, 15f, pV);
    }

    private static float drawWrapped(Canvas canvas, String text, float x, float y,
                                     float maxW, float lineH, Paint p) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(test) > maxW) {
                if (line.length() > 0) canvas.drawText(line.toString(), x, y += lineH, p);
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) canvas.drawText(line.toString(), x, y += lineH, p);
        return y;
    }

    private static Paint makePaint(int color, float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    private static Paint alignRight(Paint p) {
        p.setTextAlign(Paint.Align.RIGHT);
        return p;
    }

    private static Bitmap decodeBitmap(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            opts.inSampleSize = calcSample(opts, CONTENT_W, CONTENT_W * 2);
            opts.inJustDecodeBounds = false;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;
            // Recomprimir a JPEG 80% para ahorrar memoria en el PDF
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            byte[] bytes = bos.toByteArray();
            bmp.recycle();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static int calcSample(BitmapFactory.Options opts, int reqW, int reqH) {
        int s = 1;
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            int hh = opts.outHeight / 2, hw = opts.outWidth / 2;
            while ((hh / s) >= reqH && (hw / s) >= reqW) s *= 2;
        }
        return s;
    }

    private static String pdfFormatDuration(long minutes) {
        if (minutes < 0) return "—";
        if (minutes < 60) return minutes + " min";
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? (h + "h") : (h + "h " + m + " min");
    }

    // ── Factura PDF ───────────────────────────────────────────────────────────

    public static File generarFactura(android.content.Context ctx, Job job,
                                      List<WorkItemEntity> items) throws Exception {
        return generarFactura(ctx, job, items, null, null);
    }

    /**
     * numeroServidor/estadoServidor: si la factura ya fue emitida por el servidor (sync v2), el PDF usa su número y
     * su estado (nunca "pagada" sin confirmación); si no, queda rotulada como borrador local.
     */
    public static File generarFactura(android.content.Context ctx, Job job, List<WorkItemEntity> items,
                                      String numeroServidor, String estadoServidor) throws Exception {
        return generarFactura(ctx, job, items, numeroServidor, estadoServidor, 0);
    }

    /** descuento: autorizado por despacho (se resta antes del impuesto, igual que en el servidor). */
    public static File generarFactura(android.content.Context ctx, Job job, List<WorkItemEntity> items,
                                      String numeroServidor, String estadoServidor, double descuento) throws Exception {
        File dir = new File(ctx.getExternalFilesDir(null), "facturas");
        dir.mkdirs();
        File out = new File(dir, "factura_" + job.id + ".pdf");

        PdfDocument doc = new PdfDocument();
        String fechaGen = FMT_SHORT.format(new Date());
        String numero   = numeroServidor != null
                ? numeroServidor + " · " + (estadoServidor != null ? estadoServidor : "EMITIDA")
                : "INV-" + new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date())
                          + "-" + job.id + " · BORRADOR LOCAL";

        Paint pTitle = makePaint(COLOR_BLACK, 20f, true);
        Paint pSub   = makePaint(COLOR_GRAY,  12f, false);
        Paint pSec   = makePaint(COLOR_RED,   10f, true);
        Paint pLabel = makePaint(COLOR_GRAY,  10f, false);
        Paint pValue = makePaint(COLOR_BLACK, 11f, true);
        Paint pBody  = makePaint(COLOR_BLACK, 11f, false);
        Paint pFoot  = makePaint(COLOR_GRAY,   9f, false);
        Paint pLine  = new Paint();
        pLine.setColor(COLOR_BORDER);
        pLine.setStrokeWidth(1f);

        PageCtx pg = startPage(doc, 1);
        pg.y = MARGIN + 10;

        // Header
        pg.canvas.drawText("L&C Technician", MARGIN, pg.y += 22, pTitle);
        pg.canvas.drawText("Servicios de Refrigeración y Aire Acondicionado", MARGIN, pg.y += 16, pSub);
        pg.canvas.drawText("Tel: (555) 000-0000  ·  info@lctechnician.com", MARGIN, pg.y += 14, pSub);
        pg.canvas.drawLine(MARGIN, pg.y += 10, PAGE_W - MARGIN, pg.y, pLine);

        // Invoice meta
        Paint pInvNum = makePaint(COLOR_BLACK, 14f, true);
        pg.canvas.drawText("FACTURA", PAGE_W - MARGIN - pTitle.measureText("FACTURA"),
                MARGIN + 10 + 22, pTitle);
        pg.y += 14;
        pg.canvas.drawText(numero, MARGIN, pg.y += 18, pInvNum);
        pg.canvas.drawText("Fecha: " + fechaGen, MARGIN, pg.y += 16, pSub);

        // Client
        pg.y += 14;
        pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
        drawSection(pg, "CLIENTE", pSec);
        drawField(pg, "Nombre",    job.cliente,   pLabel, pValue);
        drawField(pg, "Dirección", job.direccion, pLabel, pValue);
        drawField(pg, "Teléfono",  job.telefono,  pLabel, pValue);
        String tecnico = extraerTecnicoDeNotas(job.notas);
        if (!"Sin asignar".equals(tecnico)) {
            drawField(pg, "Técnico", tecnico, pLabel, pValue);
        }

        // Lines table
        pg.y += 10;
        pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);
        drawSection(pg, "LÍNEAS DE SERVICIO", pSec);

        // Column headers
        float colDesc  = MARGIN;
        float colQty   = MARGIN + 280;
        float colPrice = MARGIN + 360;
        float colTotal = PAGE_W - MARGIN;
        Paint pColHdr = makePaint(COLOR_GRAY, 9f, true);
        pg.y += 4;
        pg.canvas.drawText("DESCRIPCIÓN", colDesc, pg.y += 14, pColHdr);
        pg.canvas.drawText("CANT.", colQty,   pg.y, pColHdr);
        pg.canvas.drawText("PRECIO",    colPrice, pg.y, pColHdr);
        alignRight(pColHdr);
        pg.canvas.drawText("TOTAL",     colTotal, pg.y, pColHdr);
        pColHdr.setTextAlign(Paint.Align.LEFT);
        pg.y += 4;
        pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);

        double subtotal = 0;
        if (items != null) {
            for (WorkItemEntity w : items) {
                if (pg.y > PAGE_H - MARGIN - 80) {
                    finPage(doc, pg, pFoot, fechaGen);
                    pg = startPage(doc, doc.getPages().size() + 1);
                    pg.y = MARGIN + 20;
                }
                pg.y += 16;
                String desc = (w.descripcion != null ? w.descripcion : "") +
                              " [" + (w.tipo != null ? w.tipo : "") + "]";
                if (pBody.measureText(desc) > 260) desc = desc.substring(0, 38) + "…";
                pg.canvas.drawText(desc, colDesc, pg.y, pBody);

                double qty   = w.cantidad != null ? w.cantidad : 1.0;
                double price = w.precioUnitario != null ? w.precioUnitario : 0.0;
                double tot   = w.total != null ? w.total : qty * price;
                subtotal += tot;

                pg.canvas.drawText(String.format(Locale.US, "%.1f", qty),   colQty, pg.y, pBody);
                pg.canvas.drawText(String.format(Locale.US, "$%.2f", price), colPrice, pg.y, pBody);
                alignRight(pBody);
                pg.canvas.drawText(String.format(Locale.US, "$%.2f", tot),   colTotal, pg.y, pBody);
                pBody.setTextAlign(Paint.Align.LEFT);

                pg.y += 2;
                pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, makePaint(COLOR_BORDER, 0.5f, false));
            }
        }

        // Totals
        double desc  = Math.min(Math.max(descuento, 0), subtotal);
        double tax   = Math.round((subtotal - desc) * 0.07 * 100.0) / 100.0;
        double total = subtotal - desc + tax;
        pg.y += 14;
        float totX = PAGE_W - MARGIN - 120;
        Paint pTotL = makePaint(COLOR_GRAY, 11f, false);
        Paint pTotV = makePaint(COLOR_BLACK, 11f, false);
        pg.canvas.drawText("Subtotal", totX, pg.y, pTotL);
        alignRight(pTotV);
        pg.canvas.drawText(String.format(Locale.US, "$%.2f", subtotal), colTotal, pg.y, pTotV);
        if (desc > 0) {
            pg.canvas.drawText("Descuento autorizado", totX - 60, pg.y += 16, pTotL);
            pg.canvas.drawText(String.format(Locale.US, "−$%.2f", desc), colTotal, pg.y, pTotV);
        }
        pg.canvas.drawText("Tax (7%)", totX, pg.y += 16, pTotL);
        pg.canvas.drawText(String.format(Locale.US, "$%.2f", tax), colTotal, pg.y, pTotV);
        pg.canvas.drawLine(totX, pg.y += 8, PAGE_W - MARGIN, pg.y, pLine);
        Paint pTotalL = makePaint(COLOR_BLACK, 13f, true);
        Paint pTotalV = makePaint(COLOR_RED,   13f, true);
        pg.canvas.drawText("TOTAL", totX, pg.y += 16, pTotalL);
        alignRight(pTotalV);
        pg.canvas.drawText(String.format(Locale.US, "$%.2f", total), colTotal, pg.y, pTotalV);
        pTotV.setTextAlign(Paint.Align.LEFT);

        // Footer
        pg.y += 30;
        Paint pThanks = makePaint(COLOR_GRAY, 11f, false);
        pThanks.setTextAlign(Paint.Align.CENTER);
        pg.canvas.drawText("Gracias por su preferencia · L&C Technician",
                PAGE_W / 2f, pg.y, pThanks);

        finPage(doc, pg, pFoot, fechaGen);

        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
        return out;
    }

    // ── Reporte Cliente (1 página resumida) ──────────────────────────────────

    public static File generarReporteCliente(Context ctx, Job job,
                                              List<PhotoEntity> fotos,
                                              SignatureEntity signature) throws Exception {
        File dir = new File(ctx.getExternalFilesDir(null), "reportes");
        dir.mkdirs();
        File out = new File(dir, "cliente_" + job.id + ".pdf");

        PdfDocument doc = new PdfDocument();
        String fechaGen = FMT_SHORT.format(new Date());

        Paint pTitle = makePaint(COLOR_BLACK, 20f, true);
        Paint pSub   = makePaint(COLOR_GRAY,  12f, false);
        Paint pSec   = makePaint(COLOR_RED,   10f, true);
        Paint pLabel = makePaint(COLOR_GRAY,  10f, false);
        Paint pValue = makePaint(COLOR_BLACK, 12f, true);
        Paint pBody  = makePaint(COLOR_BLACK, 11f, false);
        Paint pFoot  = makePaint(COLOR_GRAY,   9f, false);
        Paint pLine  = new Paint();
        pLine.setColor(COLOR_BORDER);
        pLine.setStrokeWidth(1f);

        PageCtx pg = startPage(doc, 1);
        pg.y = MARGIN + 10;

        // Header
        pg.canvas.drawText("RESUMEN DE SERVICIO", MARGIN, pg.y += 22, pTitle);
        pg.canvas.drawText("L&C Servicios Técnicos", MARGIN, pg.y += 16, pSub);
        pg.canvas.drawText(fechaGen, PAGE_W - MARGIN, pg.y, alignRight(pSub));
        pg.y += 8;
        pg.canvas.drawLine(MARGIN, pg.y, PAGE_W - MARGIN, pg.y, pLine);

        // Client info
        pg.y += 6;
        drawSection(pg, "CLIENTE", pSec);
        drawField(pg, "Nombre",    job.cliente,   pLabel, pValue);
        drawField(pg, "Dirección", job.direccion, pLabel, pValue);
        if (job.telefono != null && !job.telefono.isEmpty())
            drawField(pg, "Teléfono", job.telefono, pLabel, pValue);

        // Service info
        pg.y += 4;
        drawSection(pg, "SERVICIO", pSec);
        drawField(pg, "Estado",  job.estado, pLabel, pValue);
        drawField(pg, "Equipo",  job.equipo, pLabel, pValue);
        drawField(pg, "Fecha",   job.hora,   pLabel, pValue);
        String tec = extraerTecnicoDeNotas(job.notas);
        drawField(pg, "Técnico", tec,        pLabel, pValue);

        // Description
        if (job.descripcion != null && !job.descripcion.isEmpty()) {
            pg.y += 4;
            drawSection(pg, "TRABAJO REALIZADO", pSec);
            pg.y = drawWrapped(pg.canvas, job.descripcion, MARGIN, pg.y, CONTENT_W, LINE_H, pBody);
        }

        // Notes (stripped of TECNICO: prefix)
        String notas = limpiarNotasTecnico(job.notas);
        if (!"Sin notas.".equals(notas)) {
            pg.y += 4;
            drawSection(pg, "OBSERVACIONES", pSec);
            pg.y = drawWrapped(pg.canvas, notas, MARGIN, pg.y, CONTENT_W, LINE_H, pBody);
        }

        // Signature if present
        if (signature != null) {
            pg.y += 8;
            drawSection(pg, "APROBADO POR", pSec);
            drawField(pg, "Nombre", signature.nombreFirmante, pLabel, pValue);
            drawField(pg, "Fecha",  FMT_TS.format(new Date(signature.timestamp)), pLabel, pValue);
            if (signature.imagePath != null) {
                Bitmap sigBmp = decodeBitmap(signature.imagePath);
                if (sigBmp != null && pg.y < PAGE_H - MARGIN - 100) {
                    int maxW = CONTENT_W / 3;
                    int sigH = (int) ((long) sigBmp.getHeight() * maxW / sigBmp.getWidth());
                    pg.y += 6;
                    android.graphics.RectF r = new android.graphics.RectF(
                            MARGIN, pg.y, MARGIN + maxW, pg.y + sigH);
                    pg.canvas.drawBitmap(sigBmp, null, r, null);
                    pg.y += sigH + 4;
                    sigBmp.recycle();
                }
            }
        }

        // Up to 2 photos ANTES + DESPUÉS
        String[] catClient = {"ANTES", "DESPU\u00c9S"};
        if (fotos != null) {
            for (String cat : catClient) {
                for (PhotoEntity ph : fotos) {
                    if (!cat.equals(ph.categoria)) continue;
                    Bitmap bmp = decodeBitmap(ph.path);
                    if (bmp == null) continue;
                    int bmpH = (int) ((long) bmp.getHeight() * (CONTENT_W / 2) / bmp.getWidth());
                    if (pg.y + bmpH + 50 > PAGE_H - MARGIN - 30) break;
                    pg.y += 8;
                    drawSection(pg, cat, pSec);
                    android.graphics.RectF r = new android.graphics.RectF(
                            MARGIN, pg.y, MARGIN + CONTENT_W / 2, pg.y + bmpH);
                    pg.canvas.drawBitmap(bmp, null, r, null);
                    pg.y += bmpH + 6;
                    bmp.recycle();
                    break; // one per category
                }
            }
        }

        // Footer
        pg.y += 16;
        Paint pThanks = makePaint(COLOR_GRAY, 11f, false);
        pThanks.setTextAlign(Paint.Align.CENTER);
        pg.canvas.drawText("Gracias por confiar en L&C Servicios Técnicos · " + fechaGen,
                PAGE_W / 2f, Math.min(pg.y, PAGE_H - MARGIN - 40), pThanks);

        finPage(doc, pg, pFoot, fechaGen);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
        return out;
    }

    private static String extraerTecnicoDeNotas(String notas) {
        if (notas == null) return "Sin asignar";
        for (String line : notas.split("\n")) {
            if (line.startsWith("TECNICO:")) {
                String nombre = line.substring(8).trim();
                return nombre.isEmpty() ? "Sin asignar" : nombre;
            }
        }
        return "Sin asignar";
    }

    private static String limpiarNotasTecnico(String notas) {
        if (notas == null || notas.isEmpty()) return "Sin notas.";
        StringBuilder sb = new StringBuilder();
        for (String line : notas.split("\n", -1)) {
            if (!line.startsWith("TECNICO:")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "Sin notas." : result;
    }
}
