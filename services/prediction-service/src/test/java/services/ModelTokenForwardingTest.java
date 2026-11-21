package services;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ModelTokenForwardingTest {
    private final PredictionRuntime runtime = new PredictionRuntime(new PredictionSettings());
    @BeforeEach void caller() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("assigned@example.invalid", "caller-token",
                List.of(new SimpleGrantedAuthority("ROLE_Doctor"))));
    }
    @AfterEach void clear() { runtime.close(); SecurityContextHolder.clearContext(); }
    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void bothModelRequestsForwardTheCallerToken() throws Exception {
        HttpClient http = mock(HttpClient.class); HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        // Controlled fixture, not model performance data.
        when(response.body()).thenReturn("{\"prediction\":0,\"probabilities\":[0.8,0.2]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new CkdModelClient(http, "http://localhost:8001/routes/tab_predict", "http://localhost:8001/routes/unet_predict", runtime);
        client.predictClinicalData(Map.of("age", 50)); client.predictImage("https://res.cloudinary.com/demo/test.png");
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        for (var request : requests.getAllValues()) {
            assertEquals("Bearer caller-token", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("POST", request.method());
        }
        assertEquals("/routes/tab_predict", requests.getAllValues().get(0).uri().getPath());
        assertEquals("/routes/unet_predict", requests.getAllValues().get(1).uri().getPath());
    }
    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void upstreamSecurityStatusesArePreserved() throws Exception {
        HttpClient http = mock(HttpClient.class); HttpResponse response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new CkdModelClient(http, "http://localhost:8001/routes/tab_predict", "http://localhost:8001/routes/unet_predict", runtime);
        for (int status : List.of(401, 403)) {
            when(response.statusCode()).thenReturn(status);
            assertEquals(status, assertThrows(ResponseStatusException.class,
                    () -> client.predictClinicalData(Map.of("age", 50))).getStatusCode().value());
        }
    }
}
