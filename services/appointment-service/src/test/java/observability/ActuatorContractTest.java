package observability;

import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = com.example.ckd_appointment_service.CkdAppointmentServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "management.health.defaults.enabled=false"})
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ActuatorContractTest {
    @Autowired Environment environment;
    @DynamicPropertySource static void secret(DynamicPropertyRegistry properties) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        properties.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
    }
    private HttpResponse<String> get(int port, String path) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void managementEndpointsAreAvailableOnlyOnTheSeparatePortAndPrometheusHasHttpHistograms(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        int mainPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        assertNotEquals(mainPort, managementPort);
        assertEquals(environment.getProperty("mongodb.database"), environment.getProperty("spring.mongodb.database"));
        var rejected = get(mainPort, "/actuator/health");
        assertEquals(401, rejected.statusCode());
        assertTrue(rejected.headers().firstValue("X-Request-Id").isPresent());
        assertEquals(401, get(mainPort, "/private-patient@example.invalid?clinical=private-data").statusCode());
        assertEquals(200, get(managementPort, "/actuator/health").statusCode());
        assertEquals(200, get(managementPort, "/actuator/metrics").statusCode());
        var metrics = get(managementPort, "/actuator/prometheus");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("http_server_requests_seconds_bucket"));
        assertTrue(metrics.body().contains("application=\"" + environment.getProperty("spring.application.name") + "\""));
        assertEquals(403, get(managementPort, "/actuator/env").statusCode());
        assertTrue(output.toString().contains("\"service\":\"" + environment.getProperty("spring.application.name") + "\""));
        assertFalse(output.toString().contains("private-patient"));
        assertFalse(output.toString().contains("private-data"));
        assertFalse(metrics.body().contains("private-patient"));
        assertFalse(output.toString().contains("Using generated security password"));
    }
}
