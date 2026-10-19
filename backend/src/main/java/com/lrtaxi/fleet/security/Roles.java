package com.lrtaxi.fleet.security;

/** SpEL expressions for @PreAuthorize. Server-side authorisation is applied to every sensitive endpoint. */
public final class Roles {

    public static final String ADMIN = "hasRole('ADMIN')";
    /** Managers and admins: deactivate vehicles, replace imported data, resolve alerts, edit masters. */
    public static final String MANAGE = "hasAnyRole('ADMIN','MANAGER')";
    /** Data entry staff and above. */
    public static final String WRITE = "hasAnyRole('ADMIN','MANAGER','OPERATOR')";
    /** Any signed-in role. */
    public static final String READ = "hasAnyRole('ADMIN','MANAGER','OPERATOR','VIEWER')";

    private Roles() {
    }
}
