package com.storeql.gateway;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifies the gateway's WebClient for its own lookups on the request path (key set, tenant
 * status, plan allowances, API-key introspection): short connect and read timeouts, unlike the
 * proxy client that waits as long as a business call may take.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  java.lang.annotation.ElementType.FIELD,
  java.lang.annotation.ElementType.METHOD,
  java.lang.annotation.ElementType.PARAMETER,
  java.lang.annotation.ElementType.TYPE
})
public @interface ControlPlane {}
