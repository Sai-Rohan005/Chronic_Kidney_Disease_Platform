package com.ckd.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationTest {
    @Test void generatesAnIdForMissingOrUnsafeInputAndCleansMdc() throws Exception {
        for (String input : new String[] {null, "unsafe@example.invalid", "bad\r\nvalue", "a".repeat(100)}) {
            var request = new MockHttpServletRequest();
            if (input != null) request.addHeader("X-Request-Id", input);
            var response = new MockHttpServletResponse();
            new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
                assertNotNull(MDC.get("requestId"));
                assertEquals(response.getHeader("X-Request-Id"), MDC.get("requestId"));
            });
            assertDoesNotThrow(() -> java.util.UUID.fromString(response.getHeader("X-Request-Id")));
            assertNull(MDC.get("requestId"));
        }
    }
    @Test void preservesSafeIdAndNeverLogsHeadersPathsOrFailureDetails() throws Exception {
        var request = new MockHttpServletRequest("POST", "/patients/private@example.invalid");
        request.addHeader("X-Request-Id", "correlation-test-123");
        request.addHeader("Authorization", "Bearer private-token");
        request.setContent("patient clinical data".getBytes());
        var response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            assertThrows(jakarta.servlet.ServletException.class, () -> new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
                throw new jakarta.servlet.ServletException("private clinical data");
            }));
            assertEquals("correlation-test-123", response.getHeader("X-Request-Id"));
            assertEquals(1, logs.list.size());
            var event = logs.list.getFirst();
            assertEquals("correlation-test-123", event.getMDCPropertyMap().get("requestId"));
            assertEquals("500", event.getMDCPropertyMap().get("status"));
            assertEquals("request_completed", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            assertFalse(event.toString().contains("private"));
            assertNull(MDC.get("requestId"));
        } finally { logger.detachAppender(logs); logs.stop(); MDC.clear(); }
    }
}
