package Controllers;

import Database.*;
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.IndexOptions;
import java.util.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import services.ReportService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReportIdempotencyTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private ReportsRepository repository() {
        var repo = mock(ReportsRepository.class);
        when(repo.getAppointment(ID)).thenReturn(new Document("doctor_email", "d@example.invalid"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("d@example.invalid", "token",
                List.of(new SimpleGrantedAuthority("ROLE_Doctor"))));
        return repo;
    }
    @Test void reportHasAuthenticatedGetByAppointmentRoute() throws Exception {
        var repo = repository();
        when(repo.getReport(ID)).thenReturn(new Document("appointment_id", ID));
        standaloneSetup(new ReportController(new ReportService(repo))).build().perform(get("/reports/" + ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.report.appointment_id").value(ID));
    }
    @Test void concurrentDuplicateInsertReturns409() {
        var repo = repository();
        when(repo.createReport(eq(ID), anyMap())).thenThrow(new MongoWriteException(
                new WriteError(11000, "duplicate", new org.bson.BsonDocument()), new ServerAddress()));
        assertEquals(409, new ReportService(repo).createReport(Map.of("appointment_id", ID, "results", Map.of())).status());
    }
    @Test @SuppressWarnings("unchecked") void appointmentIdIndexIsUnique() {
        var db = mock(DB.class); var database = mock(MongoDatabase.class);
        MongoCollection<Document> reports = mock(MongoCollection.class);
        when(db.getDatabase()).thenReturn(database);
        when(database.getCollection("reports")).thenReturn(reports);
        new ReportsRepository(db);
        var index = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(reports).createIndex(index.capture(), options.capture());
        assertTrue(options.getValue().isUnique());
        assertEquals(new org.bson.BsonDocument("appointment_id", new org.bson.BsonInt32(1)), index.getValue().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()));
    }
}
