package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application
        .AuthenticatedProject;
import com.huning.aerotrace.ingest.api
        .AuthenticatedProjectRequestResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/traces")
public class TraceQueryController {

  private static final String DEFAULT_LIMIT =
          "50";

  private final TraceQueryService traceQueryService;

  private final TraceDetailQueryService
          traceDetailQueryService;

  private final AuthenticatedProjectRequestResolver
          authenticatedProjectRequestResolver;

  public TraceQueryController(
          TraceQueryService traceQueryService,
          TraceDetailQueryService
                  traceDetailQueryService,
          AuthenticatedProjectRequestResolver
                  authenticatedProjectRequestResolver
  ) {
    this.traceQueryService =
            Objects.requireNonNull(
                    traceQueryService,
                    "traceQueryService must not be null"
            );

    this.traceDetailQueryService =
            Objects.requireNonNull(
                    traceDetailQueryService,
                    "traceDetailQueryService "
                            + "must not be null"
            );

    this.authenticatedProjectRequestResolver =
            Objects.requireNonNull(
                    authenticatedProjectRequestResolver,
                    "authenticatedProjectRequestResolver "
                            + "must not be null"
            );
  }

  @GetMapping
  public ResponseEntity<TraceListResponse> findTraceList(
          @RequestParam String from,
          @RequestParam String to,
          @RequestParam(
                  defaultValue = DEFAULT_LIMIT
          ) String limit,
          HttpServletRequest request
  ) {
    AuthenticatedProject authenticatedProject =
            authenticatedProjectRequestResolver
                    .resolve(request);

    Instant parsedFrom =
            parseInstant(
                    from,
                    "from"
            );

    Instant parsedTo =
            parseInstant(
                    to,
                    "to"
            );

    int parsedLimit =
            parseLimit(limit);

    List<TraceListItem> items =
            traceQueryService.findTraceList(
                    authenticatedProject,
                    parsedFrom,
                    parsedTo,
                    parsedLimit
            );

    return ResponseEntity.ok()
            .cacheControl(
                    CacheControl.noStore()
            )
            .body(
                    TraceListResponse.from(items)
            );
  }

  @GetMapping("/{traceId}")
  public ResponseEntity<TraceDetailResponse>
  findTraceDetail(
          @PathVariable String traceId,
          HttpServletRequest request
  ) {
    AuthenticatedProject authenticatedProject =
            authenticatedProjectRequestResolver
                    .resolve(request);

    List<TraceSpanDetail> spans =
            traceDetailQueryService.findTraceSpans(
                    authenticatedProject,
                    traceId
            );

    return ResponseEntity.ok()
            .cacheControl(
                    CacheControl.noStore()
            )
            .body(
                    TraceDetailResponse.from(
                            traceId,
                            spans
                    )
            );
  }

  private static Instant parseInstant(
          String value,
          String parameterName
  ) {
    if (
            value == null
                    || value.isBlank()
    ) {
      throw new IllegalArgumentException(
              parameterName
                      + " must not be blank"
      );
    }

    try {
      return OffsetDateTime.parse(value)
              .toInstant();
    } catch (
            DateTimeParseException exception
    ) {
      throw new IllegalArgumentException(
              parameterName
                      + " must be an ISO-8601 "
                      + "date-time with an offset",
              exception
      );
    }
  }

  private static int parseLimit(
          String value
  ) {
    try {
      return Integer.parseInt(value);
    } catch (
            NumberFormatException exception
    ) {
      throw new IllegalArgumentException(
              "limit must be an integer",
              exception
      );
    }
  }
}