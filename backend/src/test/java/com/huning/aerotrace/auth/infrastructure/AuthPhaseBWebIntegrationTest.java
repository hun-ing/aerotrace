package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.api.OnboardingIntentController;
import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.OnboardingInviteService;
import com.huning.aerotrace.auth.application.TenantRole;
import com.huning.aerotrace.auth.application.UserSessionRevocationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "aerotrace.auth.enabled=true",
                "aerotrace.auth.allow-insecure-local=true",
                "aerotrace.auth.secure-cookie=false",
                "aerotrace.auth.cookie-name=aerotrace_session",
                "aerotrace.auth.public-origin=http://127.0.0.1:8080",
                "aerotrace.auth.github.client-id=phase-b-test-client",
                "aerotrace.auth.github.client-secret=phase-b-test-secret",
                "server.servlet.session.cookie.name=aerotrace_session",
                "server.servlet.session.cookie.secure=false"
        }
)
@AutoConfigureMockMvc
@Import(AuthPhaseBWebIntegrationTest.StubOAuthConfiguration.class)
class AuthPhaseBWebIntegrationTest {

  private static final String ORIGIN =
          "http://127.0.0.1:8080";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private OnboardingInviteService inviteService;

  @Autowired
  private UserSessionRevocationService sessionRevocationService;

  private final List<UUID> tenantIds = new ArrayList<>();
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void clearSessions() {
    jdbcTemplate.update(
            "DELETE FROM aerotrace_session_attributes"
    );
    jdbcTemplate.update("DELETE FROM aerotrace_session");
    deleteAnonymousLoginFailureAudits();
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update(
            "DELETE FROM aerotrace_session_attributes"
    );
    jdbcTemplate.update("DELETE FROM aerotrace_session");
    deleteAnonymousLoginFailureAudits();

    for (UUID tenantId : tenantIds) {
      jdbcTemplate.update(
              "DELETE FROM security_audit_events WHERE tenant_id = ?",
              tenantId
      );
      jdbcTemplate.update(
              "DELETE FROM tenants WHERE id = ?",
              tenantId
      );
    }

    for (UUID userId : userIds) {
      jdbcTemplate.update(
              "DELETE FROM security_audit_events WHERE actor_user_id = ?",
              userId
      );
      jdbcTemplate.update(
              "DELETE FROM app_users WHERE id = ?",
              userId
      );
    }
  }

