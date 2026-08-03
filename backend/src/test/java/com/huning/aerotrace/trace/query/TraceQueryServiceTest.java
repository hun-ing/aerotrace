package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceQueryServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID PROJECT_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    @Mock
    private JdbcTraceQueryRepository repository;

    @Mock
    private AuthenticatedProject authenticatedProject;

    private TraceQueryService traceQueryService;

    @BeforeEach
    void setUp() {
        traceQueryService =
                new TraceQueryService(repository);
    }

    @Test
    void usesAuthenticatedTenantAndProjectForQuery() {
        Instant from =
                Instant.parse(
                        "2026-08-01T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-08-03T00:00:00Z"
                );

        TraceListItem expectedItem =
                new TraceListItem(
                        "0123456789abcdef0123456789abcdef",
                        Instant.parse(
                                "2026-08-02T10:00:00Z"
                        ),
                        3,
                        2,
                        5_000_000L
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
                        from,
                        to,
                        50
                )
        ).thenReturn(
                List.of(expectedItem)
        );

        List<TraceListItem> result =
                traceQueryService.findTraceList(
                        authenticatedProject,
                        from,
                        to,
                        50
                );

        assertThat(result)
                .containsExactly(expectedItem);

        verify(repository).findTraceList(
                TENANT_ID,
                PROJECT_ID,
                from,
                to,
                50
        );
    }

    @Test
    void rejectsQueryRangeLongerThanThirtyDays() {
        Instant from =
                Instant.parse(
                        "2026-07-01T00:00:00Z"
                );

        Instant to =
                from.plus(
                        Duration.ofDays(30)
                ).plusNanos(1);

        assertThatThrownBy(
                () -> traceQueryService.findTraceList(
                        authenticatedProject,
                        from,
                        to,
                        50
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Query range must not exceed 30 days"
                );

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsLimitGreaterThanRepositoryMaximum() {
        Instant from =
                Instant.parse(
                        "2026-08-01T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-08-03T00:00:00Z"
                );

        int invalidLimit =
                JdbcTraceQueryRepository.MAX_LIMIT + 1;

        assertThatThrownBy(
                () -> traceQueryService.findTraceList(
                        authenticatedProject,
                        from,
                        to,
                        invalidLimit
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "limit must be between 1 and "
                                + JdbcTraceQueryRepository
                                .MAX_LIMIT
                );

        verifyNoInteractions(repository);
    }
}