package com.ckd.auth;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestCorrelation {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private RequestCorrelation() {}
    public static String normalize(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : UUID.randomUUID().toString();
    }
    public static String currentId() { return normalize(MDC.get(MDC_KEY)); }
}
