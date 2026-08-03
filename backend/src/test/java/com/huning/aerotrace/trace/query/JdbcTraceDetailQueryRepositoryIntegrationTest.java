package com.huning.aerotrace.trace.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class JdbcTraceDetailQueryRepositoryIntegrationTest {

  private static final String TRACE_ID =
          "dddddddddddddddddddddddddddddddd";

  private static final String ROOT_SPAN_ID =
          "1111111111111111";

  private static final String CHILD_SPAN_ID =
          "2222222222222222";

  private static final String OTHER_PROJECT_SPAN_ID =
          "3333333333333333";

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private JdbcTraceDetailQueryRepository repository;

  private UUID tenantId;
  private UUID projectAId;
  private UUID projectBId;

  private Instant baseTime;

  @BeforeEach
  void setUp() {
    tenantId = UUID.randomUUID();
    projectAId = UUID.randomUUID();
    projectBId = UUID.randomUUID();

    baseTime =
            Instant.now()
                    .truncatedTo(ChronoUnit.SECONDS)
                    .minus(Duration.ofMinutes(10));

    insertTenant();

    insertProject(
            projectAId,
            "Trace Detail Project A",
            uniqueSlug("trace-detail-project-a")
    );

    insertProject(
            projectBId,
            "Trace Detail Project B",
            uniqueSlug("trace-detail-project-b")
    );

    insertProjectASpans();
    insertProjectBSpan();
  }

  @Test
  void returnsOnlySpansOwnedByRequestedProject() {
    List<TraceSpanDetail> projectASpans =
            repository.findTraceSpans(
                    tenantId,
                    projectAId,
                    TRACE_ID,
                    100
            );

    assertThat(projectASpans)
            .extracting(TraceSpanDetail::spanId)
            .containsExactly(
                    ROOT_SPAN_ID,
                    CHILD_SPAN_ID
            );

    assertThat(projectASpans)
            .extracting(TraceSpanDetail::spanId)
            .doesNotContain(
                    OTHER_PROJECT_SPAN_ID
            );

    TraceSpanDetail rootSpan =
            projectASpans.get(0);

    assertThat(rootSpan.parentSpanId())
            .isNull();

    assertThat(rootSpan.serviceName())
            .isEqualTo("project-a-api");

    assertThat(rootSpan.name())
            .isEqualTo("project-a-root");

    assertThat(rootSpan.startTime())
            .isEqualTo(baseTime);

    assertThat(rootSpan.durationNano())
            .isEqualTo(20_000_000L);

    TraceSpanDetail childSpan =
            projectASpans.get(1);

    assertThat(childSpan.parentSpanId())
            .isEqualTo(ROOT_SPAN_ID);

    assertThat(childSpan.serviceName())
            .isEqualTo("project-a-database");

    assertThat(childSpan.statusCode())
            .isEqualTo((short) 2);

    List<TraceSpanDetail> projectBSpans =
            repository.findTraceSpans(
                    tenantId,
                    projectBId,
                    TRACE_ID,
                    100
            );

    assertThat(projectBSpans)
            .extracting(TraceSpanDetail::spanId)
            .containsExactly(
                    OTHER_PROJECT_SPAN_ID
            );
  }

  @Test
  void appliesLimitAfterOrderingOldestSpanFirst() {
    List<TraceSpanDetail> result =
            repository.findTraceSpans(
                    tenantId,
                    projectAId,
                    TRACE_ID,
                    1
            );

    assertThat(result)
            .hasSize(1);

    assertThat(result.getFirst().spanId())
            .isEqualTo(ROOT_SPAN_ID);
  }

  @Test
  void rejectsMalformedTraceIdBeforeExecutingQuery() {
    assertThatThrownBy(
            () -> repository.findTraceSpans(
                    tenantId,
                    projectAId,
                    "invalid-trace-id",
                    100
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "traceId must be a non-zero "
                            + "32-character lowercase "
                            + "hexadecimal value"
            );
  }

  private void insertTenant() {
    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO public.tenants (
                        id,
                        name,
                        slug
                    )
                    VALUES (?, ?, ?)
                    """,
                    tenantId,
                    "Trace Detail Tenant",
                    uniqueSlug("trace-detail-tenant")
            );

    assertThat(insertedRows)
            .isEqualTo(1);
  }

  private void insertProject(
          UUID projectId,
          String name,
          String slug
  ) {
    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO public.projects (
                        id,
                        tenant_id,
                        name,
                        slug
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    projectId,
                    tenantId,
                    name,
                    slug
            );

    assertThat(insertedRows)
            .isEqualTo(1);
  }

  private void insertProjectASpans() {
    insertSpan(
            projectAId,
            TRACE_ID,
            ROOT_SPAN_ID,
            null,
            "project-a-api",
            "project-a-root",
            (short) 2,
            (short) 1,
            "",
            baseTime,
            20_000_000L
    );

    insertSpan(
            projectAId,
            TRACE_ID,
            CHILD_SPAN_ID,
            ROOT_SPAN_ID,
            "project-a-database",
            "project-a-select",
            (short) 3,
            (short) 2,
            "database timeout",
            baseTime.plusMillis(5),
            10_000_000L
    );
  }

  private void insertProjectBSpan() {
    /*
     * Project A와 동일한 trace_id를 사용한다.
     * Repository가 project_id 조건을 빠뜨리면
     * 이 Span이 Project A의 Trace에 섞이게 된다.
     */
    insertSpan(
            projectBId,
            TRACE_ID,
            OTHER_PROJECT_SPAN_ID,
            null,
            "project-b-api",
            "project-b-root",
            (short) 2,
            (short) 1,
            "",
            baseTime.plusMillis(1),
            30_000_000L
    );
  }

  private void insertSpan(
          UUID projectId,
          String traceId,
          String spanId,
          String parentSpanId,
          String serviceName,
          String spanName,
          short spanKind,
          short statusCode,
          String statusMessage,
          Instant startTime,
          long durationNano
  ) {
    Instant endTime =
            startTime.plusNanos(durationNano);

    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO public.spans (
                        tenant_id,
                        project_id,
                        trace_id,
                        span_id,
                        parent_span_id,
                        service_name,
                        name,
                        span_kind,
                        status_code,
                        status_message,
                        start_time,
                        end_time,
                        duration_nano
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """,
                    tenantId,
                    projectId,
                    traceId,
                    spanId,
                    parentSpanId,
                    serviceName,
                    spanName,
                    spanKind,
                    statusCode,
                    statusMessage,
                    OffsetDateTime.ofInstant(
                            startTime,
                            ZoneOffset.UTC
                    ),
                    OffsetDateTime.ofInstant(
                            endTime,
                            ZoneOffset.UTC
                    ),
                    durationNano
            );

    assertThat(insertedRows)
            .isEqualTo(1);
  }

  private static String uniqueSlug(
          String prefix
  ) {
    return prefix
            + "-"
            + UUID.randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, 12);
  }
}