package services;
import Database.ReportsRepository;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReportOwnershipTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void user(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, "token",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    @Test void patientCannotReadAnotherPatientsReport() {
        var repository = mock(ReportsRepository.class);
        when(repository.getAppointment(ID)).thenReturn(new Document("patient_email", "owner@example.invalid").append("doctor_email", "assigned@example.invalid"));
        when(repository.getReport(ID)).thenReturn(new Document("appointment_id", ID));
        user("other@example.invalid", "Patient");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> new ReportService(repository).getReport(ID)).getStatusCode().value());
    }
    @Test void patientsCannotCreateOrUpdateReports() {
        var repository = mock(ReportsRepository.class);
        user("owner@example.invalid", "Patient");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> new ReportService(repository).createReport(Map.of("appointment_id", ID, "results", Map.of("test", true)))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> new ReportService(repository).updateReport(ID, Map.of("results", Map.of("test", true)))).getStatusCode().value());
    }
}
