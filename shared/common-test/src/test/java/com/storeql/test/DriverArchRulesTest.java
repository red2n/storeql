package com.storeql.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

class DriverArchRulesTest {

  private static JavaClasses of(String pkg) {
    return new ClassFileImporter().importPackages("com.storeql.test.fixture." + pkg);
  }

  @Test
  void httpClientsAreFineInsideClientAndRefusedElsewhere() {
    StoreQlArchRules.DRIVERS_STAY_BEHIND_THE_INTERFACE.check(of("service"));
    assertThrows(
        AssertionError.class,
        () -> StoreQlArchRules.DRIVERS_STAY_BEHIND_THE_INTERFACE.check(of("other")));
  }

  @Test
  void serviceMayUseTheInterfaceButNotAConcreteDriver() {
    var rule = StoreQlArchRules.serviceUsesOnlyTheDriverInterface("..fixture.client..");
    JavaClasses all = new ClassFileImporter().importPackages("com.storeql.test.fixture");
    EvaluationResult r = rule.evaluate(all);
    assertTrue(r.hasViolation());
    String text = r.getFailureReport().toString();
    assertTrue(text.contains("BadService"), text);
    assertFalse(text.contains("GoodService"), text);
  }
}
