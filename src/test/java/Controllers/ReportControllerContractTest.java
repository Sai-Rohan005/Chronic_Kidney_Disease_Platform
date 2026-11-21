package Controllers;

import Database.ReportsRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import services.ReportService;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReportControllerContractTest {
    @org.junit.jupiter.api.AfterEach void clear() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void createsReportAtCollectionRouteWithJsonBody() throws Exception {
        ReportsRepository repository = mock(ReportsRepository.class);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("doctor@example.invalid", "token",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_Doctor"))));
        when(repository.getAppointment("507f1f77bcf86cd799439011")).thenReturn(new Document("doctor_email", "doctor@example.invalid"));
        Map<String, Object> results = Map.of("final_diagnosis", "No CKD");
        when(repository.createReport("507f1f77bcf86cd799439011", Map.of("results", results)))
                .thenReturn(new Document("appointment_id", "507f1f77bcf86cd799439011").append("results", results));
        standaloneSetup(new ReportController(new ReportService(repository))).build()
                .perform(post("/reports").contentType("application/json").content("""
                        {"appointment_id":"507f1f77bcf86cd799439011","results":{"final_diagnosis":"No CKD"}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"message":"Report created successfully",
                        "report":{"appointment_id":"507f1f77bcf86cd799439011","results":{"final_diagnosis":"No CKD"}}}
                        """));
        verify(repository).createReport("507f1f77bcf86cd799439011", Map.of("results", results));
    }
}
