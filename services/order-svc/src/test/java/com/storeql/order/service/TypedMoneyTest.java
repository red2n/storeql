package com.storeql.order.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Money a person types — a till price, a discount, a deposit — is no finer than the business's
 * currency and is kept at its own scale: three places for the dinar, whole yen, pence. A figure
 * finer than that is refused by name, never rounded behind the typist's back.
 */
class TypedMoneyTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void anAmountIsKeptAtTheCurrencysOwnScale() {
    assertThat(TypedMoney.require(d("1.235"), "KWD", "unitPrice"), is(d("1.235")));
    assertThat(TypedMoney.require(d("1.2"), "KWD", "unitPrice"), is(d("1.200")));
    assertThat(TypedMoney.require(d("1000.00"), "JPY", "unitPrice"), is(d("1000")));
    assertThat(TypedMoney.require(d("10"), "GBP", "unitPrice"), is(d("10.00")));
    assertThat(TypedMoney.require(null, "GBP", "taxAmount"), is((BigDecimal) null));
  }

  @Test
  void anAmountFinerThanTheCurrencyIsRefusedByName() {
    for (String[] bad :
        new String[][] {{"1.2345", "KWD"}, {"333.4", "JPY"}, {"9.999", "GBP"}, {"0.005", "EUR"}}) {
      var e =
          assertThrows(
              ApiException.class, () -> TypedMoney.require(d(bad[0]), bad[1], "discountAmount"));
      assertThat(e.status(), is(400));
      assertThat(e.code(), is("VALIDATION_FAILED"));
      assertThat(e.details().get(0).startsWith("discountAmount: "), is(true));
    }
    var named =
        assertThrows(
            ApiException.class,
            () -> TypedMoney.require(d("9.999"), "GBP", "unitPrice", "ORDER_PRICE_INVALID"));
    assertThat(named.code(), is("ORDER_PRICE_INVALID"));
  }

  /**
   * An amount far past any money is refused at once, whichever way its exponent points: neither
   * scaling {@code 1E+80000000} nor writing {@code 1E-80000000} out in full for the refusal's
   * message is work an eleven-character body may cause.
   */
  @Test
  void anExtremeExponentIsRefusedAtOnce() {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () -> {
          for (String bad : new String[] {"1E+80000000", "1E-80000000"}) {
            var e =
                assertThrows(
                    ApiException.class, () -> TypedMoney.require(d(bad), "KWD", "unitPrice"));
            assertThat(e.code(), is("VALIDATION_FAILED"));
            assertThat(e.details().get(0).startsWith("unitPrice: "), is(true));
          }
        });
  }
}
