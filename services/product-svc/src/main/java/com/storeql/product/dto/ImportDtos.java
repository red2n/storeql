package com.storeql.product.dto;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Wire shapes for the catalogue import (intent/catalogue-import.md). */
public final class ImportDtos {

  private ImportDtos() {}

  @Schema(
      name = "ImportMappingRequest",
      description =
          "How a business's export maps onto StoreQL: which header holds which field, what its VAT"
              + " codes and sold-by words mean, how a price is written and whether it is a shelf"
              + " price. Saved once under a name and applied to every store's file by header text.")
  public record ImportMappingRequest(
      @Schema(
              description =
                  "field → header text. Fields: sku and name (required), barcode, category, vatCode,"
                      + " price, cost, soldBy, unit, brand, stockQty, expiry.")
          Map<String, String> columns,
      @Schema(description = "Further barcode columns: an old code, a multipack, a case.")
          List<AliasColumn> aliasColumns,
      @Schema(description = "The file's VAT code → the business's own VAT code (T1, T5…).")
          Map<String, String> vatCodes,
      @Schema(description = "The VAT code for a row whose VAT cell is blank; never guessed.")
          String defaultVatCode,
      @Schema(description = "INCLUSIVE (the prices are shelf prices, VAT inside) or EXCLUSIVE.")
          String priceBasis,
      @Schema(description = "'.' or ',': how the file writes a decimal.") String decimalMark,
      @Schema(description = "A date pattern for the expiry column, e.g. dd/MM/yyyy.")
          String dateFormat,
      @Schema(description = "The file's sold-by word → EACH, WEIGHT, VOLUME or LENGTH.")
          Map<String, String> soldByValues,
      @Schema(description = "What separates the levels of a category path, e.g. '>'.")
          String categorySeparator) {}

  @Schema(name = "ImportAliasColumn")
  public record AliasColumn(
      String header,
      @Schema(description = "OLD_EAN, MULTIPACK, CASE or PLU.") String kind,
      @Schema(description = "How many units a scan of this code stands for.") int packQty) {}

  @Schema(name = "ImportMappingResponse")
  public record ImportMappingResponse(
      String name,
      String hash,
      String updatedAt,
      @Schema(description = "The mapping as saved.") JsonObject mapping) {}

  @Schema(name = "ImportJobResponse")
  public record ImportJobResponse(
      String id,
      @Schema(description = "DRY_RUN or APPLY.") String kind,
      String status,
      String storeId,
      String fileId,
      String mappingHash,
      String fileSha256,
      String priceListId,
      String phase,
      @Schema(
              description =
                  "The report: rows, per action, refusals and gaps by code, the file's VAT codes.")
          JsonObject summary,
      String failureCode,
      String failureDetail,
      String createdAt,
      String finishedAt) {}

  @Schema(name = "ImportRowResponse")
  public record ImportRowResponse(
      int line,
      String sku,
      @Schema(description = "CREATE, UPDATE, UNCHANGED, SKIPPED or REFUSED.") String action,
      JsonArray refusals,
      JsonArray gaps,
      JsonArray changes) {}
}
