package com.storeql.einvoice;

import com.storeql.einvoice.Invoice.Address;
import com.storeql.einvoice.Invoice.AllowanceCharge;
import com.storeql.einvoice.Invoice.CreditTransfer;
import com.storeql.einvoice.Invoice.Line;
import com.storeql.einvoice.Invoice.Note;
import com.storeql.einvoice.Invoice.Party;
import com.storeql.einvoice.Invoice.PaymentInstructions;
import com.storeql.einvoice.Invoice.PrecedingInvoice;
import com.storeql.einvoice.Invoice.Totals;
import com.storeql.einvoice.Invoice.VatBreakdown;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;

/**
 * Factur-X 1.0 (ZUGFeRD 2 in Germany): a PDF/A-3 a person reads, carrying the EN 16931 Cross
 * Industry Invoice a machine reads. Both halves are the invoice; where they differ the XML governs,
 * which is why the PDF is drawn from the same model the XML is written from.
 *
 * <p>Reading one is reading a hostile file: the PDF is capped in size, held in memory only, refused
 * when encrypted, and its embedded XML is read no further than {@link SafeXml#MAX_BYTES}, so a
 * compressed bomb stops at the cap instead of filling the heap.
 */
public final class FacturX {

  /** The attachment name Factur-X requires. */
  public static final String XML_FILENAME = "factur-x.xml";

  /** 20 MB: a scanned delivery note can ride along; a PDF larger than this is not an invoice. */
  public static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

  /** The names a hybrid invoice's XML is attached under: Factur-X, ZUGFeRD 2 and XRechnung. */
  static final Set<String> RECOGNISED =
      Set.of("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml");

  private static final String FACTUR_X_NS = "urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#";
  private static final String PRODUCER = "StoreQL";
  private static final String SRGB = "sRGB IEC61966-2.1";
  private static final int MAX_NAME_TREE_DEPTH = 16;

  /** The XML found inside a PDF, and the name it was attached under. */
  static final class Attachment {
    private final String filename;
    private final byte[] xml;

    Attachment(String filename, byte[] xml) {
      this.filename = filename;
      this.xml = xml.clone();
    }

    String filename() {
      return filename;
    }

    byte[] xml() {
      return xml.clone();
    }
  }

  private FacturX() {}

  /** The embedded font's bytes, read from the jar once (742 KB) rather than for every PDF. */
  private static final class FontHolder {
    static final byte[] BYTES = load();

    private static byte[] load() {
      try (InputStream in = FacturX.class.getResourceAsStream("fonts/DejaVuSans.ttf")) {
        if (in == null) {
          throw new IllegalStateException("fonts/DejaVuSans.ttf is not on the classpath");
        }
        return in.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException("the embedded font could not be read", e);
      }
    }
  }

  /** The cached font bytes; the same array every call, never to be modified. */
  @SuppressWarnings("PMD.MethodReturnsInternalArray") // read-only by contract, copying defeats it
  static byte[] fontBytes() {
    return FontHolder.BYTES;
  }

  /** A Factur-X PDF/A-3b of the invoice, at the EN 16931 conformance level. */
  public static byte[] create(Invoice invoice) {
    return create(
        invoice, CiiWriter.write(invoice).getBytes(StandardCharsets.UTF_8), Instant.now());
  }

