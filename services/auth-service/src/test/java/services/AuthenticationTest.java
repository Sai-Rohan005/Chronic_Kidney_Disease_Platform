package services;

import Database.UserRepository;
import com.ckd.auth.JwtTokenService;
import com.mongodb.*;
import java.time.Instant;
import java.util.*;
import org.bson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mindrot.jbcrypt.BCrypt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationTest {
    private UserRepository users;
    private JwtTokenService tokens;
    private Authentication authentication;
    private static final String EMAIL = "patient@example.invalid";
    private static final String PASSWORD = "a-valid-test-password";
    private static final String HASH = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));
    @BeforeEach void setUp() {
        users = mock(UserRepository.class); tokens = mock(JwtTokenService.class);
        authentication = new Authentication(users, tokens);
    }
    private Map<String, Object> credentials(String email, String password) { return Map.of("email", email, "password", password); }
    private Map<String, Object> registration() { return Map.of("name", "Patient", "email", EMAIL, "password", PASSWORD); }
    private Document user(String hash) { return new Document("name", "Patient").append("email", EMAIL).append("passwordHash", hash).append("role", "Patient"); }

    @ParameterizedTest @ValueSource(strings = {"", "not-an-email", "a@", "@example.invalid", "a b@example.invalid"})
    void loginRejectsBadEmailBeforeDatabaseAccess(String email) {
        assertEquals(400, authentication.login(credentials(email, PASSWORD)).status());
        verifyNoInteractions(users, tokens);
    }
    @Test void loginRequiresPassword() {
        assertEquals(400, authentication.login(Map.of("email", EMAIL)).status());
        verifyNoInteractions(users, tokens);
    }
    @Test void unknownUserAndWrongPasswordHaveSameResponse() {
        var missing = authentication.login(credentials(EMAIL, PASSWORD));
        when(users.findUser(EMAIL)).thenReturn(user(HASH));
        var wrong = authentication.login(credentials(EMAIL, "incorrect-password"));
        assertEquals(401, missing.status()); assertEquals(missing, wrong);
        verifyNoInteractions(tokens);
    }
    @ParameterizedTest @ValueSource(strings = {"", "malformed-hash"})
    void invalidStoredHashCannotAuthenticate(String hash) {
        when(users.findUser(EMAIL)).thenReturn(user(hash));
        assertEquals(401, authentication.login(credentials(EMAIL, PASSWORD)).status());
        verifyNoInteractions(tokens);
    }
    @Test void missingStoredHashCannotAuthenticate() {
        when(users.findUser(EMAIL)).thenReturn(user(null));
        assertEquals(401, authentication.login(credentials(EMAIL, PASSWORD)).status());
        verifyNoInteractions(tokens);
    }
    @Test void loginNormalizesEmailIssuesTokenAndNeverReturnsPasswordHash() {
        when(users.findUser(EMAIL)).thenReturn(user(HASH));
        Instant expiry = Instant.now().plusSeconds(3600);
        when(tokens.issue(EMAIL, "Patient")).thenReturn(new JwtTokenService.IssuedToken("test-fixture-token", expiry));
        var result = authentication.login(credentials(" Patient@Example.Invalid ", PASSWORD));
        assertEquals(200, result.status()); assertEquals("test-fixture-token", result.body().get("token"));
        assertEquals(expiry.toString(), result.body().get("expiresAt"));
        assertEquals(Map.of("name", "Patient", "email", EMAIL, "role", "Patient"), result.body().get("user"));
        verify(tokens).issue(EMAIL, "Patient");
    }
    @Test void loginCannotReportSuccessWhenTokenStorageFails() {
        when(users.findUser(EMAIL)).thenReturn(user(HASH));
        when(tokens.issue(EMAIL, "Patient")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test outage"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> authentication.login(credentials(EMAIL, PASSWORD)));
    }
    @ParameterizedTest @ValueSource(strings = {"", "invalid", "a@", "a b@example.invalid"})
    void registerRejectsBadEmailWithoutWriting(String email) {
        var body = new HashMap<>(registration()); body.put("email", email);
        assertEquals(400, authentication.register(body).status()); verifyNoInteractions(users, tokens);
    }
    @Test void registerRequiresNameAndPassword() {
        for (String field : List.of("name", "password")) {
            var body = new HashMap<>(registration()); body.remove(field);
            assertEquals(400, authentication.register(body).status());
        }
        verifyNoInteractions(users, tokens);
    }
    @Test void registerExistingEmailIsConflictWithoutCreatingAnotherUser() {
        when(users.findUser(EMAIL)).thenReturn(user(HASH));
        assertEquals(409, authentication.register(registration()).status());
        verify(users, never()).createUser(anyString(), anyString(), anyString()); verifyNoInteractions(tokens);
    }
    @Test void concurrentDuplicateRegistrationIsConflict() {
        doThrow(new MongoWriteException(new WriteError(11000, "duplicate fixture", new BsonDocument()), new ServerAddress()))
                .when(users).createUser("Patient", EMAIL, PASSWORD);
        assertEquals(409, authentication.register(registration()).status()); verifyNoInteractions(tokens);
    }
    @Test void unrelatedDatabaseWriteFailureIsNotTreatedAsADuplicate() {
        var failure = new MongoWriteException(new WriteError(121, "validation fixture", new BsonDocument()), new ServerAddress());
        doThrow(failure).when(users).createUser("Patient", EMAIL, PASSWORD);
        assertSame(failure, assertThrows(MongoWriteException.class, () -> authentication.register(registration())));
    }
    @Test void registrationNormalizesInputWithoutIssuingALoginToken() {
        var result = authentication.register(Map.of("name", " Patient ", "email", " Patient@Example.Invalid ", "password", PASSWORD, "role", "Doctor"));
        assertEquals(201, result.status()); verify(users).createUser("Patient", EMAIL, PASSWORD); verifyNoInteractions(tokens);
        assertFalse(result.body().containsKey("token"));
    }
}
