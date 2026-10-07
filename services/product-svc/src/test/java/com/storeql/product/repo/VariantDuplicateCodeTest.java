package com.storeql.product.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class VariantDuplicateCodeTest {

  private static final String PREFIX = "ERROR: duplicate key value violates unique constraint \"";

  @Test
  void theSkuConstraintIsNamed() {
    assertEquals(
        "PRODUCT_SKU_DUPLICATE",
        ProductRepository.duplicateCode(PREFIX + "product_variants_tenant_id_sku_key\""));
  }

  @Test
  void theBarcodeIndexIsNamed() {
    assertEquals(
        "PRODUCT_BARCODE_DUPLICATE",
        ProductRepository.duplicateCode(PREFIX + "uq_variants_tenant_barcode\""));
  }

  @Test
  void anyOtherUniqueValueStaysGeneric() {
    assertEquals("DUPLICATE", ProductRepository.duplicateCode(PREFIX + "uq_uom_item_conv\""));
    assertEquals("DUPLICATE", ProductRepository.duplicateCode(null));
  }

  @Test
  void theRefusalIsA409WithTheNamedCode() {
    var e =
        ProductRepository.duplicate(
            new SQLException(PREFIX + "uq_variants_tenant_barcode\"", "23505"));
    assertEquals(409, e.status());
    assertEquals("PRODUCT_BARCODE_DUPLICATE", e.code());
  }
}
