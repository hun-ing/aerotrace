package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import com.huning.aerotrace.ingest.api
        .AuthenticatedProjectRequestResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request
        .MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TraceDetailQueryControllerTest {

  private static final String TRACE_ID =
          "dddddddddddddddddddddddddddddddd";

  @Mock
  private TraceQueryService traceQueryService;

  @Mock
  private TraceDetailQueryService traceDetailQueryService;

  @Mock
  private AuthenticatedProjectRequestResolver
          authenticatedProjectRequestResolver;

  @Mock
  private AuthenticatedProject authenticatedProject;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    TraceQueryController controller =
            new TraceQueryController(
                    traceQueryService,
                    traceDetailQueryService,
                    authenticatedProjectRequestResolver
            );

    mockMvc =
            MockMvcBuilders
                    .standaloneSetup(controller)
                    .setControllerAdvice(
                            new TraceQueryExceptionHandler()
                    )
                    .build();
  }

  @Test
  void returnsTraceDetail() throws Exception {
    TraceSpanDetail rootSpan =
            createSpan(
                    "1111111111111111",
                    null,
                    "api-service",
                    "root-span",
                    Instant.parse(
                            "2026-08-03T05:00:00Z"
                    )
            );

    TraceSpanDetail childSpan =
            createSpan(
                    "2222222222222222",
                    "1111111111111111",
                    "database-service",
                    "select-span",
                    Instant.parse(
                            "2026-08-03T05:00:00.001Z"
                    )
            );

    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    when(
            traceDetailQueryService.findTraceSpans(
                    authenticatedProject,
                    TRACE_ID
            )
    ).thenReturn(
            List.of(
                    rootSpan,
                    childSpan
            )
    );

    mockMvc.perform(
                    get(
                            "/api/v1/traces/{traceId}",
                            TRACE_ID
                    )
            )
            .andExpect(
                    status().isOk()
            )
            .andExpect(
                    header().string(
                            HttpHeaders.CACHE_CONTROL,
                            "no-store"
                    )
            )
            .andExpect(
                    jsonPath("$.traceId")
                            .value(TRACE_ID)
            )
            .andExpect(
                    jsonPath("$.spanCount")
                            .value(2)
            )
            .andExpect(
                    jsonPath("$.spans.length()")
                            .value(2)
            )
            .andExpect(
                    jsonPath("$.spans[0].spanId")
                            .value(
                                    "1111111111111111"
                            )
            )
            .andExpect(
                    jsonPath("$.spans[1].spanId")
                            .value(
                                    "2222222222222222"
                            )
            )
            .andExpect(
                    jsonPath("$.spans[1].parentSpanId")
                            .value(
                                    "1111111111111111"
                            )
            )
            .andExpect(
                    jsonPath("$.spans[1].serviceName")
                            .value(
                                    "database-service"
                            )
            );
  }

  @Test
  void returnsNotFoundWhenTraceDoesNotExist()
          throws Exception {
    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    when(
            traceDetailQueryService.findTraceSpans(
                    authenticatedProject,
                    TRACE_ID
            )
    ).thenThrow(
            new TraceNotFoundException()
    );

    mockMvc.perform(
                    get(
                            "/api/v1/traces/{traceId}",
                            TRACE_ID
                    )
            )
            .andExpect(
                    status().isNotFound()
            )
            .andExpect(
                    header().string(
                            HttpHeaders.CACHE_CONTROL,
                            "no-store"
                    )
            )
            .andExpect(
                    jsonPath("$.message")
                            .value(
                                    "Trace was not found"
                            )
            );
  }

  @Test
  void returnsBadRequestForMalformedTraceId()
          throws Exception {
    String malformedTraceId =
            "invalid-trace-id";

    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    when(
            traceDetailQueryService.findTraceSpans(
                    authenticatedProject,
                    malformedTraceId
            )
    ).thenThrow(
            new IllegalArgumentException(
                    "traceId must be a non-zero "
                            + "32-character lowercase "
                            + "hexadecimal value"
            )
    );

    mockMvc.perform(
                    get(
                            "/api/v1/traces/{traceId}",
                            malformedTraceId
                    )
            )
            .andExpect(
                    status().isBadRequest()
            )
            .andExpect(
                    jsonPath("$.message")
                            .value(
                                    "traceId must be a non-zero "
                                            + "32-character lowercase "
                                            + "hexadecimal value"
                            )
            );
  }

  @Test
  void returnsUnprocessableEntityForOversizedTrace()
          throws Exception {
    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    when(
            traceDetailQueryService.findTraceSpans(
                    authenticatedProject,
                    TRACE_ID
            )
    ).thenThrow(
            new TraceSpanLimitExceededException(
                    5_000
            )
    );

    mockMvc.perform(
                    get(
                            "/api/v1/traces/{traceId}",
                            TRACE_ID
                    )
            )
            .andExpect(
                    status().isUnprocessableEntity()
            )
            .andExpect(
                    header().string(
                            HttpHeaders.CACHE_CONTROL,
                            "no-store"
                    )
            )
            .andExpect(
                    jsonPath("$.message")
                            .value(
                                    "Trace contains more than "
                                            + "5000 spans"
                            )
            );
  }

  private static TraceSpanDetail createSpan(
          String spanId,
          String parentSpanId,
          String serviceName,
          String spanName,
          Instant startTime
  ) {
    return new TraceSpanDetail(
            TRACE_ID,
            spanId,
            parentSpanId,
            serviceName,
            "",
            "",
            spanName,
            (short) 2,
            (short) 1,
            "",
            startTime,
            startTime.plusMillis(5),
            5_000_000L
    );
  }
}