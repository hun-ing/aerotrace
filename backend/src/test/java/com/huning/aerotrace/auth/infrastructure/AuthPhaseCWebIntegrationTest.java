package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "aerotrace.auth.enabled=true", "aerotrace.auth.allow-insecure-local=true",
        "aerotrace.auth.secure-cookie=false", "aerotrace.auth.cookie-name=aerotrace_session",
        "aerotrace.auth.public-origin=http://127.0.0.1:8080",
        "aerotrace.auth.github.client-id=phase-c-test", "aerotrace.auth.github.client-secret=phase-c-test-secret"
})
@AutoConfigureMockMvc
@Transactional
class AuthPhaseCWebIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  private UUID user, tenant, otherTenant, project, sibling, foreignProject;
  private static final String TRACE = "11111111111111111111111111111111";
  private static final String FROM = "2026-09-01T00:00:00Z";
  private static final String TO = "2026-09-02T00:00:00Z";

  @BeforeEach
  void fixture() {
    user = UUID.randomUUID(); tenant = UUID.randomUUID(); otherTenant = UUID.randomUUID();
    project = UUID.randomUUID(); sibling = UUID.randomUUID(); foreignProject = UUID.randomUUID();
    jdbc.update("INSERT INTO app_users(id, display_name, status) VALUES (?, 'Phase C user', 'ACTIVE')", user);
    for (UUID id : List.of(tenant, otherTenant))
      jdbc.update("INSERT INTO tenants(id, name, slug) VALUES (?, 'Phase C tenant', ?)", id, "phase-c-" + id);
    insertProject(project, tenant, "Visible project");
    insertProject(sibling, tenant, "Sibling project");
    insertProject(foreignProject, otherTenant, "Hidden project");
    jdbc.update("INSERT INTO tenant_memberships(tenant_id, user_id, role, status) VALUES (?, ?, 'VIEWER', 'ACTIVE')", tenant, user);
    insertSpan(project, tenant, TRACE, "visible-service");
    insertSpan(project, tenant, "22222222222222222222222222222222", "visible-service");
    // Same trace and span IDs in different projects must never cross the read boundary.
    insertSpan(sibling, tenant, TRACE, "sibling-service");
    insertSpan(foreignProject, otherTenant, TRACE, "hidden-service");
  }

  @Test
  void anonymousAndWorkloadCredentialsCannotUseSessionApis() throws Exception {
    for (String path : paths()) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).header("Authorization", "Bearer atr_not-a-user-session"))
              .andExpect(status().isUnauthorized());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"OWNER", "ADMIN", "VIEWER"})
  void everyReadRoleSeesOnlyItsTenantAndProjects(String role) throws Exception {
    jdbc.update("UPDATE tenant_memberships SET role = ? WHERE user_id = ?", role, user);
    mvc.perform(get("/api/v1/tenants").with(session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].tenantId").value(tenant.toString()))
            .andExpect(jsonPath("$[0].role").value(role));
    mvc.perform(get("/api/v1/tenants/" + tenant + "/projects").with(session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    mvc.perform(get("/api/v1/projects/" + project).with(session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(tenant.toString()))
            .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void hiddenAndMissingResourcesHaveTheSameResponse() throws Exception {
    for (UUID id : List.of(foreignProject, UUID.randomUUID())) {
      mvc.perform(get("/api/v1/projects/" + id).with(session()))
              .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource is unavailable"));
      mvc.perform(get("/api/v1/projects/" + id + "/traces").param("from", FROM).param("to", TO).with(session()))
              .andExpect(status().isNotFound());
      mvc.perform(get("/api/v1/projects/" + id + "/traces/" + TRACE).with(session()))
              .andExpect(status().isNotFound());
    }
    mvc.perform(get("/api/v1/tenants/" + otherTenant + "/projects").with(session()))
            .andExpect(status().isNotFound());
  }

  @Test
  void traceListDetailAndCursorAreBoundToAuthorizedProject() throws Exception {
    String json = mvc.perform(get("/api/v1/projects/" + project + "/traces").with(session())
                    .param("from", FROM).param("to", TO).param("limit", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
    JsonNode response = JsonMapper.builder().build().readTree(json);
    String cursor = response.get("nextCursor").asString();
    mvc.perform(get("/api/v1/projects/" + sibling + "/traces").with(session())
                    .param("from", FROM).param("to", TO).param("limit", "1").param("cursor", cursor))
            .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/projects/" + project + "/traces/" + TRACE).with(session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.spanCount").value(1))
            .andExpect(jsonPath("$.spans[0].serviceName").value("visible-service"));
    mvc.perform(get("/api/v1/projects/" + sibling + "/traces/" + TRACE).with(session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.spans[0].serviceName").value("sibling-service"));
  }

  @Test
  void revokedMembershipTakesEffectOnTheNextRequest() throws Exception {
    RequestPostProcessor sameSession = session();
    mvc.perform(get("/api/v1/projects/" + project).with(sameSession)).andExpect(status().isOk());
    jdbc.update("UPDATE tenant_memberships SET status='REVOKED', revoked_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE user_id=?", user);
    mvc.perform(get("/api/v1/projects/" + project).with(sameSession)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/tenants").with(sameSession)).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void disabledUserAndAbsoluteExpiryApplyToEverySessionReadPath() throws Exception {
    for (String path : paths()) {
      mvc.perform(get(path).with(authenticatedAt(Instant.now().minusSeconds(8 * 86400L))))
              .andExpect(status().isUnauthorized());
    }
    jdbc.update("UPDATE app_users SET status='DISABLED' WHERE id=?", user);
    for (String path : paths()) mvc.perform(get(path).with(session()))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void sessionDoesNotReplaceApiKeyAndUnknownWritesRemainDenied() throws Exception {
    mvc.perform(get("/api/v1/traces").param("from", FROM).param("to", TO).with(session()))
            .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/projects/" + project).with(session()).with(csrf()))
            .andExpect(status().isForbidden());
  }

  @Test
  void invalidQueryAndMalformedIdsFailWithoutData() throws Exception {
    mvc.perform(get("/api/v1/projects/" + project + "/traces").with(session())
                    .param("from", FROM).param("to", TO).param("errorOnly", "maybe"))
            .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/projects/not-a-uuid").with(session())).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/projects/" + project + "/traces").with(session()))
            .andExpect(status().isBadRequest());
  }

  private List<String> paths() {
    return List.of("/api/v1/me", "/api/v1/tenants", "/api/v1/tenants/" + tenant + "/projects",
            "/api/v1/projects/" + project, "/api/v1/projects/" + project + "/traces",
            "/api/v1/projects/" + project + "/traces/" + TRACE);
  }

  private RequestPostProcessor session() { return authenticatedAt(Instant.now()); }
  private RequestPostProcessor authenticatedAt(Instant at) {
    return authentication(new UsernamePasswordAuthenticationToken(new AeroTracePrincipal(user, at), null, List.of()));
  }
  private void insertProject(UUID id, UUID tenantId, String name) {
    jdbc.update("INSERT INTO projects(id, tenant_id, name, slug) VALUES (?, ?, ?, ?)", id, tenantId, name, "project-" + id);
  }
  private void insertSpan(UUID projectId, UUID tenantId, String traceId, String service) {
    jdbc.update("""
            INSERT INTO spans(tenant_id, project_id, trace_id, span_id, service_name, name, span_kind,
                              start_time, end_time, duration_nano)
            VALUES (?, ?, ?, '1111111111111111', ?, 'request', 2,
                    '2026-09-01T12:00:00Z', '2026-09-01T12:00:01Z', 1000000000)
            """, tenantId, projectId, traceId, service);
  }
}
