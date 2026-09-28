package com.storeql.iam.domain;

import java.util.UUID;

/** A staff login as its business names it: the user id and the email it signs in with. */
public record StaffLogin(UUID userId, String email) {}
