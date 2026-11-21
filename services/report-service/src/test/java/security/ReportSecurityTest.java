package security;
import Controllers.ReportController;
import Database.ReportsRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import services.ReportService;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ReportSecurityTest extends SecurityTestSupport {
    private static final String ID = "507f1f77bcf86cd799439011";
    @Override protected Class<?> config() { return Config.class; }
    @Override protected String protectedPath() { return "/reports/" + ID; }
    @Test void anotherPatientsReportIs403() throws Exception {
        mvc.perform(post("/reports/getReport").contentType("application/json").content("{\"appointmentId\":\"" + ID + "\"}")
                .header("Authorization", bearer("other@example.invalid", "Patient"))).andExpect(status().isForbidden());
    }
    @Test void unassignedDoctorCannotModifyReport() throws Exception {
        mvc.perform(patch(protectedPath()).contentType("application/json").content("{\"results\":{}}")
                .header("Authorization", bearer("other@example.invalid", "Doctor"))).andExpect(status().isForbidden());
    }
    @Test void patientCannotCreateReport() throws Exception {
        mvc.perform(post("/reports").contentType("application/json").content("{\"appointment_id\":\"" + ID + "\",\"results\":{}}")
                .header("Authorization", bearer("owner@example.invalid", "Patient"))).andExpect(status().isForbidden());
    }
    @Test void ownerAndAssignedDoctorCanReadReport() throws Exception {
        for (var user : java.util.Map.of("owner@example.invalid", "Patient", "assigned@example.invalid", "Doctor").entrySet()) {
            mvc.perform(post("/reports/getReport").contentType("application/json").content("{\"appointmentId\":\"" + ID + "\"}")
                    .header("Authorization", bearer(user.getKey(), user.getValue()))).andExpect(status().isOk());
        }
    }
    @Configuration static class Config {
        @Bean ReportsRepository repository() {
            var repo = mock(ReportsRepository.class);
            when(repo.getAppointment(ID)).thenReturn(new Document("patient_email", "owner@example.invalid").append("doctor_email", "assigned@example.invalid"));
            when(repo.getReport(ID)).thenReturn(new Document("appointment_id", ID)); return repo;
        }
        @Bean ReportService service(ReportsRepository repo) { return new ReportService(repo); }
        @Bean ReportController controller(ReportService service) { return new ReportController(service); }
    }
}
