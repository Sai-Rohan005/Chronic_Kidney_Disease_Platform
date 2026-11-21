package services;

import Database.UserRepository;
import com.ckd.auth.JwtTokenService;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationLookupTest {
    @Test void validatedIdentityLookupsReturnRoleAndSanitizedOwnProfile() {
        var users = mock(UserRepository.class); var tokens = mock(JwtTokenService.class);
        var authentication = new Authentication(users, tokens);
        var identity = new JwtTokenService.AuthenticatedUser("doctor@example.invalid", "Doctor");
        when(tokens.authenticate("fixture-token")).thenReturn(Optional.of(identity));
        when(users.findUser(identity.email())).thenReturn(new Document("email", identity.email()).append("role", "Doctor")
                .append("passwordHash", "not-a-real-hash").append("password", "not-a-real-password"));
        assertEquals(Map.of("email", identity.email(), "role", "Doctor"), authentication.getRoleByToken("Bearer fixture-token").body());
        assertEquals(Map.of("email", identity.email(), "role", "Doctor"), authentication.getUserAndRole("Bearer fixture-token").body());
        var profile = authentication.getUserByToken("Bearer fixture-token");
        assertEquals(200, profile.status());
        assertEquals(Map.of("user", Map.of("email", identity.email(), "role", "Doctor")), profile.body());
    }
    @Test void revokedIdentityCannotReadAnyProfileOrRole() {
        var users = mock(UserRepository.class); var tokens = mock(JwtTokenService.class);
        when(tokens.authenticate("revoked-fixture")).thenReturn(Optional.empty());
        var authentication = new Authentication(users, tokens);
        assertEquals(401, authentication.getUserByToken("Bearer revoked-fixture").status());
        assertEquals(401, authentication.getRoleByToken("Bearer revoked-fixture").status());
        assertEquals(401, authentication.getUserAndRole("Bearer revoked-fixture").status());
        verifyNoInteractions(users);
    }
    @Test void missingBearerHeadersCannotReachTokenOrUserStorage() {
        var users = mock(UserRepository.class); var tokens = mock(JwtTokenService.class);
        var authentication = new Authentication(users, tokens);
        assertEquals(401, authentication.getUserByToken(null).status());
        assertEquals(401, authentication.getRoleByToken("Basic fixture").status());
        assertEquals(401, authentication.getUserAndRole("Bearer ").status());
        assertEquals(401, authentication.logout(null).status());
        verifyNoInteractions(users, tokens);
    }
    @Test void logoutOnlySucceedsWhenTheTokenWasRevoked() {
        var tokens = mock(JwtTokenService.class);
        var authentication = new Authentication(mock(UserRepository.class), tokens);
        when(tokens.revoke("active-fixture")).thenReturn(true);
        assertEquals(200, authentication.logout("Bearer active-fixture").status());
        assertEquals(401, authentication.logout("Bearer missing-fixture").status());
    }
}
