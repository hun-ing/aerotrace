package com.huning.aerotrace.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class ColumnstorePolicyVerifier {

  private static final URI COLLECTOR_ENDPOINT =
          URI.create("http://localhost:4318/v1/traces");

  private static final String SERVICE_NAME =
          "columnstore-policy-verification";

  private ColumnstorePolicyVerifier() {
  }

  public static void main(String[] args)
          throws IOException, InterruptedException {
    Mode mode = readMode(args);

    Instant startTime =
            mode == Mode.HISTORICAL
                    ? Instant.now().minus(Duration.ofDays(4))
                    : Instant.now();

    Instant endTime =
            startTime.plusMillis(5);

    String spanName =
            mode == Mode.HISTORICAL
                    ? "columnstore-historical-check"
                    : "columnstore-recent-check";

    String traceId =
            UUID.randomUUID()
                    .toString()
                    .replace("-", "");

    String spanId =
            UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 16);

    String requestBody =
            createRequestBody(
                    traceId,
                    spanId,
                    spanName,
                    toUnixNano(startTime),
                    toUnixNano(endTime)
            );

    HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

    HttpRequest request =
            HttpRequest.newBuilder(COLLECTOR_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header(
                            "Content-Type",
                            "application/json"
                    )
                    .header(
                            "Accept",
                            "application/json"
                    )
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    requestBody,
                                    StandardCharsets.UTF_8
                            )
                    )
                    .build();

    HttpResponse<String> response =
            httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );

    System.out.println();
    System.out.println(
            "AeroTrace columnstore policy verifier"
    );
    System.out.println("- mode: " + mode);
    System.out.println("- service.name: " + SERVICE_NAME);
    System.out.println("- span.name: " + spanName);
    System.out.println("- start_time: " + startTime);
    System.out.println("- trace_id: " + traceId);
    System.out.println("- span_id: " + spanId);
    System.out.println(
            "- HTTP status: " + response.statusCode()
    );
    System.out.println(
            "- response body: " + response.body()
    );

    if (response.statusCode() != 200) {
      throw new IllegalStateException(
              "Collector did not accept request. "
                      + "status="
                      + response.statusCode()
                      + ", body="
                      + response.body()
      );
    }
  }

  private static Mode readMode(String[] args) {
    if (args.length == 0) {
      return Mode.HISTORICAL;
    }

    try {
      return Mode.valueOf(
              args[0].trim().toUpperCase(Locale.ROOT)
      );
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
              "Mode must be historical or recent",
              exception
      );
    }
  }

  private static long toUnixNano(Instant instant) {
    return Math.addExact(
            Math.multiplyExact(
                    instant.getEpochSecond(),
                    1_000_000_000L
            ),
            instant.getNano()
    );
  }

  private static String createRequestBody(
          String traceId,
          String spanId,
          String spanName,
          long startTimeUnixNano,
          long endTimeUnixNano
  ) {
    return """
                {
                  "resourceSpans": [
                    {
                      "resource": {
                        "attributes": [
                          {
                            "key": "service.name",
                            "value": {
                              "stringValue": "%s"
                            }
                          }
                        ]
                      },
                      "scopeSpans": [
                        {
                          "scope": {
                            "name": "aerotrace.columnstore.verifier",
                            "version": "1.0.0"
                          },
                          "spans": [
                            {
                              "traceId": "%s",
                              "spanId": "%s",
                              "name": "%s",
                              "kind": 2,
                              "startTimeUnixNano": "%d",
                              "endTimeUnixNano": "%d",
                              "status": {
                                "code": 1
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(
            SERVICE_NAME,
            traceId,
            spanId,
            spanName,
            startTimeUnixNano,
            endTimeUnixNano
    );
  }

  private enum Mode {
    HISTORICAL,
    RECENT
  }
}