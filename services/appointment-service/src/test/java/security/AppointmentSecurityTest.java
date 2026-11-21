package security;
import Controllers.AppointmentController;
import Database.AppointmentRepository;
import com.cloudinary.Cloudinary;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import services.AppointmentService;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AppointmentSecurityTest extends SecurityTestSupport {
    private static final String ID = "507f1f77bcf86cd799439011";
    @Override protected Class<?> config() { return Config.class; }
    @Override protected String protectedPath() { return "/appointments/" + ID; }
    @Test void anotherPatientsDataIs403() throws Exception {
        mvc.perform(get(protectedPath()).header("Authorization", bearer("other@example.invalid", "Patient")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }
    @Test void unassignedDoctorIs403() throws Exception {
        mvc.perform(get(protectedPath()).header("Authorization", bearer("other@example.invalid", "Doctor"))).andExpect(status().isForbidden());
    }
    @Test void ownerAndAssignedDoctorCanRead() throws Exception {
        mvc.perform(get(protectedPath()).header("Authorization", bearer("owner@example.invalid", "Patient"))).andExpect(status().isOk());
        mvc.perform(get(protectedPath()).header("Authorization", bearer("assigned@example.invalid", "Doctor"))).andExpect(status().isOk());
    }
    @Test void listsOnlyIdentityFromSecurityContext() throws Exception {
        mvc.perform(get("/appointments").header("Authorization", bearer("owner@example.invalid", "Patient")))
                .andExpect(status().isOk());
        verify(context.getBean(AppointmentRepository.class)).getAllAppointments("owner@example.invalid", "Patient");
    }
    @Configuration static class Config {
        @Bean AppointmentRepository repository() {
            var repo = mock(AppointmentRepository.class);
            when(repo.getAppointment(ID)).thenReturn(new Document("patient_email", "owner@example.invalid").append("doctor_email", "assigned@example.invalid"));
            return repo;
        }
        @Bean AppointmentService service(AppointmentRepository repo) { return new AppointmentService(repo, mock(Cloudinary.class)); }
        @Bean AppointmentController controller(AppointmentService service) { return new AppointmentController(service); }
    }
}