  @SuppressWarnings("PMD.ReplaceJavaUtilCalendar") // PDFBox takes and writes PDF dates as Calendar
  static byte[] create(Invoice invoice, byte[] ciiXml, Instant at) {
    Calendar when =
        GregorianCalendar.from(at.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneOffset.UTC));
    String title = (invoice.isCreditNote() ? "Credit note " : "Invoice ") + nz(invoice.number());
    String author =
        invoice.seller() == null || invoice.seller().name() == null
            ? PRODUCER
            : invoice.seller().name();
    try (PDDocument doc = new PDDocument()) {
      doc.setVersion(1.7f);
      PDType0Font font = PDType0Font.load(doc, new ByteArrayInputStream(fontBytes()), true);
      new Layout(doc, font).render(invoice);
      PDDocumentInformation info = doc.getDocumentInformation();
      info.setTitle(title);
      info.setAuthor(author);
      info.setCreator(PRODUCER);
      info.setProducer(PRODUCER);
      info.setCreationDate(when);
      info.setModificationDate(when);
      PDDocumentCatalog catalog = doc.getDocumentCatalog();
      PDMetadata metadata = new PDMetadata(doc);
      metadata.importXMPMetadata(xmp(title, author, when).getBytes(StandardCharsets.UTF_8));
      catalog.setMetadata(metadata);
      PDOutputIntent intent =
          new PDOutputIntent(
              doc, new ByteArrayInputStream(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData()));
      intent.setInfo(SRGB);
      intent.setOutputCondition(SRGB);
      intent.setOutputConditionIdentifier(SRGB);
      intent.setRegistryName("http://www.color.org");
      catalog.addOutputIntent(intent);
      attach(doc, catalog, ciiXml, when);
      ByteArrayOutputStream out = new ByteArrayOutputStream(64_536);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("the Factur-X PDF could not be written", e);
    }
  }

  @SuppressWarnings("PMD.ReplaceJavaUtilCalendar") // PDFBox takes and writes PDF dates as Calendar
  private static void attach(PDDocument doc, PDDocumentCatalog catalog, byte[] xml, Calendar when)
      throws IOException {
    PDEmbeddedFile file =
        new PDEmbeddedFile(doc, new ByteArrayInputStream(xml), COSName.FLATE_DECODE);
    file.setSubtype("text/xml");
    file.setSize(xml.length);
    file.setCreationDate(when);
    file.setModDate(when);
    PDComplexFileSpecification spec = new PDComplexFileSpecification();
    spec.setFile(XML_FILENAME);
    spec.setFileUnicode(XML_FILENAME);
    spec.setFileDescription("Factur-X invoice: EN 16931 Cross Industry Invoice");
    spec.setEmbeddedFile(file);
    spec.setEmbeddedFileUnicode(file);
    // "Alternative": the XML is the invoice itself in another form, as ZUGFeRD requires and
    // Factur-X allows.
    spec.getCOSObject().setName(COSName.getPDFName("AFRelationship"), "Alternative");
    PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
    tree.setNames(Map.of(XML_FILENAME, spec));
    PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
    names.setEmbeddedFiles(tree);
    catalog.setNames(names);
    COSArray associated = new COSArray();
    associated.add(spec);
    catalog.getCOSObject().setItem(COSName.getPDFName("AF"), associated);
  }

  /**
   * PDF/A-3b identification, the Factur-X properties and the extension schema that declares them.
   */
  @SuppressWarnings("PMD.ReplaceJavaUtilCalendar") // PDFBox takes and writes PDF dates as Calendar
  private static String xmp(String title, String author, Calendar when) {
    String date =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(((GregorianCalendar) when).toZonedDateTime());
    return """
        <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
           <pdfaid:part>3</pdfaid:part>
           <pdfaid:conformance>B</pdfaid:conformance>
          </rdf:Description>
          <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
           <dc:title><rdf:Alt><rdf:li xml:lang="x-default">%1$s</rdf:li></rdf:Alt></dc:title>
           <dc:creator><rdf:Seq><rdf:li>%2$s</rdf:li></rdf:Seq></dc:creator>
          </rdf:Description>
          <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
           <pdf:Producer>%3$s</pdf:Producer>
          </rdf:Description>
          <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
           <xmp:CreatorTool>%3$s</xmp:CreatorTool>
           <xmp:CreateDate>%4$s</xmp:CreateDate>
           <xmp:ModifyDate>%4$s</xmp:ModifyDate>
          </rdf:Description>
          <rdf:Description rdf:about="" xmlns:fx="%5$s">
           <fx:DocumentType>INVOICE</fx:DocumentType>
           <fx:DocumentFileName>%6$s</fx:DocumentFileName>
           <fx:Version>1.0</fx:Version>
           <fx:ConformanceLevel>EN 16931</fx:ConformanceLevel>
          </rdf:Description>
          <rdf:Description rdf:about="" xmlns:pdfaExtension="http://www.aiim.org/pdfa/ns/extension/" xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#" xmlns:pdfaProperty="http://www.aiim.org/pdfa/ns/property#">
           <pdfaExtension:schemas>
            <rdf:Bag>
             <rdf:li rdf:parseType="Resource">
              <pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>
              <pdfaSchema:namespaceURI>%5$s</pdfaSchema:namespaceURI>
              <pdfaSchema:prefix>fx</pdfaSchema:prefix>
              <pdfaSchema:property>
               <rdf:Seq>
                <rdf:li rdf:parseType="Resource"><pdfaProperty:name>DocumentFileName</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>The name of the embedded XML document</pdfaProperty:description></rdf:li>
                <rdf:li rdf:parseType="Resource"><pdfaProperty:name>DocumentType</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>The type of the hybrid document in capital letters, e.g. INVOICE or ORDER</pdfaProperty:description></rdf:li>
                <rdf:li rdf:parseType="Resource"><pdfaProperty:name>Version</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>The actual version of the standard applying to the embedded XML document</pdfaProperty:description></rdf:li>
                <rdf:li rdf:parseType="Resource"><pdfaProperty:name>ConformanceLevel</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>The conformance level of the embedded XML document</pdfaProperty:description></rdf:li>
               </rdf:Seq>
              </pdfaSchema:property>
             </rdf:li>
            </rdf:Bag>
           </pdfaExtension:schemas>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
        """
        .formatted(
            XmlNode.escape(title, false),
            XmlNode.escape(author, false),
            PRODUCER,
            date,
            FACTUR_X_NS,
            XML_FILENAME);
  }

  /** Whether the bytes start as a PDF does, within the first kilobyte where readers look. */
  static boolean isPdf(byte[] bytes) {
    int limit = Math.min(bytes.length, 1024);
    for (int i = 0; i + 4 < limit; i++) {
      if (bytes[i] == '%'
          && bytes[i + 1] == 'P'
          && bytes[i + 2] == 'D'
          && bytes[i + 3] == 'F'
          && bytes[i + 4] == '-') {
        return true;
      }
    }
    return false;
  }

  /**
   * The invoice XML attached to a hybrid PDF, found through the document's associated files and its
   * embedded files name tree.
   *
   * @throws EInvoiceFormatException {@code TOO_LARGE}, {@code NOT_A_PDF}, {@code PDF_ENCRYPTED},
   *     {@code PDF_UNREADABLE} or {@code NO_EMBEDDED_INVOICE}
   */
  static Attachment extract(byte[] pdf) {
    if (pdf.length > MAX_PDF_BYTES) {
      throw new EInvoiceFormatException(
          "TOO_LARGE", "the PDF is larger than " + MAX_PDF_BYTES / (1024 * 1024) + " MB");
    }
    if (!isPdf(pdf)) throw new EInvoiceFormatException("NOT_A_PDF", "the document is not a PDF");
    try (PDDocument doc =
        Loader.loadPDF(
            pdf,
            "",
            null,
            null,
            MemoryUsageSetting.setupMainMemoryOnly(2L * MAX_PDF_BYTES).streamCache)) {
      if (doc.isEncrypted()) {
        throw new EInvoiceFormatException(
            "PDF_ENCRYPTED", "an encrypted PDF cannot be a Factur-X invoice");
      }
      PDDocumentCatalog catalog = doc.getDocumentCatalog();
      List<PDComplexFileSpecification> specs = new ArrayList<>();
      COSArray associated = catalog.getCOSObject().getCOSArray(COSName.getPDFName("AF"));
      if (associated != null) {
        for (int i = 0; i < associated.size(); i++) {
          if (associated.getObject(i) instanceof COSDictionary d)
            specs.add(new PDComplexFileSpecification(d));
        }
      }
      PDDocumentNameDictionary names = catalog.getNames();
      if (names != null && names.getEmbeddedFiles() != null)
        collect(names.getEmbeddedFiles(), specs, 0);
      for (PDComplexFileSpecification spec : specs) {
        String name = spec.getFileUnicode() != null ? spec.getFileUnicode() : spec.getFile();
        if (name == null || !RECOGNISED.contains(name.strip().toLowerCase(Locale.ROOT))) continue;
        PDEmbeddedFile file =
            spec.getEmbeddedFileUnicode() != null
                ? spec.getEmbeddedFileUnicode()
                : spec.getEmbeddedFile();
        if (file != null) return new Attachment(name.strip(), readCapped(file));
      }
      throw new EInvoiceFormatException(
          "NO_EMBEDDED_INVOICE",
          "the PDF carries no factur-x.xml, zugferd-invoice.xml or xrechnung.xml");
    } catch (InvalidPasswordException e) {
      throw new EInvoiceFormatException(
          "PDF_ENCRYPTED", "an encrypted PDF cannot be a Factur-X invoice", e);
    } catch (IOException e) {
      throw new EInvoiceFormatException("PDF_UNREADABLE", "the PDF could not be read", e);
    }
  }

  private static void collect(
      PDNameTreeNode<PDComplexFileSpecification> node,
      List<PDComplexFileSpecification> specs,
      int depth)
      throws IOException {
    if (depth > MAX_NAME_TREE_DEPTH) return;
    Map<String, PDComplexFileSpecification> entries = node.getNames();
    if (entries != null) specs.addAll(entries.values());
    List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
    if (kids == null) return;
    for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) collect(kid, specs, depth + 1);
  }

  private static byte[] readCapped(PDEmbeddedFile file) throws IOException {
    try (InputStream in = file.createInputStream()) {
      byte[] bytes = in.readNBytes(SafeXml.MAX_BYTES + 1);
      if (bytes.length > SafeXml.MAX_BYTES) {
        throw new EInvoiceFormatException(
            "TOO_LARGE",
            "the invoice embedded in the PDF is larger than "
                + SafeXml.MAX_BYTES / (1024 * 1024)
                + " MB");
      }
      return bytes;
    }
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  /** Draws the human-readable half from the model, page after page. */
  private static final class Layout {
    private static final float MARGIN = 48f;
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float BODY = 9f;
    private static final float SMALL = 7f;
    private static final float TITLE = 16f;
    private static final float LEADING = 12f;

    private final PDDocument doc;
    private final PDType0Font font;
    private final Map<Integer, Boolean> glyphs = new HashMap<>();
    private PDPageContentStream content;
    private float y;
    private int page;

    Layout(PDDocument doc, PDType0Font font) {
      this.doc = doc;
      this.font = font;
    }

    void render(Invoice inv) throws IOException {
      newPage();
      try {
        header(inv);
        parties(inv);
        references(inv);
        lines(inv);
        vat(inv);
        totals(inv.totals());
        payment(inv);
        for (Note n : inv.notes())
          paragraph(n.subjectCode() == null ? nz(n.text()) : n.subjectCode() + ": " + nz(n.text()));
      } finally {
        finishPage();
      }
    }

    private void newPage() throws IOException {
      finishPage();
      PDPage next = new PDPage(PDRectangle.A4);
      doc.addPage(next);
      content = new PDPageContentStream(doc, next);
      page++;
      y = HEIGHT - MARGIN;
    }

    private void finishPage() throws IOException {
      if (content == null) return;
      text(
          MARGIN,
          MARGIN / 2,
          SMALL,
          "The invoice is also carried in this PDF as EN 16931 XML ("
              + XML_FILENAME
              + "). Page "
              + page
              + ".");
      content.close();
      content = null;
    }

    private void need(float height) throws IOException {
      if (y - height < MARGIN + LEADING) newPage();
    }

    private void header(Invoice inv) throws IOException {
      text(
          MARGIN,
          y - TITLE,
          TITLE,
          (inv.isCreditNote() ? "Credit note " : "Invoice ") + nz(inv.number()));
      right(
          WIDTH - MARGIN,
          y - LEADING,
          BODY,
          "Issued " + (inv.issueDate() == null ? "" : inv.issueDate()));
      if (inv.dueDate() != null)
        right(WIDTH - MARGIN, y - 2 * LEADING, BODY, "Due " + inv.dueDate());
      right(WIDTH - MARGIN, y - 3 * LEADING, BODY, "Currency " + nz(inv.currency()));
      y -= 3 * LEADING + 16;
    }

    private void parties(Invoice inv) throws IOException {
      List<String> seller = partyLines("Seller", inv.seller());
      List<String> buyer = partyLines("Buyer", inv.buyer());
      int rows = Math.max(seller.size(), buyer.size());
      need(rows * LEADING);
      float half = (WIDTH - 2 * MARGIN) / 2;
      for (int i = 0; i < rows; i++) {
        float baseline = y - (i + 1) * LEADING;
        if (i < seller.size()) text(MARGIN, baseline, BODY, fit(seller.get(i), half - 12));
        if (i < buyer.size()) text(MARGIN + half, baseline, BODY, fit(buyer.get(i), half - 12));
      }
      y -= rows * LEADING + 12;
    }

    private static List<String> partyLines(String role, Party p) {
      List<String> out = new ArrayList<>();
      out.add(role.toUpperCase(Locale.ROOT));
      if (p == null) return out;
      add(out, p.name());
      if (p.tradingName() != null && !p.tradingName().equals(p.name())) add(out, p.tradingName());
      Address a = p.address();
      if (a != null) {
        add(out, a.line1());
        add(out, a.line2());
        add(out, a.line3());
        add(out, join(" ", a.postcode(), a.city()));
        add(out, join(", ", a.subdivision(), a.country()));
      }
      if (p.vatId() != null) add(out, "VAT " + p.vatId());
      if (p.taxRegistrationId() != null) add(out, "Tax registration " + p.taxRegistrationId());
      if (p.legalRegistration() != null) add(out, "Registration " + p.legalRegistration().id());
      return out;
    }

    private void references(Invoice inv) throws IOException {
      List<String> refs = new ArrayList<>();
      if (inv.buyerReference() != null) refs.add("Buyer reference " + inv.buyerReference());
      if (inv.orderReference() != null) refs.add("Purchase order " + inv.orderReference());
      if (inv.contractReference() != null) refs.add("Contract " + inv.contractReference());
      if (inv.projectReference() != null) refs.add("Project " + inv.projectReference());
      for (PrecedingInvoice p : inv.precedingInvoices()) {
        refs.add(
            "Refers to invoice "
                + nz(p.number())
                + (p.issueDate() == null ? "" : " of " + p.issueDate()));
      }
      if (inv.delivery() != null && inv.delivery().actualDate() != null)
        refs.add("Delivered " + inv.delivery().actualDate());
      if (inv.invoicingPeriod() != null) {
        refs.add(
            "Period "
                + (inv.invoicingPeriod().start() == null ? "" : inv.invoicingPeriod().start())
                + " to "
                + (inv.invoicingPeriod().end() == null ? "" : inv.invoicingPeriod().end()));
      }
      for (String r : refs) {
        need(LEADING);
        y -= LEADING;
        text(MARGIN, y, BODY, fit(r, WIDTH - 2 * MARGIN));
      }
      y -= 10;
    }

    private void lines(Invoice inv) throws IOException {
      need(2 * LEADING);
      y -= LEADING;
      text(MARGIN, y, BODY, "#");
      text(MARGIN + 28, y, BODY, "Item");
      right(MARGIN + 330, y, BODY, "Quantity");
      right(MARGIN + 400, y, BODY, "Unit price");
      right(MARGIN + 450, y, BODY, "VAT");
      right(WIDTH - MARGIN, y, BODY, "Net");
      rule(y - 4);
      y -= 4;
      for (Line l : inv.lines()) {
        need(LEADING);
        y -= LEADING;
        text(MARGIN, y, BODY, fit(nz(l.id()), 24));
        text(MARGIN + 28, y, BODY, fit(l.item() == null ? "" : nz(l.item().name()), 220));
        right(MARGIN + 330, y, BODY, amount(l.quantity()) + " " + nz(l.unitCode()));
        right(MARGIN + 400, y, BODY, l.price() == null ? "" : amount(l.price().net()));
        right(
            MARGIN + 450,
            y,
            BODY,
            nz(l.vatCategory()) + (l.vatRate() == null ? "" : " " + amount(l.vatRate()) + "%"));
        right(WIDTH - MARGIN, y, BODY, amount(l.netAmount()));
      }
      for (AllowanceCharge ac : inv.allowanceCharges()) {
        need(LEADING);
        y -= LEADING;
        String reason = ac.reason() != null ? ac.reason() : nz(ac.reasonCode());
        text(MARGIN + 28, y, BODY, fit((ac.charge() ? "Charge: " : "Allowance: ") + reason, 220));
        right(
            MARGIN + 450,
            y,
            BODY,
            nz(ac.vatCategory()) + (ac.vatRate() == null ? "" : " " + amount(ac.vatRate()) + "%"));
        right(WIDTH - MARGIN, y, BODY, (ac.charge() ? "" : "-") + amount(ac.amount()));
      }
      y -= 12;
    }

    private void vat(Invoice inv) throws IOException {
      for (VatBreakdown b : inv.vatBreakdown()) {
        need(LEADING);
        y -= LEADING;
        String rate = b.rate() == null ? "" : " at " + amount(b.rate()) + "%";
        text(
            MARGIN + 250,
            y,
            BODY,
            fit("VAT " + nz(b.category()) + rate + " on " + amount(b.taxableAmount()), 200));
        right(WIDTH - MARGIN, y, BODY, amount(b.taxAmount()));
        String reason = b.exemptionReason() != null ? b.exemptionReason() : b.exemptionReasonCode();
        if (reason != null) {
          need(LEADING);
          y -= LEADING;
          text(MARGIN + 250, y, SMALL, fit(reason, WIDTH - MARGIN - 250 - MARGIN));
        }
      }
      y -= 6;
    }

    private void totals(Totals t) throws IOException {
      if (t == null) return;
      total("Lines", t.lineNet());
      total("Allowances", t.allowances());
      total("Charges", t.charges());
      total("Total without VAT", t.withoutVat());
      total("VAT", t.vat());
      total("Total with VAT", t.withVat());
      total("Paid", t.paid());
      total("Rounding", t.rounding());
      total("Amount due", t.payable());
      y -= 10;
    }

    private void total(String label, BigDecimal value) throws IOException {
      if (value == null) return;
      need(LEADING);
      y -= LEADING;
      text(MARGIN + 330, y, BODY, label);
      right(WIDTH - MARGIN, y, BODY, amount(value));
    }

    private void payment(Invoice inv) throws IOException {
      PaymentInstructions p = inv.payment();
      if (p != null) {
        if (p.meansCode() != null)
          paragraph(
              "Payment means "
                  + p.meansCode()
                  + (p.meansText() == null ? "" : " (" + p.meansText() + ")"));
        for (CreditTransfer t : p.creditTransfers()) {
          paragraph(
              "Pay to "
                  + nz(t.account())
                  + (t.accountName() == null ? "" : ", " + t.accountName())
                  + (t.serviceProvider() == null ? "" : ", " + t.serviceProvider()));
        }
        if (p.remittanceInformation() != null)
          paragraph("Payment reference " + p.remittanceInformation());
        if (p.directDebit() != null && p.directDebit().mandateReference() != null)
          paragraph("Direct debit mandate " + p.directDebit().mandateReference());
      }
      if (inv.paymentTerms() != null) paragraph(inv.paymentTerms());
    }

    private void paragraph(String s) throws IOException {
      for (String line : wrap(s, WIDTH - 2 * MARGIN)) {
        need(LEADING);
        y -= LEADING;
        text(MARGIN, y, BODY, line);
      }
    }

    private List<String> wrap(String s, float max) throws IOException {
      List<String> out = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      for (String word : printable(s).split(" ")) {
        String candidate = current.isEmpty() ? word : current + " " + word;
        if (width(candidate, BODY) <= max || current.isEmpty()) {
          current.setLength(0);
          current.append(fit(candidate, max));
        } else {
          out.add(current.toString());
          current.setLength(0);
          current.append(fit(word, max));
        }
      }
      if (!current.isEmpty()) out.add(current.toString());
      return out;
    }

    private void rule(float at) throws IOException {
      content.setStrokingColor(0.6f, 0.6f, 0.6f);
      content.setLineWidth(0.5f);
      content.moveTo(MARGIN, at);
      content.lineTo(WIDTH - MARGIN, at);
      content.stroke();
    }

    private void text(float x, float baseline, float size, String s) throws IOException {
      String shown = printable(s);
      if (shown.isBlank()) return;
      content.beginText();
      content.setFont(font, size);
      content.setNonStrokingColor(0.1f, 0.1f, 0.1f);
      content.newLineAtOffset(x, baseline);
      content.showText(shown);
      content.endText();
    }

    private void right(float xRight, float baseline, float size, String s) throws IOException {
      text(xRight - width(s, size), baseline, size, s);
    }

    private float width(String s, float size) throws IOException {
      return font.getStringWidth(printable(s)) / 1000f * size;
    }

    private String fit(String s, float max) throws IOException {
      String shown = printable(s);
      if (width(shown, BODY) <= max) return shown;
      while (shown.length() > 1 && width(shown + "…", BODY) > max) {
        shown = shown.substring(0, shown.length() - 1);
      }
      return shown + "…";
    }

    /**
     * Replaces control characters, and anything the font has no glyph for, so a name cannot break a
     * page.
     */
    private String printable(String s) {
      if (s == null) return "";
      StringBuilder b = new StringBuilder(s.length());
      s.codePoints()
          .forEach(
              cp -> b.appendCodePoint(Character.isISOControl(cp) ? ' ' : hasGlyph(cp) ? cp : '?'));
      return b.toString();
    }

    private boolean hasGlyph(int codePoint) {
      return glyphs.computeIfAbsent(
          codePoint,
          cp -> {
            try {
              font.encode(new String(Character.toChars(cp)));
              return true;
            } catch (IOException | IllegalArgumentException e) {
              return false;
            }
          });
    }

    private static String amount(BigDecimal v) {
      return v == null ? "" : v.toPlainString();
    }

    private static void add(List<String> out, String s) {
      if (s != null && !s.isBlank()) out.add(s.strip());
    }

    private static String join(String separator, String a, String b) {
      if (a == null || a.isBlank()) return b;
      if (b == null || b.isBlank()) return a;
      return a.strip() + separator + b.strip();
    }
  }
}
