package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceDetailQueryServiceTest {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final String TRACE_ID =
          "dddddddddddddddddddddddddddddddd";

  @Mock
  private JdbcTraceDetailQueryRepository repository;

  @Mock
  private AuthenticatedProject authenticatedProject;

  private TraceDetailQueryService service;

  @BeforeEach
  void setUp() {
    service =
            new TraceDetailQueryService(repository);

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);
  }

  @Test
  void queriesUsingAuthenticatedTenantAndProject() {
    TraceSpanDetail expectedSpan =
            createSpan(
                    "1111111111111111",
                    null
            );

    when(
            repository.findTraceSpans(
                    TENANT_ID,
                    PROJECT_ID,
                    TRACE_ID,
                    JdbcTraceDetailQueryRepository
                            .QUERY_LIMIT_WITH_OVERFLOW_DETECTION
            )
    ).thenReturn(
            List.of(expectedSpan)
    );

    List<TraceSpanDetail> result =
            service.findTraceSpans(
                    authenticatedProject,
                    TRACE_ID
            );

    assertThat(result)
            .containsExactly(expectedSpan);

    verify(repository).findTraceSpans(
            TENANT_ID,
            PROJECT_ID,
            TRACE_ID,
            JdbcTraceDetailQueryRepository
                    .QUERY_LIMIT_WITH_OVERFLOW_DETECTION
    );
  }

  @Test
  void throwsNotFoundWhenProjectHasNoMatchingSpan() {
    when(
            repository.findTraceSpans(
                    TENANT_ID,
                    PROJECT_ID,
                    TRACE_ID,
                    JdbcTraceDetailQueryRepository
                            .QUERY_LIMIT_WITH_OVERFLOW_DETECTION
            )
    ).thenReturn(List.of());

    assertThatThrownBy(
            () -> service.findTraceSpans(
                    authenticatedProject,
                    TRACE_ID
            )
    )
            .isInstanceOf(
                    TraceNotFoundException.class
            )
            .hasMessage(
                    "Trace was not found"
            );
  }

  @Test
  void rejectsTraceContainingMoreThanFiveThousandSpans() {
    TraceSpanDetail span =
            createSpan(
                    "1111111111111111",
                    null
            );

    List<TraceSpanDetail> overflowResult =
            Collections.nCopies(
                    JdbcTraceDetailQueryRepository
                            .QUERY_LIMIT_WITH_OVERFLOW_DETECTION,
                    span
            );

    when(
            repository.findTraceSpans(
                    TENANT_ID,
                    PROJECT_ID,
                    TRACE_ID,
                    JdbcTraceDetailQueryRepository
                            .QUERY_LIMIT_WITH_OVERFLOW_DETECTION
            )
    ).thenReturn(overflowResult);

    assertThatThrownBy(
            () -> service.findTraceSpans(
                    authenticatedProject,
                    TRACE_ID
            )
    )
            .isInstanceOf(
                    TraceSpanLimitExceededException.class
            )
            .hasMessage(
                    "Trace contains more than 5000 spans"
            );
  }

  private static TraceSpanDetail createSpan(
          String spanId,
          String parentSpanId
  ) {
    Instant startTime =
            Instant.parse(
                    "2026-08-03T05:00:00Z"
            );

    return new TraceSpanDetail(
            TRACE_ID,
            spanId,
            parentSpanId,
            "service-a",
            "",
            "",
            "test-span",
            (short) 2,
            (short) 1,
            "",
            startTime,
            startTime.plusMillis(5),
            5_000_000L
    );
  }
}