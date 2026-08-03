package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AuthenticatedProjectRequestResolver {

  public AuthenticatedProject resolve(
          HttpServletRequest request
  ) {
    Objects.requireNonNull(
            request,
            "request must not be null"
    );

    Object attribute =
            request.getAttribute(
                    OtlpRequestAttributes
                            .AUTHENTICATED_PROJECT
            );

    if (
            attribute
                    instanceof AuthenticatedProject
                    authenticatedProject
    ) {
      return authenticatedProject;
    }

    /*
     * 정상 요청에서는 인증 Filter가 먼저 실행되어
     * 반드시 AuthenticatedProject를 넣어야 한다.
     *
     * 이 예외가 발생하면 사용자 인증 실패가 아니라
     * Filter 경로 또는 순서 설정 오류다.
     */
    throw new IllegalStateException(
            "Authenticated project is missing "
                    + "from the request"
    );
  }
}