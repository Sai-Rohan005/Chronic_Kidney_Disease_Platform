package services;
import Database.AppointmentRepository;
import com.cloudinary.Cloudinary;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AppointmentOwnershipTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    private AppointmentRepository repository;
    private AppointmentService service;
    @BeforeEach void setup() {
        repository = mock(AppointmentRepository.class);
        service = new AppointmentService(repository, mock(Cloudinary.class));
        when(repository.getAppointment(ID)).thenReturn(new Document("patient_email", "owner@example.invalid")
                .append("doctor_email", "assigned@example.invalid"));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void user(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, "token",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    @Test void patientCannotReadAnotherPatientAppointment() {
        user("other@example.invalid", "Patient");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.getAppointment(ID)).getStatusCode().value());
    }
    @Test void doctorCannotReadUnassignedAppointment() {
        user("other-doctor@example.invalid", "Doctor");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.getAppointment(ID)).getStatusCode().value());
    }
    @Test void patientCannotUpdateAnotherPatientAppointment() {
        user("other@example.invalid", "Patient");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.updateAppointment(ID, Map.of("status", "completed"))).getStatusCode().value());
        verify(repository, never()).updateAppointment(any(), any(), any());
    }
    @Test void patientCannotListAnotherPatientsImages() {
        user("other@example.invalid", "Patient");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.getAllImages("owner@example.invalid")).getStatusCode().value());
        verify(repository, never()).getAllImages(any());
    }
    @Test void patientCannotBookForAnotherPatient() {
        user("other@example.invalid", "Patient");
        var body = Map.<String, Object>of("patient_email", "owner@example.invalid", "doctor_email", "assigned@example.invalid", "date", "2026-12-01");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.bookAppointment(body)).getStatusCode().value());
        verify(repository, never()).reserveAppointment(any(), any(), any(), any());
    }
    @Test void ownerAndAssignedDoctorCanRead() {
        user("owner@example.invalid", "Patient"); assertEquals(200, service.getAppointment(ID).status());
        user("assigned@example.invalid", "Doctor"); assertEquals(200, service.getAppointment(ID).status());
    }
}
