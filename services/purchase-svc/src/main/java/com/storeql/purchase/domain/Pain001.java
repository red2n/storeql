package com.storeql.purchase.domain;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * A SEPA credit transfer initiation, ISO 20022 pain.001.001.09 (17.12): the file a euro payment run
 * hands the bank. The SEPA Regulation (EU) 260/2012 art.5(1)(d) requires this format for bundled
 * euro credit transfers a business sends its bank.
 *
 * <p>Written to the EPC's SCT customer-to-PSP implementation guidelines (EPC132-08, 2025): one
 * payment information block with service level SEPA and charges shared (SLEV), batch booking, and
 * one transfer per supplier. SEPA only obliges banks to carry the Latin character set, so names and
 * remittance text are reduced to it — accents dropped, an ampersand written as a plus, anything
 * else a space — and identifiers that would not survive that are refused rather than altered,
 * because an identifier is what the bank's status report is matched back on.
 */
public final class Pain001 {

  public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";

  static final int MAX_ID = 35;
  static final int MAX_NAME = 70;
  static final int MAX_REMITTANCE = 140;
  static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

  private static final Pattern NOT_LATIN = Pattern.compile("[^a-zA-Z0-9/\\-?:().,'+ ]");

  private Pain001() {}

  /** An account: its holder's name, IBAN and, where known, BIC. */
  public record Account(String name, String iban, String bic) {}

  /**
   * One credit transfer.
   *
   * @param endToEndId what the bank's status report names the payment by
   * @param remittance what the supplier sees, e.g. the run reference
   */
  public record Transfer(
      String endToEndId, BigDecimal amount, Account creditor, String remittance) {}

  /**
   * The initiation.
   *
   * @param messageId unique per file the business sends its bank
   * @param executionDate the day the business asks to be debited
   */
  public record Initiation(
      String messageId,
      Instant createdAt,
      LocalDate executionDate,
      Account debtor,
      List<Transfer> transfers) {}

  /**
   * Writes the file.
   *
   * @return the XML, UTF-8 declared
   * @throws IllegalArgumentException naming what SEPA would refuse: an identifier outside the Latin
   *     set or reused, an amount of nothing, more than two decimals or above the scheme maximum, an
   *     IBAN whose check digits fail, a name with nothing SEPA can carry
   */
  public static String write(Initiation in) {
    List<Transfer> transfers = in.transfers() == null ? List.of() : in.transfers();
    if (transfers.isEmpty()) {
      throw new IllegalArgumentException(
          "a credit transfer initiation needs at least one transfer");
    }
    if (in.executionDate() == null || in.createdAt() == null) {
      throw new IllegalArgumentException("a credit transfer initiation needs its dates");
    }
    String messageId = identifier(in.messageId(), "the message id");
    Account debtor = account(in.debtor(), "the paying account");
    Set<String> seen = new HashSet<>();
    List<Transfer> clean = new ArrayList<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (Transfer t : transfers) {
      String who = t.creditor() == null ? "a payee" : "payee " + t.creditor().name();
      String e2e = identifier(t.endToEndId(), "the end-to-end id of " + who);
      if (!seen.add(e2e)) {
        throw new IllegalArgumentException("end-to-end id " + e2e + " is used twice");
      }
      BigDecimal amount = amount(t.amount(), who);
      clean.add(
          new Transfer(
              e2e, amount, account(t.creditor(), who), latin(t.remittance(), MAX_REMITTANCE)));
      sum = sum.add(amount);
    }
    try {
      StringWriter out = new StringWriter();
      XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("Document");
      w.writeDefaultNamespace(NAMESPACE);
      w.writeStartElement("CstmrCdtTrfInitn");

      w.writeStartElement("GrpHdr");
      leaf(w, "MsgId", messageId);
      leaf(w, "CreDtTm", in.createdAt().truncatedTo(ChronoUnit.SECONDS).toString());
      leaf(w, "NbOfTxs", Integer.toString(clean.size()));
      leaf(w, "CtrlSum", sum.toPlainString());
      w.writeStartElement("InitgPty");
      leaf(w, "Nm", debtor.name());
      w.writeEndElement();
      w.writeEndElement();

      w.writeStartElement("PmtInf");
      leaf(w, "PmtInfId", messageId);
      leaf(w, "PmtMtd", "TRF");
      leaf(w, "BtchBookg", "true");
      leaf(w, "NbOfTxs", Integer.toString(clean.size()));
      leaf(w, "CtrlSum", sum.toPlainString());
      w.writeStartElement("PmtTpInf");
      w.writeStartElement("SvcLvl");
      leaf(w, "Cd", "SEPA");
      w.writeEndElement();
      w.writeEndElement();
      w.writeStartElement("ReqdExctnDt");
      leaf(w, "Dt", in.executionDate().toString());
      w.writeEndElement();
      party(w, "Dbtr", debtor.name());
      iban(w, "DbtrAcct", debtor.iban());
      agent(w, "DbtrAgt", debtor.bic(), true);
      leaf(w, "ChrgBr", "SLEV");
      for (Transfer t : clean) {
        w.writeStartElement("CdtTrfTxInf");
        w.writeStartElement("PmtId");
        leaf(w, "EndToEndId", t.endToEndId());
        w.writeEndElement();
        w.writeStartElement("Amt");
        w.writeStartElement("InstdAmt");
        w.writeAttribute("Ccy", "EUR");
        w.writeCharacters(t.amount().toPlainString());
        w.writeEndElement();
        w.writeEndElement();
        agent(w, "CdtrAgt", t.creditor().bic(), false);
        party(w, "Cdtr", t.creditor().name());
        iban(w, "CdtrAcct", t.creditor().iban());
        if (!t.remittance().isEmpty()) {
          w.writeStartElement("RmtInf");
          leaf(w, "Ustrd", t.remittance());
          w.writeEndElement();
        }
        w.writeEndElement();
      }
      w.writeEndElement();

      w.writeEndElement();
      w.writeEndElement();
      w.writeEndDocument();
      w.close();
      return out.toString();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("the credit transfer initiation could not be written", e);
    }
  }

