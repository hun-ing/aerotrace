package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application
        .AuthenticatedProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TraceQueryPaginationServiceTest {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final Instant FROM =
          Instant.parse("2026-08-01T00:00:00Z");

  private static final Instant TO =
          Instant.parse("2026-08-03T00:00:00Z");

  @Mock
  private JdbcTraceQueryRepository repository;

  @Mock
  private AuthenticatedProject authenticatedProject;

  private TraceQueryService service;

  @BeforeEach
  void setUp() {
    service =
            new TraceQueryService(repository);
  }

  @Test
  void returnsNextCursorWhenRepositoryHasExtraItem() {
    TraceListItem first =
            item(
                    "cccccccccccccccccccccccccccccccc",
                    "2026-08-02T12:00:00Z"
            );

    TraceListItem second =
            item(
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "2026-08-02T11:00:00Z"
            );

    TraceListItem extra =
            item(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "2026-08-02T10:00:00Z"
            );

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);

    when(
            repository.findTraceList(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    null,
                    3
            )
    ).thenReturn(
            List.of(
                    first,
                    second,
                    extra
            )
    );

    TraceListPage page =
            service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    null,
                    2
            );

    assertThat(page.items())
            .containsExactly(
                    first,
                    second
            );

    assertThat(page.hasNext())
            .isTrue();

    String expectedFingerprint =
            TraceListQueryFingerprint.create(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    null,
                    false
            );

    assertThat(page.nextCursor())
            .isEqualTo(
                    new TraceListCursor(
                            second.traceStartTime(),
                            second.traceId(),
                            expectedFingerprint
                    )
            );
    verify(repository).findTraceList(
            TENANT_ID,
            PROJECT_ID,
            FROM,
            TO,
            null,
            3
    );
  }

  @Test
  void returnsNullCursorForLastPage() {
    String queryFingerprint =
            TraceListQueryFingerprint.create(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    null,
                    false
            );

    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-02T11:00:00Z"
                    ),
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    queryFingerprint
            );

    TraceListItem finalItem =
            item(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "2026-08-02T10:00:00Z"
            );

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);

    when(
            repository.findTraceList(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    cursor,
                    3
            )
    ).thenReturn(
            List.of(finalItem)
    );

    TraceListPage page =
            service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    cursor,
                    2
            );

    assertThat(page.items())
            .containsExactly(finalItem);

    assertThat(page.hasNext())
            .isFalse();

    assertThat(page.nextCursor())
            .isNull();
  }

  @Test
  void rejectsCursorOutsideRequestedTimeRange() {
    TraceListCursor cursor =
            new TraceListCursor(
                    FROM.minusSeconds(1),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );

    assertThatThrownBy(
            () -> service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    cursor,
                    50
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor is outside the requested time range"
            );

    verifyNoInteractions(repository);
  }

  @Test
  void normalizesAndPassesServiceNameFilter() {
    TraceListItem item =
            item(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "2026-08-02T10:00:00Z"
            );

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);

    when(
            repository.findTraceList(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    null,
                    "orders-service",
                    3
            )
    ).thenReturn(
            List.of(item)
    );

    TraceListPage page =
            service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    null,
                    "  orders-service  ",
                    2
            );

    assertThat(page.items())
            .containsExactly(item);

    assertThat(page.nextCursor())
            .isNull();

    verify(repository).findTraceList(
            TENANT_ID,
            PROJECT_ID,
            FROM,
            TO,
            null,
            "orders-service",
            3
    );
  }

  @Test
  void rejectsBlankServiceNameBeforeDatabaseQuery() {
    assertThatThrownBy(
            () -> service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    null,
                    "   ",
                    50
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "serviceName must not be blank"
            );

    verifyNoInteractions(repository);
  }

  @Test
  void passesCombinedServiceAndErrorFilter() {
    TraceListItem item =
            item(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "2026-08-02T10:00:00Z"
            );

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);

    when(
            repository.findTraceList(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    null,
                    "orders-service",
                    true,
                    3
            )
    ).thenReturn(
            List.of(item)
    );

    TraceListPage page =
            service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    null,
                    "  orders-service  ",
                    true,
                    2
            );

    assertThat(page.items())
            .containsExactly(item);

    assertThat(page.nextCursor())
            .isNull();

    verify(repository).findTraceList(
            TENANT_ID,
            PROJECT_ID,
            FROM,
            TO,
            null,
            "orders-service",
            true,
            3
    );
  }

  @Test
  void rejectsCursorCreatedForDifferentServiceFilter() {
    String previousQueryFingerprint =
            TraceListQueryFingerprint.create(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    "orders-service",
                    false
            );

    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-02T10:00:00Z"
                    ),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    previousQueryFingerprint
            );

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);

    assertThatThrownBy(
            () -> service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    cursor,
                    "payment-service",
                    50
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor does not match the current query"
            );

    verifyNoInteractions(repository);
  }

  @Test
  void rejectsCursorCreatedForDifferentErrorFilter() {
    String previousQueryFingerprint =
            TraceListQueryFingerprint.create(
                    TENANT_ID,
                    PROJECT_ID,
                    FROM,
                    TO,
                    null,
                    false
            );

    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-02T10:00:00Z"
                    ),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    previousQueryFingerprint
            );

    when(
            authenticatedProject.tenantId()
    ).thenReturn(TENANT_ID);

    when(
            authenticatedProject.projectId()
    ).thenReturn(PROJECT_ID);

    assertThatThrownBy(
            () -> service.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    cursor,
                    null,
                    true,
                    50
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor does not match the current query"
            );

    verifyNoInteractions(repository);
  }

  private static TraceListItem item(
          String traceId,
          String traceStartTime
  ) {
    return new TraceListItem(
            traceId,
            Instant.parse(traceStartTime),
            1,
            1,
            5_000_000L
    );
  }
}