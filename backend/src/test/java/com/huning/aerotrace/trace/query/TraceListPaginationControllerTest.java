package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application
        .AuthenticatedProject;
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
class TraceListPaginationControllerTest {

  private static final Instant FROM =
          Instant.parse(
                  "2026-08-01T00:00:00Z"
          );

  private static final Instant TO =
          Instant.parse(
                  "2026-08-03T06:00:00Z"
          );

  @Mock
  private TraceQueryService traceQueryService;

  @Mock
  private TraceDetailQueryService
          traceDetailQueryService;

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
  void returnsEncodedNextCursor() throws Exception {
    TraceListItem item =
            new TraceListItem(
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    Instant.parse(
                            "2026-08-02T10:00:00Z"
                    ),
                    2,
                    1,
                    5_000_000L
            );

    TraceListCursor nextCursor =
            new TraceListCursor(
                    item.traceStartTime(),
                    item.traceId()
            );

    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    when(
            traceQueryService.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    null,
                    1
            )
    ).thenReturn(
            new TraceListPage(
                    List.of(item),
                    nextCursor
            )
    );

    String expectedCursor =
            TraceListCursorCodec.encode(
                    nextCursor
            );

    mockMvc.perform(
                    get("/api/v1/traces")
                            .queryParam(
                                    "from",
                                    "2026-08-01T00:00:00Z"
                            )
                            .queryParam(
                                    "to",
                                    "2026-08-03T06:00:00Z"
                            )
                            .queryParam(
                                    "limit",
                                    "1"
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
                    jsonPath("$.items.length()")
                            .value(1)
            )
            .andExpect(
                    jsonPath("$.items[0].traceId")
                            .value(item.traceId())
            )
            .andExpect(
                    jsonPath("$.nextCursor")
                            .value(expectedCursor)
            );
  }

  @Test
  void decodesRequestCursor() throws Exception {
    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-02T10:00:00Z"
                    ),
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            );

    String encodedCursor =
            TraceListCursorCodec.encode(cursor);

    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    when(
            traceQueryService.findTracePage(
                    authenticatedProject,
                    FROM,
                    TO,
                    cursor,
                    1
            )
    ).thenReturn(
            new TraceListPage(
                    List.of(),
                    null
            )
    );

    mockMvc.perform(
                    get("/api/v1/traces")
                            .queryParam(
                                    "from",
                                    "2026-08-01T00:00:00Z"
                            )
                            .queryParam(
                                    "to",
                                    "2026-08-03T06:00:00Z"
                            )
                            .queryParam(
                                    "limit",
                                    "1"
                            )
                            .queryParam(
                                    "cursor",
                                    encodedCursor
                            )
            )
            .andExpect(
                    status().isOk()
            )
            .andExpect(
                    jsonPath("$.items.length()")
                            .value(0)
            )
            .andExpect(
                    jsonPath("$.nextCursor")
                            .doesNotExist()
            );
  }

  @Test
  void returnsBadRequestForInvalidCursor()
          throws Exception {
    when(
            authenticatedProjectRequestResolver.resolve(
                    any(HttpServletRequest.class)
            )
    ).thenReturn(authenticatedProject);

    mockMvc.perform(
                    get("/api/v1/traces")
                            .queryParam(
                                    "from",
                                    "2026-08-01T00:00:00Z"
                            )
                            .queryParam(
                                    "to",
                                    "2026-08-03T06:00:00Z"
                            )
                            .queryParam(
                                    "limit",
                                    "1"
                            )
                            .queryParam(
                                    "cursor",
                                    "%%%invalid%%%"
                            )
            )
            .andExpect(
                    status().isBadRequest()
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
                                    "cursor is invalid"
                            )
            );
  }
}