  /**
   * Text as the SEPA Latin character set carries it: accents dropped, an ampersand as a plus,
   * anything else a space, runs of spaces closed up, cut to the limit.
   */
  static String latin(String s, int max) {
    if (s == null) return "";
    String t =
        Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('&', '+');
    t = NOT_LATIN.matcher(t).replaceAll(" ").replaceAll(" {2,}", " ").trim();
    return t.length() <= max ? t : t.substring(0, max).trim();
  }

  /** An identifier SEPA accepts as it is: 1 to 35 Latin characters, no edge or double slash. */
  static String identifier(String id, String what) {
    if (id == null
        || id.isBlank()
        || id.length() > MAX_ID
        || NOT_LATIN.matcher(id).find()
        || id.startsWith("/")
        || id.endsWith("/")
        || id.contains("//")) {
      throw new IllegalArgumentException(
          what + " must be 1 to 35 SEPA Latin characters, without an edge or double slash");
    }
    return id;
  }

  private static BigDecimal amount(BigDecimal amount, String who) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException(who + " is paid nothing");
    }
    // Two places on purpose, not as an assumption about money: a SEPA credit transfer is in euro
    // only (Ccy is fixed to EUR and the service writes it for a EUR run alone), and the euro has
    // two minor units (ISO 4217; the EPC rulebook allows no more).
    if (amount.stripTrailingZeros().scale() > 2) {
      throw new IllegalArgumentException(who + " is paid in fractions of a cent");
    }
    if (amount.compareTo(MAX_AMOUNT) > 0) {
      throw new IllegalArgumentException(who + " is paid more than one SEPA transfer carries");
    }
    return amount.setScale(2);
  }

  private static Account account(Account a, String who) {
    if (a == null) throw new IllegalArgumentException(who + " has no account");
    String name = latin(a.name(), MAX_NAME);
    if (name.isEmpty()) {
      throw new IllegalArgumentException(who + " has no name SEPA can carry");
    }
    try {
      String bic = a.bic() == null || a.bic().isBlank() ? null : BankAccount.bic(a.bic());
      return new Account(name, BankAccount.iban(a.iban()), bic);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(who + ": " + e.getMessage(), e);
    }
  }

  private static void leaf(XMLStreamWriter w, String name, String text) throws XMLStreamException {
    w.writeStartElement(name);
    w.writeCharacters(text);
    w.writeEndElement();
  }

  private static void party(XMLStreamWriter w, String element, String name)
      throws XMLStreamException {
    w.writeStartElement(element);
    leaf(w, "Nm", name);
    w.writeEndElement();
  }

  private static void iban(XMLStreamWriter w, String element, String iban)
      throws XMLStreamException {
    w.writeStartElement(element);
    w.writeStartElement("Id");
    leaf(w, "IBAN", iban);
    w.writeEndElement();
    w.writeEndElement();
  }

  /**
   * A bank by its BIC. The debtor's agent is mandatory in the schema, so an unknown one is written
   * NOTPROVIDED as the EPC guidelines allow; a creditor's agent is simply left out.
   */
  private static void agent(XMLStreamWriter w, String element, String bic, boolean required)
      throws XMLStreamException {
    if (bic == null && !required) return;
    w.writeStartElement(element);
    w.writeStartElement("FinInstnId");
    if (bic != null) {
      leaf(w, "BICFI", bic);
    } else {
      w.writeStartElement("Othr");
      leaf(w, "Id", "NOTPROVIDED");
      w.writeEndElement();
    }
    w.writeEndElement();
    w.writeEndElement();
  }
}
