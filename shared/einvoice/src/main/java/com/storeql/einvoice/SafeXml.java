package com.storeql.einvoice;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads a document someone else wrote without letting it reach anything but its own bytes.
 *
 * <p>An e-invoice arrives from a supplier, an access point or an upload, so it is hostile until
 * read: a DTD is refused outright (which removes external entities, XXE and entity expansion
 * together), and size, depth, element and attribute counts are capped before any of it is
 * interpreted. What survives is a small tree the syntax readers walk.
 */
final class SafeXml {

  /** 10 MB: Peppol's attachments travel inside the XML, so this is the whole message. */
  static final int MAX_BYTES = 10 * 1024 * 1024;

  static final int MAX_DEPTH = 40;
  static final int MAX_ELEMENTS = 250_000;
  static final int MAX_ATTRIBUTES = 16;

  /** A parsed document, and how many empty leaf elements it had. */
  record Parsed(XmlElement root, int emptyLeaves) {}

  /**
   * Configured once. The factory is only used to create readers (every reader is its own object),
   * and every property is set here at class load and never changed afterwards.
   */
  private static final XMLInputFactory FACTORY = newFactory();

  private static XMLInputFactory newFactory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    return factory;
  }

  private SafeXml() {}

  static Parsed parse(byte[] xml) {
    if (xml == null || xml.length == 0) {
      throw new EInvoiceFormatException("EMPTY", "the document is empty");
    }
    if (xml.length > MAX_BYTES) {
      throw new EInvoiceFormatException(
          "TOO_LARGE", "the document is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
    }
    try {
      XMLStreamReader r = FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        return read(r);
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new EInvoiceFormatException("NOT_XML", "the document is not well-formed XML", e);
    }
  }

  private static Parsed read(XMLStreamReader r) throws XMLStreamException {
    Deque<XmlElement> open = new ArrayDeque<>();
    XmlElement root = null;
    int elements = 0;
    int empty = 0;
    while (r.hasNext()) {
      int event = r.next();
      switch (event) {
        case XMLStreamConstants.DTD ->
            throw new EInvoiceFormatException(
                "DTD_REFUSED", "an e-invoice may not declare a DTD or entities");
        case XMLStreamConstants.ENTITY_REFERENCE ->
            throw new EInvoiceFormatException(
                "DTD_REFUSED", "an e-invoice may not refer to entities");
        case XMLStreamConstants.START_ELEMENT -> {
          if (++elements > MAX_ELEMENTS) {
            throw new EInvoiceFormatException(
                "TOO_LARGE", "the document has more than " + MAX_ELEMENTS + " elements");
          }
          if (open.size() >= MAX_DEPTH) {
            throw new EInvoiceFormatException(
                "TOO_LARGE", "the document nests deeper than " + MAX_DEPTH + " elements");
          }
          if (r.getAttributeCount() > MAX_ATTRIBUTES) {
            throw new EInvoiceFormatException(
                "TOO_LARGE", "an element has more than " + MAX_ATTRIBUTES + " attributes");
          }
          Map<String, String> attributes = new HashMap<>();
          for (int i = 0; i < r.getAttributeCount(); i++) {
            attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
          }
          XmlElement element = new XmlElement(r.getNamespaceURI(), r.getLocalName(), attributes);
          if (open.isEmpty()) {
            if (root != null) throw new XMLStreamException("more than one root element");
            root = element;
          } else {
            open.peek().add(element);
          }
          open.push(element);
        }
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
          if (!open.isEmpty()) open.peek().append(r.getText());
        }
        case XMLStreamConstants.END_ELEMENT -> {
          if (open.pop().isEmptyLeaf()) empty++;
        }
        default -> {
          // comments, processing instructions and the document's own start and end carry nothing
        }
      }
    }
    if (root == null) {
      throw new EInvoiceFormatException("NOT_XML", "the document has no root element");
    }
    return new Parsed(root, empty);
  }
}
