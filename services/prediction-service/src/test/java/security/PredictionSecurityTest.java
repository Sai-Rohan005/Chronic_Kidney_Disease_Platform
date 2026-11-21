package security;
import Controllers.PredictionController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import services.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PredictionSecurityTest extends SecurityTestSupport {
    private static final String ID = "507f1f77bcf86cd799439011";
    @Override protected Class<?> config() { return Config.class; }
    @Override protected String protectedPath() { return "/predictions"; }
    @Test void patientIs403BeforeModelsAreCalled() throws Exception {
        mvc.perform(post(protectedPath()).contentType("application/json").content("{\"appointment_id\":\"" + ID + "\"}")
                .header("Authorization", bearer("owner@example.invalid", "Patient"))).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(CkdModelClient.class));
    }
    @Test void unassignedDoctorIs403BeforeModelsAreCalled() throws Exception {
        mvc.perform(post(protectedPath()).contentType("application/json").content("{\"appointment_id\":\"" + ID + "\"}")
                .header("Authorization", bearer("other@example.invalid", "Doctor"))).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(CkdModelClient.class));
    }
    @Test void assignedDoctorCanPredictUsingControlledFixtures() throws Exception {
        mvc.perform(post(protectedPath()).contentType("application/json").content("{\"appointment_id\":\"" + ID + "\"}")
                .header("Authorization", bearer("assigned@example.invalid", "Doctor"))).andExpect(status().isOk());
        verify(context.getBean(CkdModelClient.class)).predictClinicalData(Map.of("age", 50));
        verify(context.getBean(ReportClient.class)).storeReport(anyMap());
    }
    @Configuration static class Config {
        @Bean AppointmentClient appointments() {
            var client = mock(AppointmentClient.class);
            when(client.getAppointment(ID)).thenReturn(Map.of("patient_email", "owner@example.invalid", "doctor_email", "assigned@example.invalid",
                    "clinical_data", Map.of("age", 50), "ultrasound_data", List.of("https://example.invalid/test.png"))); return client;
        }
        @Bean ReportClient reports() { var client = mock(ReportClient.class); when(client.storeReport(anyMap())).thenReturn(Map.of("report", Map.of())); return client; }
        @Bean CkdModelClient models() {
            var client = mock(CkdModelClient.class);
            // These are test fixtures, not measured model performance.
            var fixture = Map.<String, Object>of("prediction", 0, "probabilities", List.of(0.8, 0.2));
            when(client.predictClinicalData(anyMap())).thenReturn(fixture); when(client.predictImage(anyString())).thenReturn(fixture); return client;
        }
        @Bean CkdPredictionService service(AppointmentClient a, ReportClient r, CkdModelClient m) { return new CkdPredictionService(a, r, m); }
        @Bean PredictionController controller(CkdPredictionService service) { return new PredictionController(service); }
    }
}
