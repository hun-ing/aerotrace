package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import com.huning.aerotrace.auth.application.ProjectApiKeyAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.huning.aerotrace.auth.application.ProjectApiKeyAuthenticationMetrics;

import static com.huning.aerotrace.auth.application.ProjectApiKeyAuthenticationMetrics.AuthenticationResult;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OtlpApiKeyAuthenticationFilter extends OncePerRequestFilter {

  /**
   * 이 Filter가 인증할 OTLP Trace 수신 경로다.
   * <p>
   * 다른 Actuator endpoint나 향후 관리 API에는
   * 이 Filter를 적용하지 않는다.
   */
  private static final String OTLP_TRACES_PATH =
          "/v1/traces";

  /**
   * Authorization 헤더에서 허용할 인증 방식이다.
   * <p>
   * Authorization: Bearer <API Key>
   */
  private static final String BEARER_SCHEME =
          "Bearer";

  /**
   * 인증 실패 응답의 WWW-Authenticate 값이다.
   */
  private static final String BEARER_CHALLENGE =
          "Bearer realm=\"aerotrace\"";

  /**
   * 외부에는 인증 실패 원인을 구분해서 노출하지 않는다.
   * <p>
   * Key 형식 오류, 존재하지 않는 Key, 잘못된 Secret,
   * 만료된 Key, 폐기된 Key 모두 같은 메시지를 사용한다.
   */
  private static final String UNAUTHORIZED_MESSAGE = "Authentication credentials are missing or invalid";

  private final ProjectApiKeyAuthenticationService authenticationService;
  private final ObjectMapper objectMapper;
  private final ProjectApiKeyAuthenticationMetrics authenticationMetrics;

  public OtlpApiKeyAuthenticationFilter(
          ProjectApiKeyAuthenticationService authenticationService,
          ObjectMapper objectMapper,
          ProjectApiKeyAuthenticationMetrics authenticationMetrics
  ) {
    this.authenticationService = authenticationService;
    this.objectMapper = objectMapper;
    this.authenticationMetrics = authenticationMetrics;
  }

  /**
   * POST /v1/traces 요청에만 이 Filter를 적용한다.
   */
  @Override
  protected boolean shouldNotFilter(
          HttpServletRequest request
  ) {
    if (
            !HttpMethod.POST.matches(
                    request.getMethod()
            )
    ) {
      return true;
    }

    String targetPath =
            request.getContextPath()
                    + OTLP_TRACES_PATH;

    return !targetPath.equals(
            request.getRequestURI()
    );
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain
  ) throws ServletException, IOException {
    List<String> authorizationHeaders =
            Collections.list(
                    request.getHeaders(
                            HttpHeaders.AUTHORIZATION
                    )
            );

    if (authorizationHeaders.isEmpty()) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.MISSING_CREDENTIALS
      );

      writeUnauthorizedResponse(response);
      return;
    }

    if (authorizationHeaders.size() != 1) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.INVALID_AUTHORIZATION
      );

      writeUnauthorizedResponse(response);
      return;
    }

    Optional<String> rawKey =
            resolveBearerToken(
                    authorizationHeaders.getFirst()
            );

    if (rawKey.isEmpty()) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.INVALID_AUTHORIZATION
      );

      writeUnauthorizedResponse(response);
      return;
    }

    Optional<AuthenticatedProject>
            authenticatedProject =
            authenticationService.authenticate(
                    rawKey.orElseThrow()
            );

    /*
     * 존재하지 않는 Key, 잘못된 Secret, 만료, 폐기 등을
     * 모두 동일한 401로 처리한다.
     */
    if (authenticatedProject.isEmpty()) {
      writeUnauthorizedResponse(response);
      return;
    }

    /*
     * 인증된 Tenant와 Project 정보를 request에 저장한다.
     *
     * Controller는 임시 UUID 헤더를 읽지 않고
     * 이 객체에서 tenantId와 projectId를 가져간다.
     */
    request.setAttribute(
            OtlpRequestAttributes.AUTHENTICATED_PROJECT,
            authenticatedProject.orElseThrow()
    );

    /*
     * 인증에 성공했을 때만 다음 Filter와 Controller로 진행한다.
     */
    filterChain.doFilter(
            request,
            response
    );
  }

  /**
   * Authorization 헤더에서 Bearer Token을 추출한다.
   */
  private Optional<String> resolveBearerToken(
          String authorization
  ) {
    if (
            authorization == null
                    || authorization.isBlank()
    ) {
      return Optional.empty();
    }

    int separatorIndex =
            authorization.indexOf(' ');

    if (separatorIndex <= 0) {
      return Optional.empty();
    }

    String scheme =
            authorization.substring(
                    0,
                    separatorIndex
            );

    if (
            !BEARER_SCHEME.equalsIgnoreCase(
                    scheme
            )
    ) {
      return Optional.empty();
    }

    String rawKey =
            authorization.substring(
                    separatorIndex + 1
            ).trim();

    if (rawKey.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(rawKey);
  }

  /**
   * 인증 실패 응답을 직접 작성한다.
   * <p>
   * Filter에서 발생한 인증 실패는
   * OtlpExceptionHandler까지 도달하지 않기 때문이다.
   */
  private void writeUnauthorizedResponse(
          HttpServletResponse response
  ) throws IOException {
    response.setStatus(
            HttpStatus.UNAUTHORIZED.value()
    );

    response.setHeader(
            HttpHeaders.WWW_AUTHENTICATE,
            BEARER_CHALLENGE
    );

    response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            "no-store"
    );

    response.setContentType(
            MediaType.APPLICATION_JSON_VALUE
    );

    response.setCharacterEncoding(
            StandardCharsets.UTF_8.name()
    );

    objectMapper.writeValue(
            response.getOutputStream(),
            new OtlpHttpStatusResponse(
                    UNAUTHORIZED_MESSAGE
            )
    );
  }
}