package Controllers;

import Database.AppointmentRepository;
import com.cloudinary.Cloudinary;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import services.AppointmentService;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class AppointmentControllerContractTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AppointmentRepository repository;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = webAppContextSetup(context).build();
        repository = context.getBean(AppointmentRepository.class);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("patient@example.invalid", "token",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_Patient"))));
    }
    @AfterEach void close() { context.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void fetchesByPathIdWithoutRequestBody() throws Exception {
        when(repository.getAppointment(ID)).thenReturn(new Document("clinical_data", Map.of("age", 50)).append("patient_email", "patient@example.invalid")
                .append("ultrasound_data", List.of("https://example.invalid/image.png")));
        mvc.perform(get("/appointments/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"data":{"clinical_data":{"age":50},
                        "ultrasound_data":["https://example.invalid/image.png"]}}
                        """));
        verify(repository).getAppointment(ID);
    }
    @Test void missingAppointmentIsNotFound() throws Exception {
        mvc.perform(get("/appointments/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Appointment not found"));
    }
    @Test void invalidIdIsBadRequest() throws Exception {
        mvc.perform(get("/appointments/not-an-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(repository);
    }
    @Test void readsImagesFromQueryWithoutGetBody() throws Exception {
        when(repository.getAllImages("patient@example.invalid")).thenReturn(List.of("image.png"));
        mvc.perform(get("/appointments/images").param("patient_email", "patient@example.invalid"))
                .andExpect(status().isOk()).andExpect(content().json("{\"images\":[\"image.png\"]}"));
    }
    @Test void readsUploadsFromQueryWithoutGetBody() throws Exception {
        when(repository.getAllImages("patient@example.invalid")).thenReturn(List.of("upload.png"));
        mvc.perform(get("/appointments/uploads").param("patient_email", "patient@example.invalid"))
                .andExpect(status().isOk()).andExpect(content().json("{\"uploads\":[\"upload.png\"]}"));
    }
    @Test void missingPatientEmailIsBadRequest() throws Exception {
        for (String path : List.of("/appointments/images", "/appointments/uploads")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.path").value(path));
        }
    }
    @Test void listAcceptsStandardAuthorizationHeader() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        when(service.getAllAppointments("Bearer test-token"))
                .thenReturn(new AppointmentService.AppointmentResult(200, Map.of("appointments", List.of())));
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AppointmentController(service))
                .build().perform(get("/appointments").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(content().json("{\"appointments\":[]}"));
        verify(service).getAllAppointments("Bearer test-token");
    }
    @Configuration
    @ComponentScan("services.web")
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class Config {
        @Bean AppointmentRepository repository() { return mock(AppointmentRepository.class); }
        @Bean AppointmentService service(AppointmentRepository repository) {
            return new AppointmentService(repository, mock(Cloudinary.class));
        }
        @Bean AppointmentController controller(AppointmentService service) { return new AppointmentController(service); }
    }
}
