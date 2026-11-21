package services;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PredictionAuthorizationTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void serviceRejectsPatientsBeforeAnyUpstreamCall() {
        var appointments = mock(AppointmentClient.class); var reports = mock(ReportClient.class); var models = mock(CkdModelClient.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("p@example.invalid", "token",
                List.of(new SimpleGrantedAuthority("ROLE_Patient"))));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> new CkdPredictionService(appointments, reports, models).predict(Map.of("appointment_id", ID))).getStatusCode().value());
        verifyNoInteractions(appointments, reports, models);
    }
    @Test void serviceRejectsUnassignedDoctor() {
        var appointments = mock(AppointmentClient.class); var reports = mock(ReportClient.class); var models = mock(CkdModelClient.class);
        when(appointments.getAppointment(ID)).thenReturn(Map.of("patient_email", "p@example.invalid", "doctor_email", "assigned@example.invalid"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("other@example.invalid", "token",
                List.of(new SimpleGrantedAuthority("ROLE_Doctor"))));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> new CkdPredictionService(appointments, reports, models).predict(Map.of("appointment_id", ID))).getStatusCode().value());
        verifyNoInteractions(reports, models);
    }
}
