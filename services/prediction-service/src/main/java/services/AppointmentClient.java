package services;

import com.ckd.auth.CurrentCaller;
import org.springframework.http.HttpHeaders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class AppointmentClient {

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final URI endpoint;
    private final PredictionRuntime runtime;

    public AppointmentClient(
            RestClient.Builder restClientBuilder,
            @Value("${appointment.appointment-url}") String appointmentUrl, PredictionRuntime runtime
    ) {
        this.restClient = restClientBuilder.build();
        this.runtime = runtime;
        this.endpoint = URI.create(appointmentUrl);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAppointment(String appointmentId) {
        URI requestUri = UriComponentsBuilder.fromUri(endpoint)
                .pathSegment("{id}")
                .encode()
                .buildAndExpand(appointmentId)
                .toUri();
        return runtime.call("Appointment", true, () -> {
            Map<String, Object> response = restClient.get()
                    .uri(requestUri)
                    .header(HttpHeaders.AUTHORIZATION, CurrentCaller.authorizationHeader())
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null || !(response.get("data") instanceof Map<?, ?>)) {
                throw new RestClientException("Appointment response must contain a data object");
            }
            return (Map<String, Object>) response.get("data");
        });
    }
}
