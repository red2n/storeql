package com.storeql.product.domain.imports;

/**
 * A file that cannot be read as a table at all (intent/catalogue-import.md): the whole file is
 * refused, with a stable code and, when it applies, the line it was found on.
 */
public final class CsvException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The file is empty, or holds no header row. */
  public static final String EMPTY = "IMPORT_FILE_EMPTY";

  /** A quoted cell is never closed. */
  public static final String MALFORMED = "IMPORT_CSV_MALFORMED";

  /** More data rows than one import takes. */
  public static final String TOO_MANY_ROWS = "IMPORT_TOO_MANY_ROWS";

  private final String code;
  private final int line;

  public CsvException(String code, String message, int line) {
    super(message);
    this.code = code;
    this.line = line;
  }

  /** The stable machine code. */
  public String code() {
    return code;
  }

  /** The line the problem was found on, or 0 when it is the file's as a whole. */
  public int line() {
    return line;
  }
}
