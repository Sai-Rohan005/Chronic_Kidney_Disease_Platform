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
    @Test void createsReportAtCollectionRouteWithJsonBody() throws Exception {
        ReportsRepository repository = mock(ReportsRepository.class);
        Map<String, Object> results = Map.of("final_diagnosis", "No CKD");
        when(repository.createReport("abc123", Map.of("results", results)))
                .thenReturn(new Document("appointment_id", "abc123").append("results", results));
        standaloneSetup(new ReportController(new ReportService(repository))).build()
                .perform(post("/reports").contentType("application/json").content("""
                        {"appointment_id":"abc123","results":{"final_diagnosis":"No CKD"}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"message":"Report created successfully",
                        "report":{"appointment_id":"abc123","results":{"final_diagnosis":"No CKD"}}}
                        """));
        verify(repository).createReport("abc123", Map.of("results", results));
    }
}
