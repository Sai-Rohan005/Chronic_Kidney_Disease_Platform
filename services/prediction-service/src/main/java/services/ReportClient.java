package services;

import com.ckd.auth.CurrentCaller;
import org.springframework.http.HttpHeaders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

@Component
public class ReportClient {

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final URI endpoint;
    private final PredictionRuntime runtime;

    public ReportClient(
            RestClient.Builder restClientBuilder,
            @Value("${report.report-url}") String reportUrl, PredictionRuntime runtime
    ) {
        this.restClient = restClientBuilder.build();
        this.runtime = runtime;
        this.endpoint = URI.create(reportUrl);
    }

    public Map<String, Object> getReport(String appointmentId) {
        try {
            return runtime.call("Report", true, () -> read(restClient.get().uri(endpoint + "/" + appointmentId)));
        } catch (UpstreamException exception) {
            if (exception.status() == 404) return null;
            throw exception;
        }
    }

    public Map<String, Object> storeReport(Map<String, Object> report) {
        // Creation is not retried: a lost response may follow a successful insert.
        return runtime.timed("ckd.report.store", "create", () ->
                runtime.call("Report", false, () -> read(restClient.post().uri(endpoint).body(report))));
    }

    public Map<String, Object> updateReport(String appointmentId, Map<String, Object> fields) {
        // Deliberately do not retry writes, including force replacement.
        return runtime.timed("ckd.report.store", "update", () ->
                runtime.call("Report", false, () -> read(restClient.patch().uri(endpoint + "/" + appointmentId).body(fields))));
    }

    private Map<String, Object> read(RestClient.RequestHeadersSpec<?> request) {
        Map<String, Object> response = request.header(HttpHeaders.AUTHORIZATION, CurrentCaller.authorizationHeader())
                .header(com.ckd.auth.RequestCorrelation.HEADER, com.ckd.auth.RequestCorrelation.currentId())
                .retrieve().body(RESPONSE_TYPE);
        if (response == null || !(response.get("report") instanceof Map<?, ?>)) {
            throw new org.springframework.web.client.RestClientException("Report response must contain a report object");
        }
        return response;
    }
}
