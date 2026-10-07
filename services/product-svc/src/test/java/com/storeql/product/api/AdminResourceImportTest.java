package com.storeql.product.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.product.dto.Dtos.SupplierCsvImportRequest;
import com.storeql.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the two import endpoints say about a body before any service is asked: nothing here reaches
 * the service or the tenant context, so a refusal is proved to come from the boundary.
 */
class AdminResourceImportTest {

  private final AdminResource resource = new AdminResource();

  @Test
  @DisplayName("Both imports refuse a request with no body, in the platform's own words")
  void bothImportsRefuseAMissingBody() {
    ApiException bulk = assertThrows(ApiException.class, () -> resource.bulkImport(null));
    assertThat(bulk.status(), is(400));
    assertThat(bulk.code(), is("BODY_REQUIRED"));

    ApiException sheet = assertThrows(ApiException.class, () -> resource.importSupplierCsv(null));
    assertThat(sheet.status(), is(400));
    assertThat(sheet.code(), is("BODY_REQUIRED"));
  }

  @Test
  @DisplayName("A supplier sheet request with no csv is a validation failure naming the field")
  void aSupplierRequestWithNoCsvNamesTheField() {
    for (String csv : new String[] {null, "", "   "}) {
      ApiException refused =
          assertThrows(
              ApiException.class,
              () ->
                  resource.importSupplierCsv(
                      new SupplierCsvImportRequest(csv, "ADD", null, null, null)));
      assertThat(refused.status(), is(400));
      assertThat(refused.code(), is("VALIDATION_FAILED"));
      // "csv: <the validator's words>": the field is named, whatever language the words are in.
      assertThat(refused.details(), hasItem(startsWith("csv: ")));
    }
  }
}
