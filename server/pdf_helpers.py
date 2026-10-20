from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib import colors
from datetime import datetime as dt


def generate_report(title, subtitle, sections, output_buffer):
    doc = SimpleDocTemplate(output_buffer, pagesize=A4,
                            topMargin=15*mm, bottomMargin=15*mm,
                            leftMargin=15*mm, rightMargin=15*mm)
    styles = getSampleStyleSheet()
    elements = []

    # Title
    elements.append(Paragraph(f"<b>{title}</b>", styles['Title']))
    elements.append(Paragraph(subtitle, styles['Normal']))
    elements.append(Spacer(1, 8*mm))

    for sec in sections:
        elements.append(Paragraph(f"<b>{sec['heading']}</b>", styles['Heading2']))
        elements.append(Spacer(1, 3*mm))

        if sec.get("type") == "text":
            elements.append(Paragraph(str(sec.get("content", "")), styles['Normal']))
        elif sec.get("type") == "table":
            headers = sec.get("headers", [])
            rows = sec.get("rows", [])
            if headers and rows:
                data = [headers] + [[str(c)[:80] for c in row] for row in rows]
                col_count = len(headers)
                col_width = (A4[0] - 30*mm) / col_count
                t = Table(data, colWidths=[col_width]*col_count, repeatRows=1)
                t.setStyle(TableStyle([
                    ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#1B71C9')),
                    ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                    ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
                    ('FONTNAME', (0, 1), (-1, -1), 'Helvetica'),
                    ('FONTSIZE', (0, 0), (-1, -1), 7),
                    ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
                    ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
                    ('BOTTOMPADDING', (0, 0), (-1, 0), 6),
                    ('TOPPADDING', (0, 0), (-1, -1), 4),
                    ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
                    ('ROWBACKGROUNDS', (0, 1), (-1, -1),
                     [colors.HexColor('#f9fafb'), colors.white]),
                ]))
                elements.append(t)
            elif headers and not rows:
                elements.append(Paragraph("(Sin datos en este período)", styles['Normal']))
        elif sec.get("type") == "kpi":
            kpis = sec.get("kpis", [])
            if kpis:
                kpi_data = [[k["label"] for k in kpis], [str(k["value"]) for k in kpis]]
                kt = Table(kpi_data, colWidths=[(A4[0]-30*mm)/len(kpis)]*len(kpis))
                kt.setStyle(TableStyle([
                    ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#374151')),
                    ('TEXTCOLOR', (0, 0), (-1, 0), colors.HexColor('#9ca3af')),
                    ('BACKGROUND', (0, 1), (-1, 1), colors.HexColor('#1f2937')),
                    ('TEXTCOLOR', (0, 1), (-1, 1), colors.white),
                    ('FONTNAME', (0, 0), (-1, -1), 'Helvetica-Bold'),
                    ('FONTSIZE', (0, 0), (-1, 0), 8),
                    ('FONTSIZE', (0, 1), (-1, 1), 16),
                    ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
                    ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
                    ('TOPPADDING', (0, 0), (-1, -1), 8),
                    ('BOTTOMPADDING', (0, 0), (-1, -1), 8),
                    ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor('#4b5563')),
                ]))
                elements.append(kt)

        elements.append(Spacer(1, 6*mm))

    elements.append(Spacer(1, 10*mm))
    elements.append(Paragraph(
        f"<i>Generado: {dt.now().strftime('%Y-%m-%d %H:%M:%S')} · LC Technician</i>",
        styles['Normal']))

    doc.build(elements)
    return output_buffer


def generate_job_report_pdf(job_data, work_items, signatures, output_buffer):
    """Generate PDF with job details, work items table, and embedded signatures."""
    from reportlab.lib.pagesizes import letter
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image as RLImage
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib import colors
    from reportlab.lib.units import inch
    import io, base64

    doc = SimpleDocTemplate(output_buffer, pagesize=letter,
                            topMargin=0.75*inch, bottomMargin=0.75*inch)
    styles = getSampleStyleSheet()
    story = []

    # Header
    story.append(Paragraph("LC Technician - Reporte de Trabajo", styles['Title']))
    story.append(Paragraph(
        "Job #{} · {}".format(job_data.get('id', 'N/A'), job_data.get('clienteNombre') or job_data.get('cliente', 'N/A')),
        styles['Heading2']))
    story.append(Spacer(1, 0.2*inch))

    # Job info table
    job_info = [
        ["Estado", str(job_data.get("estado") or "")],
        ["Fecha", str(job_data.get("scheduledTime") or job_data.get("hora", ""))],
        ["Tecnico", str(job_data.get("technicianId", ""))],
        ["Direccion", str(job_data.get("address") or job_data.get("direccion", ""))],
    ]
    t = Table(job_info, colWidths=[1.5*inch, 5*inch])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (0, -1), colors.HexColor('#1e3a5f')),
        ('TEXTCOLOR', (0, 0), (0, -1), colors.white),
        ('FONTNAME', (0, 0), (-1, -1), 'Helvetica'),
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
        ('PADDING', (0, 0), (-1, -1), 6),
    ]))
    story.append(t)
    story.append(Spacer(1, 0.3*inch))

    # Work items
    if work_items:
        story.append(Paragraph("Work Items", styles['Heading2']))
        wi_data = [["Descripcion", "Tipo", "Cantidad", "Precio", "Subtotal"]]
        total = 0
        for wi in work_items:
            wi_data.append([
                str(wi.get("descripcion", ""))[:60],
                str(wi.get("tipo", "")),
                str(wi.get("cantidad", "")),
                "${:.2f}".format(float(wi.get('precioUnitario', 0) or 0)),
                "${:.2f}".format(float(wi.get('subtotal', 0) or 0)),
            ])
            total += float(wi.get("subtotal", 0) or 0)
        wi_data.append(["", "", "", "TOTAL:", "${:.2f}".format(total)])
        wt = Table(wi_data, colWidths=[2.5*inch, 1*inch, 0.8*inch, 1*inch, 1*inch])
        wt.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#1e3a5f')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
            ('FONTNAME', (0, 1), (-1, -1), 'Helvetica'),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
            ('PADDING', (0, 0), (-1, -1), 4),
            ('FONTNAME', (0, -1), (-1, -1), 'Helvetica-Bold'),
        ]))
        story.append(wt)
        story.append(Spacer(1, 0.3*inch))

    # Signatures
    for sig in signatures:
        sig_type = sig.get("type", "")
        label = "Firma del Cliente" if "CUSTOMER" in sig_type else "Firma del Tecnico"
        story.append(Paragraph(label, styles['Heading2']))
        b64 = sig.get("base64Data", "")
        if b64:
            try:
                img_data = base64.b64decode(b64)
                img_buf = io.BytesIO(img_data)
                img = RLImage(img_buf, width=3*inch, height=1.5*inch)
                story.append(img)
            except Exception:
                story.append(Paragraph("[Firma no disponible]", styles['Normal']))
        story.append(Spacer(1, 0.2*inch))

    # Footer
    from datetime import datetime as _dt2
    story.append(Spacer(1, 0.3*inch))
    story.append(Paragraph(
        "Generado: {} - LC Technician v1.2".format(_dt2.now().strftime('%Y-%m-%d %H:%M:%S')),
        styles['Normal']))

    doc.build(story)
