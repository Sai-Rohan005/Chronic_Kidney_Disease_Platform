package services;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class ServiceClientsTest {

    @Test
    void appointmentClientFetchesAppointmentById() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AppointmentClient client = new AppointmentClient(
                builder, defaultEndpoint("appointment.appointment-url")
        );

        server.expect(requestTo(
                        "http://localhost:8082/appointments/507f1f77bcf86cd799439011"
                ))
                .andExpect(method(GET))
                .andExpect(content().string(""))
                .andRespond(withSuccess(
                        "{\"data\":{\"clinical_data\":{\"age\":50},\"ultrasound_data\":[\"https://example.invalid/image.png\"]}}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> appointment = client.getAppointment("507f1f77bcf86cd799439011");

        assertEquals(Map.of("age", 50), appointment.get("clinical_data"));
        assertEquals(List.of("https://example.invalid/image.png"), appointment.get("ultrasound_data"));
        server.verify();
    }

    @Test
    void reportClientPostsReportAndReturnsStoredReport() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReportClient client = new ReportClient(
                builder, defaultEndpoint("report.report-url")
        );
        Map<String, Object> report = Map.of(
                "appointment_id", "abc123",
                "results", Map.of("final_diagnosis", "No CKD")
        );

        server.expect(requestTo("http://localhost:8083/reports"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(
                        "{\"appointment_id\":\"abc123\",\"results\":{\"final_diagnosis\":\"No CKD\"}}"
                ))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"message\":\"Report created successfully\",\"report\":{\"appointment_id\":\"abc123\",\"results\":{\"final_diagnosis\":\"No CKD\"}}}"
                ));

        Map<String, Object> storedReport = client.storeReport(report);

        assertEquals(report, storedReport.get("report"));
        server.verify();
    }
    @Test
    void appointmentClientRejectsInvalidResponseEnvelope() {
        for (String body : List.of("{}", "{\"data\":null}", "{\"data\":[]}")) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            AppointmentClient client = new AppointmentClient(builder, defaultEndpoint("appointment.appointment-url"));
            server.expect(requestTo("http://localhost:8082/appointments/507f1f77bcf86cd799439011"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThrows(RestClientException.class, () -> client.getAppointment("507f1f77bcf86cd799439011"));
            server.verify();
        }
    }

    private static String defaultEndpoint(String key) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        return new MockEnvironment().resolveRequiredPlaceholders(yaml.getObject().getProperty(key));
    }
}
