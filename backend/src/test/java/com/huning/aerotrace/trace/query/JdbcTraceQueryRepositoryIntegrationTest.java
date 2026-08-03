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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class JdbcTraceQueryRepositoryIntegrationTest {

  private static final String SHARED_TRACE_ID =
          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static final String PROJECT_A_TRACE_ID =
          "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  private static final String PROJECT_B_TRACE_ID =
          "cccccccccccccccccccccccccccccccc";

  private static final String SHARED_SPAN_ID_1 =
          "1111111111111111";

  private static final String SHARED_SPAN_ID_2 =
          "2222222222222222";

  private static final String PROJECT_ONLY_SPAN_ID =
          "3333333333333333";

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private JdbcTraceQueryRepository repository;

  private UUID tenantAId;
  private UUID projectAId;

  private UUID tenantBId;
  private UUID projectBId;

  private Instant baseTime;

  @BeforeEach
  void setUp() {
    tenantAId = UUID.randomUUID();
    projectAId = UUID.randomUUID();

    tenantBId = UUID.randomUUID();
    projectBId = UUID.randomUUID();

    baseTime =
            Instant.now()
                    .truncatedTo(ChronoUnit.SECONDS)
                    .minus(Duration.ofMinutes(10));

    insertTenant(
            tenantAId,
            "Integration Tenant A",
            uniqueSlug("integration-tenant-a")
    );

    insertProject(
            projectAId,
            tenantAId,
            "Integration Project A",
            uniqueSlug("integration-project-a")
    );

    insertTenant(
            tenantBId,
            "Integration Tenant B",
            uniqueSlug("integration-tenant-b")
    );

    insertProject(
            projectBId,
            tenantBId,
            "Integration Project B",
            uniqueSlug("integration-project-b")
    );

    insertProjectATraces();
    insertProjectBTraces();
  }

  @Test
  void isolatesTraceAggregatesByTenantAndProject() {
    Instant from =
            baseTime.minus(Duration.ofHours(1));

    Instant to =
            baseTime.plus(Duration.ofHours(1));

    List<TraceListItem> projectAResults =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    50
            );

    List<TraceListItem> projectBResults =
            repository.findTraceList(
                    tenantBId,
                    projectBId,
                    from,
                    to,
                    50
            );

    assertThat(projectAResults)
            .extracting(TraceListItem::traceId)
            .containsExactly(
                    PROJECT_A_TRACE_ID,
                    SHARED_TRACE_ID
            );

    assertThat(projectAResults)
            .extracting(TraceListItem::traceId)
            .doesNotContain(PROJECT_B_TRACE_ID);

    TraceListItem projectASharedTrace =
            findByTraceId(
                    projectAResults,
                    SHARED_TRACE_ID
            );

    assertThat(projectASharedTrace.spanCount())
            .isEqualTo(2);

    assertThat(projectASharedTrace.serviceCount())
            .isEqualTo(2);

    assertThat(
            projectASharedTrace
                    .longestSpanDurationNano()
    ).isEqualTo(10_000_000L);

    assertThat(
            projectASharedTrace.traceStartTime()
    ).isEqualTo(baseTime);

    assertThat(projectBResults)
            .extracting(TraceListItem::traceId)
            .containsExactly(
                    PROJECT_B_TRACE_ID,
                    SHARED_TRACE_ID
            );

    assertThat(projectBResults)
            .extracting(TraceListItem::traceId)
            .doesNotContain(PROJECT_A_TRACE_ID);

    TraceListItem projectBSharedTrace =
            findByTraceId(
                    projectBResults,
                    SHARED_TRACE_ID
            );

    /*
     * Project A에도 동일한 trace_id가 존재하지만
     * Project B 결과에는 Project B의 Span만 집계돼야 한다.
     */
    assertThat(projectBSharedTrace.spanCount())
            .isEqualTo(1);

    assertThat(projectBSharedTrace.serviceCount())
            .isEqualTo(1);

    assertThat(
            projectBSharedTrace
                    .longestSpanDurationNano()
    ).isEqualTo(30_000_000L);
  }

  @Test
  void appliesLimitAfterOrderingNewestTraceFirst() {
    Instant from =
            baseTime.minus(Duration.ofHours(1));

    Instant to =
            baseTime.plus(Duration.ofHours(1));

    List<TraceListItem> result =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    1
            );

    assertThat(result)
            .hasSize(1);

    assertThat(result.getFirst().traceId())
            .isEqualTo(PROJECT_A_TRACE_ID);
  }

  @Test
  void continuesAfterCursorWithoutDuplicates() {
    Instant from =
            baseTime.minus(Duration.ofHours(1));

    Instant to =
            baseTime.plus(Duration.ofHours(1));

    List<TraceListItem> firstPage =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    null,
                    1
            );

    assertThat(firstPage)
            .hasSize(1);

    assertThat(firstPage.getFirst().traceId())
            .isEqualTo(PROJECT_A_TRACE_ID);

    TraceListItem lastItem =
            firstPage.getFirst();

    TraceListCursor cursor =
            new TraceListCursor(
                    lastItem.traceStartTime(),
                    lastItem.traceId()
            );

    List<TraceListItem> secondPage =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    cursor,
                    10
            );

    assertThat(secondPage)
            .extracting(TraceListItem::traceId)
            .containsExactly(
                    SHARED_TRACE_ID
            );

    assertThat(secondPage)
            .extracting(TraceListItem::traceId)
            .doesNotContain(
                    PROJECT_A_TRACE_ID
            );
  }

  @Test
  void filtersByServiceWithoutChangingWholeTraceAggregates() {
    Instant from =
            baseTime.minus(Duration.ofHours(1));

    Instant to =
            baseTime.plus(Duration.ofHours(1));

    List<TraceListItem> result =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    null,
                    "project-a-service-two",
                    50
            );

    assertThat(result)
            .extracting(TraceListItem::traceId)
            .containsExactly(
                    SHARED_TRACE_ID
            );

    TraceListItem sharedTrace =
            result.getFirst();

    /*
     * project-a-service-two를 포함하는 Trace를 찾았지만,
     * 집계값은 해당 서비스 Span만이 아니라
     * Trace 전체 Span을 기준으로 계산돼야 한다.
     */
    assertThat(sharedTrace.spanCount())
            .isEqualTo(2);

    assertThat(sharedTrace.serviceCount())
            .isEqualTo(2);

    assertThat(
            sharedTrace.longestSpanDurationNano()
    ).isEqualTo(10_000_000L);
  }

  @Test
  void filtersErrorTracesWithoutChangingWholeTraceAggregates() {
    /*
     * shared trace의 두 번째 Span만 오류 상태로 만든다.
     *
     * 첫 번째 Span:
     * - project-a-service-one
     * - status_code = 0
     *
     * 두 번째 Span:
     * - project-a-service-two
     * - status_code = 2
     */
    int updatedRows =
            jdbcTemplate.update(
                    """
                    UPDATE public.spans
                    SET status_code = 2,
                        status_message = 'integration error'
                    WHERE tenant_id = ?
                      AND project_id = ?
                      AND trace_id = ?
                      AND span_id = ?
                    """,
                    tenantAId,
                    projectAId,
                    SHARED_TRACE_ID,
                    SHARED_SPAN_ID_2
            );

    assertThat(updatedRows)
            .isEqualTo(1);

    Instant from =
            baseTime.minus(Duration.ofHours(1));

    Instant to =
            baseTime.plus(Duration.ofHours(1));

    List<TraceListItem> result =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    null,
                    "project-a-service-one",
                    true,
                    50
            );

    /*
     * project-a-service-one을 포함하면서
     * 오류 Span도 포함한 Trace는 shared trace뿐이다.
     *
     * 서비스 조건과 오류 조건은 서로 다른 Span에서
     * 충족되지만 같은 Trace 안에 있으므로 반환돼야 한다.
     */
    assertThat(result)
            .extracting(TraceListItem::traceId)
            .containsExactly(
                    SHARED_TRACE_ID
            );

    TraceListItem sharedTrace =
            result.getFirst();

    /*
     * 필터에 직접 일치한 Span만 집계하면 1이 되지만,
     * Trace 전체 기준이므로 2가 유지돼야 한다.
     */
    assertThat(sharedTrace.spanCount())
            .isEqualTo(2);

    assertThat(sharedTrace.serviceCount())
            .isEqualTo(2);

    assertThat(
            sharedTrace.longestSpanDurationNano()
    ).isEqualTo(10_000_000L);
  }

  @Test
  void combinesServiceErrorAndDurationFilters() {
    int updatedRows =
            jdbcTemplate.update(
                    """
                    UPDATE public.spans
                    SET status_code = 2,
                        status_message = 'slow trace error'
                    WHERE tenant_id = ?
                      AND project_id = ?
                      AND trace_id = ?
                      AND span_id = ?
                    """,
                    tenantAId,
                    projectAId,
                    SHARED_TRACE_ID,
                    SHARED_SPAN_ID_2
            );

    assertThat(updatedRows)
            .isEqualTo(1);

    Instant from =
            baseTime.minus(Duration.ofHours(1));

    Instant to =
            baseTime.plus(Duration.ofHours(1));

    List<TraceListItem> result =
            repository.findTraceList(
                    tenantAId,
                    projectAId,
                    from,
                    to,
                    null,
                    "project-a-service-one",
                    true,
                    9_000_000L,
                    50
            );

    /*
     * shared trace:
     * - service-one Span 포함
     * - 다른 Span이 ERROR
     * - 최대 Span duration 10ms
     */
    assertThat(result)
            .extracting(TraceListItem::traceId)
            .containsExactly(
                    SHARED_TRACE_ID
            );

    TraceListItem sharedTrace =
            result.getFirst();

    assertThat(sharedTrace.spanCount())
            .isEqualTo(2);

    assertThat(sharedTrace.serviceCount())
            .isEqualTo(2);

    assertThat(
            sharedTrace.longestSpanDurationNano()
    ).isEqualTo(10_000_000L);
  }

  private void insertProjectATraces() {
    insertSpan(
            tenantAId,
            projectAId,
            SHARED_TRACE_ID,
            SHARED_SPAN_ID_1,
            "project-a-service-one",
            "project-a-shared-root",
            baseTime,
            5_000_000L
    );

    insertSpan(
            tenantAId,
            projectAId,
            SHARED_TRACE_ID,
            SHARED_SPAN_ID_2,
            "project-a-service-two",
            "project-a-shared-child",
            baseTime.plusMillis(1),
            10_000_000L
    );

    /*
     * shared trace보다 최신이므로 목록 첫 번째에 와야 한다.
     */
    insertSpan(
            tenantAId,
            projectAId,
            PROJECT_A_TRACE_ID,
            PROJECT_ONLY_SPAN_ID,
            "project-a-service-one",
            "project-a-newest-trace",
            baseTime.plusSeconds(10),
            20_000_000L
    );
  }

  private void insertProjectBTraces() {
    /*
     * Project A와 동일한 trace_id 및 span_id를 사용한다.
     *
     * unique index에는 tenant_id와 project_id가 포함되므로
     * 다른 Project에서는 동일 식별자를 사용할 수 있다.
     */
    insertSpan(
            tenantBId,
            projectBId,
            SHARED_TRACE_ID,
            SHARED_SPAN_ID_1,
            "project-b-service-one",
            "project-b-shared-root",
            baseTime.plusSeconds(1),
            30_000_000L
    );

    insertSpan(
            tenantBId,
            projectBId,
            PROJECT_B_TRACE_ID,
            PROJECT_ONLY_SPAN_ID,
            "project-b-service-one",
            "project-b-newest-trace",
            baseTime.plusSeconds(20),
            40_000_000L
    );
  }

  private void insertTenant(
          UUID tenantId,
          String name,
          String slug
  ) {
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
                    name,
                    slug
            );

    assertThat(insertedRows)
            .isEqualTo(1);
  }

  private void insertProject(
          UUID projectId,
          UUID tenantId,
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

  private void insertSpan(
          UUID tenantId,
          UUID projectId,
          String traceId,
          String spanId,
          String serviceName,
          String spanName,
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
                        service_name,
                        name,
                        span_kind,
                        start_time,
                        end_time,
                        duration_nano
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId,
                    projectId,
                    traceId,
                    spanId,
                    serviceName,
                    spanName,
                    2,
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

  private static TraceListItem findByTraceId(
          List<TraceListItem> items,
          String traceId
  ) {
    return items.stream()
            .filter(
                    item ->
                            item.traceId()
                                    .equals(traceId)
            )
            .findFirst()
            .orElseThrow(
                    () -> new AssertionError(
                            "Trace not found: "
                                    + traceId
                    )
            );
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