  @Test
  void csrfEndpointCreatesNoStoreJdbcSession() throws Exception {
    MvcResult result = mockMvc.perform(
                    get("/api/v1/auth/csrf")
            )
            .andExpect(status().isOk())
            .andExpect(
                    header().string(
                            "Cache-Control",
                            org.hamcrest.Matchers.containsString(
                                    "no-store"
                            )
                    )
            )
            .andExpect(
                    jsonPath("$.headerName")
                            .value("X-CSRF-TOKEN")
            )
            .andExpect(
                    jsonPath("$.token")
                            .isNotEmpty()
            )
            .andReturn();

    String setCookie = result.getResponse()
            .getHeader("Set-Cookie");

    assertThat(setCookie)
            .contains("aerotrace_session=")
            .contains("Path=/")
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .doesNotContain("Secure");

    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT max_inactive_interval
                    FROM aerotrace_session
                    """,
                    Integer.class
            )
    ).isEqualTo(8 * 60 * 60);
  }

  @Test
  void onboardingStoresOnlyInviteRowIdInAnonymousSession()
          throws Exception {
    UUID tenantId = insertTenant();
    OnboardingInviteService.IssuedOnboardingInvite invite =
            inviteService.issueBootstrap(
                    tenantId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );

    MvcResult result = mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .header("Origin", ORIGIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                    "{\"invite\":\""
                                            + invite.rawToken()
                                            + "\"}"
                            )
            )
            .andExpect(status().isNoContent())
            .andExpect(
                    header().string(
                            "Cache-Control",
                            org.hamcrest.Matchers.containsString(
                                    "no-store"
                            )
                    )
            )
            .andReturn();

    byte[] storedIntent = jdbcTemplate.queryForObject(
            """
            SELECT attribute_bytes
            FROM aerotrace_session_attributes
            WHERE attribute_name = ?
            """,
            byte[].class,
            OnboardingIntentController.SESSION_ATTRIBUTE
    );

    try (
            ObjectInputStream input = new ObjectInputStream(
                    new ByteArrayInputStream(storedIntent)
            )
    ) {
      assertThat(input.readObject()).isEqualTo(invite.id());
    }

    assertThat(
            countSessionAttributesContaining(
                    invite.rawToken()
            )
    ).isZero();
  }

  @Test
  void onboardingRequiresBothCsrfAndExactOrigin()
          throws Exception {
    mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .header("Origin", ORIGIN)
                            .contentType("application/json")
                            .content("{\"invite\":\"invalid\"}")
            )
            .andExpect(status().isForbidden());

    mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"invite\":\"invalid\"}")
            )
            .andExpect(status().isForbidden());

    mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .header(
                                    "Origin",
                                    "http://attacker.example"
                            )
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"invite\":\"invalid\"}")
            )
            .andExpect(status().isForbidden());
  }

  @Test
  void malformedOnboardingBodyUsesGenericAuthError()
          throws Exception {
    mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .header("Origin", ORIGIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{not-json")
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                    jsonPath("$.message")
                            .value(
                                    "Request is invalid or unavailable"
                            )
            )
            .andExpect(
                    jsonPath("$.message")
                            .value(
                                    org.hamcrest.Matchers.not(
                                            org.hamcrest.Matchers
                                                    .containsString(
                                                            "OTLP"
                                                    )
                                    )
                            )
            );
  }

  @Test
  void malformedRetryCannotReuseAnOlderOnboardingIntent()
          throws Exception {
    UUID tenantId = insertTenant();
    OnboardingInviteService.IssuedOnboardingInvite invite =
            inviteService.issueBootstrap(
                    tenantId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );

    MvcResult validIntent = mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .header("Origin", ORIGIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                    "{\"invite\":\""
                                            + invite.rawToken()
                                            + "\"}"
                            )
            )
            .andExpect(status().isNoContent())
            .andReturn();

    Cookie sessionCookie = validIntent.getResponse()
            .getCookie("aerotrace_session");

    mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .cookie(sessionCookie)
                            .header("Origin", ORIGIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{not-json")
            )
            .andExpect(status().isBadRequest());

    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM aerotrace_session_attributes
                    WHERE attribute_name = ?
                    """,
                    Integer.class,
                    OnboardingIntentController.SESSION_ATTRIBUTE
            )
    ).isZero();
  }

  @Test
  void oauthAuthorizationRedirectContainsStatePkceAndExactCallback()
          throws Exception {
    MvcResult result = mockMvc.perform(
                    get("/oauth2/authorization/github")
            )
            .andExpect(status().is3xxRedirection())
            .andReturn();

    URI location = URI.create(
            result.getResponse().getHeader("Location")
    );
    Map<String, String> query = queryParameters(
            location.getRawQuery()
    );

    assertThat(location.getScheme()).isEqualTo("https");
    assertThat(location.getHost()).isEqualTo("github.com");
    assertThat(location.getPath())
            .isEqualTo("/login/oauth/authorize");

    assertThat(query.get("client_id"))
            .isEqualTo("phase-b-test-client");
    assertThat(query.get("redirect_uri"))
            .isEqualTo(
                    ORIGIN + "/login/oauth2/code/github"
            );
    assertThat(query.get("scope")).isEqualTo("read:user");
    assertThat(query.get("state")).isNotBlank();
    assertThat(query.get("code_challenge")).isNotBlank();
    assertThat(query.get("code_challenge_method"))
            .isEqualTo("S256");
  }

  @Test
  void callbackWithoutMatchingStateFailsWithoutProviderCall()
          throws Exception {
    mockMvc.perform(
                    get("/login/oauth2/code/github")
                            .queryParam("code", "not-exchanged")
                            .queryParam("state", "not-matched")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                    header().string(
                            "Cache-Control",
                            "no-store"
                    )
            )
            .andExpect(
                    jsonPath("$.message")
                            .value("Authentication failed")
            );

    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM security_audit_events
                    WHERE action = 'OAUTH_LOGIN_FAILED'
                    """,
                    Integer.class
            )
    ).isZero();
  }

  @Test
  void trackedProviderFailureIsAuditedAndSessionIsInvalidated()
          throws Exception {
    MvcResult authorization = mockMvc.perform(
                    get("/oauth2/authorization/github")
            )
            .andExpect(status().is3xxRedirection())
            .andReturn();

    Cookie anonymousCookie = authorization.getResponse()
            .getCookie("aerotrace_session");
    Map<String, String> authorizationQuery =
            queryParameters(
                    URI.create(
                            authorization.getResponse()
                                    .getHeader("Location")
                    ).getRawQuery()
            );

    mockMvc.perform(
                    get("/login/oauth2/code/github")
                            .cookie(anonymousCookie)
                            .queryParam("code", "stub-failure")
                            .queryParam(
                                    "state",
                                    authorizationQuery.get("state")
                            )
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                    jsonPath("$.message")
                            .value("Authentication failed")
            );

    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM security_audit_events
                    WHERE action = 'OAUTH_LOGIN_FAILED'
                      AND result = 'DENIED'
                    """,
                    Integer.class
            )
    ).isEqualTo(1);

    mockMvc.perform(
                    get("/api/v1/me")
                            .cookie(anonymousCookie)
            )
            .andExpect(status().isUnauthorized());
  }

  @Test
  void successfulCallbackRotatesSessionAndPersistsOnlyLocalPrincipal()
          throws Exception {
    UUID tenantId = insertTenant();
    OnboardingInviteService.IssuedOnboardingInvite invite =
            inviteService.issueBootstrap(
                    tenantId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );

    MvcResult onboarding = mockMvc.perform(
                    post("/api/v1/onboarding/intents")
                            .header("Origin", ORIGIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                    "{\"invite\":\""
                                            + invite.rawToken()
                                            + "\"}"
                            )
            )
            .andExpect(status().isNoContent())
            .andReturn();

    Cookie anonymousCookie = onboarding.getResponse()
            .getCookie("aerotrace_session");

    assertThat(anonymousCookie).isNotNull();

    MvcResult authorization = mockMvc.perform(
                    get("/oauth2/authorization/github")
                            .cookie(anonymousCookie)
            )
            .andExpect(status().is3xxRedirection())
            .andReturn();

    Map<String, String> authorizationQuery =
            queryParameters(
                    URI.create(
                            authorization.getResponse()
                                    .getHeader("Location")
                    ).getRawQuery()
            );

    MvcResult callback = mockMvc.perform(
                    get("/login/oauth2/code/github")
                            .cookie(anonymousCookie)
                            .queryParam("code", "stub-code")
                            .queryParam(
                                    "state",
                                    authorizationQuery.get("state")
                            )
            )
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/"))
            .andExpect(
                    header().string(
                            "Cache-Control",
                            "no-store"
                    )
            )
            .andReturn();

    Cookie authenticatedCookie = callback.getResponse()
            .getCookie("aerotrace_session");

    assertThat(authenticatedCookie).isNotNull();
    assertThat(authenticatedCookie.getValue())
            .isNotEqualTo(anonymousCookie.getValue());

    UUID userId = jdbcTemplate.queryForObject(
            """
            SELECT user_id
            FROM user_identities
            WHERE provider = 'GITHUB'
              AND provider_subject = '987654321'
            """,
            UUID.class
    );
    userIds.add(userId);

    mockMvc.perform(
                    get("/api/v1/me")
                            .cookie(authenticatedCookie)
            )
            .andExpect(status().isOk())
            .andExpect(
                    jsonPath("$.userId")
                            .value(userId.toString())
            );

    mockMvc.perform(
                    get("/api/v1/me")
                            .cookie(anonymousCookie)
            )
            .andExpect(status().isUnauthorized());

    assertThat(
            countSessionAttributesContaining(
                    "test-token-not-persisted"
            )
    ).isZero();
    assertThat(
            countSessionAttributesContaining(
                    invite.rawToken()
            )
    ).isZero();
    assertThat(
            countSessionAttributesContaining(
                    "DefaultOAuth2User"
            )
    ).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM aerotrace_session
                    WHERE principal_name = ?
                    """,
                    Integer.class,
                    userId.toString()
            )
    ).isEqualTo(1);
  }

  @Test
  void currentUserReadsActiveMembershipAndDisabledUserIsRejected()
          throws Exception {
    UUID tenantId = insertTenant();
    UUID userId = insertUser("Phase B Web User");
    insertMembership(tenantId, userId, TenantRole.VIEWER);

    UsernamePasswordAuthenticationToken authentication =
            localAuthentication(userId);

    mockMvc.perform(
                    get("/api/v1/me")
                            .with(authentication(authentication))
            )
            .andExpect(status().isOk())
            .andExpect(
                    header().string(
                            "Cache-Control",
                            org.hamcrest.Matchers.containsString(
                                    "no-store"
                            )
                    )
            )
            .andExpect(
                    jsonPath("$.userId")
                            .value(userId.toString())
            )
            .andExpect(
                    jsonPath("$.memberships[0].tenantId")
                            .value(tenantId.toString())
            )
            .andExpect(
                    jsonPath("$.memberships[0].role")
                            .value("VIEWER")
            );

    jdbcTemplate.update(
            "UPDATE app_users SET status = 'DISABLED' WHERE id = ?",
            userId
    );

    mockMvc.perform(
                    get("/api/v1/me")
                            .with(authentication(authentication))
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                    header().string(
                            "Cache-Control",
                            "no-store"
                    )
            );
  }

  @Test
  void logoutIsPostOnlyCsrfAndOriginProtected() throws Exception {
    UUID userId = insertUser("Phase B Logout User");
    UsernamePasswordAuthenticationToken authentication =
            localAuthentication(userId);

    mockMvc.perform(
                    post("/api/v1/logout")
                            .header("Origin", ORIGIN)
                            .with(authentication(authentication))
                            .with(csrf())
            )
            .andExpect(status().isNoContent())
            .andExpect(
                    header().string(
                            "Cache-Control",
                            "no-store"
                    )
            );

    mockMvc.perform(
                    get("/api/v1/logout")
                            .with(authentication(authentication))
            )
            .andExpect(status().is4xxClientError());
  }

  @Test
  void disabledUserCanStillInvalidateOwnSession()
          throws Exception {
    UUID userId = insertUser("Phase B Disabled Logout User");
    UsernamePasswordAuthenticationToken authentication =
            localAuthentication(userId);

    jdbcTemplate.update(
            "UPDATE app_users SET status = 'DISABLED' WHERE id = ?",
            userId
    );

    mockMvc.perform(
                    post("/api/v1/logout")
                            .header("Origin", ORIGIN)
                            .with(authentication(authentication))
                            .with(csrf())
            )
            .andExpect(status().isNoContent());
  }

  @Test
  void unknownRouteIsDeniedAndLegacyApiKeyFilterStillApplies()
          throws Exception {
    mockMvc.perform(get("/api/v1/private-unknown"))
            .andExpect(status().is4xxClientError());

    mockMvc.perform(get("/api/v1/traces"))
            .andExpect(status().isUnauthorized());
  }

  @Test
  void userAndGlobalSessionRevocationDeleteJdbcState() {
    UUID firstUserId = UUID.randomUUID();
    UUID secondUserId = UUID.randomUUID();

    insertSession(firstUserId);
    insertSession(firstUserId);
    insertSession(secondUserId);

    assertThat(
            sessionRevocationService.revokeUserSessions(
                    firstUserId
            )
    ).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM aerotrace_session",
                    Integer.class
            )
    ).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM aerotrace_session_attributes
                    """,
                    Integer.class
            )
    ).isEqualTo(1);

    assertThat(
            sessionRevocationService.revokeAllSessions()
    ).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM aerotrace_session",
                    Integer.class
            )
    ).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM aerotrace_session_attributes
                    """,
                    Integer.class
            )
    ).isZero();
  }

  private UUID insertTenant() {
    UUID tenantId = UUID.randomUUID();
    tenantIds.add(tenantId);

    jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug)
            VALUES (?, ?, ?)
            """,
            tenantId,
            "Phase B Web Tenant " + tenantId,
            "phase-b-web-" + tenantId
    );

    return tenantId;
  }

  private UUID insertUser(String displayName) {
    UUID userId = UUID.randomUUID();
    userIds.add(userId);

    jdbcTemplate.update(
            """
            INSERT INTO app_users (id, display_name, status)
            VALUES (?, ?, 'ACTIVE')
            """,
            userId,
            displayName
    );

    return userId;
  }

  private void insertMembership(
          UUID tenantId,
          UUID userId,
          TenantRole role
  ) {
    OffsetDateTime now = OffsetDateTime.now(
            ZoneOffset.UTC
    );

    jdbcTemplate.update(
            """
            INSERT INTO tenant_memberships (
                tenant_id,
                user_id,
                role,
                status,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, 'ACTIVE', ?, ?)
            """,
            tenantId,
            userId,
            role.name(),
            now,
            now
    );
  }

  private UsernamePasswordAuthenticationToken localAuthentication(
          UUID userId
  ) {
    return new UsernamePasswordAuthenticationToken(
            new AeroTracePrincipal(
                    userId,
                    Instant.now()
            ),
            null,
            List.of()
    );
  }

  private void insertSession(UUID userId) {
    String primaryId = UUID.randomUUID().toString();
    String sessionId = UUID.randomUUID().toString();
    long now = Instant.now().toEpochMilli();

    jdbcTemplate.update(
            """
            INSERT INTO aerotrace_session (
                primary_id,
                session_id,
                creation_time,
                last_access_time,
                max_inactive_interval,
                expiry_time,
                principal_name
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            primaryId,
            sessionId,
            now,
            now,
            8 * 60 * 60,
            now + (8 * 60 * 60 * 1000L),
            userId.toString()
    );

    jdbcTemplate.update(
            """
            INSERT INTO aerotrace_session_attributes (
                session_primary_id,
                attribute_name,
                attribute_bytes
            )
            VALUES (?, 'test', ?)
            """,
            primaryId,
            new byte[]{1}
    );
  }

  private int countSessionAttributesContaining(String value) {
    Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM aerotrace_session_attributes
            WHERE encode(attribute_bytes, 'escape') LIKE ?
            """,
            Integer.class,
            "%" + value + "%"
    );

    return count == null ? 0 : count;
  }

  private Map<String, String> queryParameters(
          String rawQuery
  ) {
    Map<String, String> result = new LinkedHashMap<>();

    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
              decode(parts[0]),
              parts.length == 2 ? decode(parts[1]) : ""
      );
    }

    return result;
  }

  private String decode(String value) {
    return URLDecoder.decode(
            value,
            StandardCharsets.UTF_8
    );
  }

  private void deleteAnonymousLoginFailureAudits() {
    jdbcTemplate.update(
            """
            DELETE FROM security_audit_events
            WHERE action = 'OAUTH_LOGIN_FAILED'
              AND actor_user_id IS NULL
              AND tenant_id IS NULL
              AND project_id IS NULL
            """
    );
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class StubOAuthConfiguration {

    @Bean
    OAuth2AccessTokenResponseClient<
            OAuth2AuthorizationCodeGrantRequest>
            stubAccessTokenResponseClient() {
      return request -> {
        Object codeVerifier = request
                .getAuthorizationExchange()
                .getAuthorizationRequest()
                .getAttribute("code_verifier");

        if (codeVerifier == null) {
          throw new IllegalStateException(
                  "PKCE code verifier was not retained"
          );
        }

        String authorizationCode = request
                .getAuthorizationExchange()
                .getAuthorizationResponse()
                .getCode();

        if ("stub-failure".equals(authorizationCode)) {
          throw new OAuth2AuthorizationException(
                  new OAuth2Error(
                          "temporarily_unavailable"
                  )
          );
        }

        return OAuth2AccessTokenResponse
                .withToken("test-token-not-persisted")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(300)
                .scopes(java.util.Set.of("read:user"))
                .build();
      };
    }

    @Bean
    OAuth2UserService<OAuth2UserRequest, OAuth2User>
            stubOauth2UserService() {
      return request -> new DefaultOAuth2User(
              List.of(
                      new SimpleGrantedAuthority(
                              "OAUTH2_USER"
                      )
              ),
              Map.of(
                      "id",
                      987654321L,
                      "login",
                      "phase-b-oauth-user",
                      "name",
                      "Phase B OAuth User",
                      "avatar_url",
                      "https://avatars.githubusercontent.com/u/987654321"
              ),
              "id"
      );
    }
  }
}
