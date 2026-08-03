package com.huning.aerotrace.collector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

public final class CollectorQueueLoadVerifier {

  private static final URI COLLECTOR_TRACES_ENDPOINT =
          URI.create("http://localhost:4318/v1/traces");

  private static final String SERVICE_NAME =
          "collector-queue-load-verification";

  private static final int DEFAULT_TOTAL_SPANS = 10_000;

  private static final int DEFAULT_SPANS_PER_REQUEST = 1_000;

  private CollectorQueueLoadVerifier() {
  }

  public static void main(String[] args)
          throws IOException, InterruptedException {
    int totalSpans =
            readPositiveInt(
                    args,
                    0,
                    DEFAULT_TOTAL_SPANS,
                    "totalSpans"
            );

    int spansPerRequest =
            readPositiveInt(
                    args,
                    1,
                    DEFAULT_SPANS_PER_REQUEST,
                    "spansPerRequest"
            );

    if (spansPerRequest > 5_000) {
      throw new IllegalArgumentException(
              "spansPerRequest must not exceed 5000"
      );
    }

    HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

    long runNonce =
            System.currentTimeMillis();

    int acceptedSpans = 0;
    int requestCount = 0;

    long startedAt =
            System.nanoTime();

    while (acceptedSpans < totalSpans) {
      int currentRequestSpanCount =
              Math.min(
                      spansPerRequest,
                      totalSpans - acceptedSpans
              );

      String requestBody =
              createRequestBody(
                      runNonce,
                      acceptedSpans,
                      currentRequestSpanCount
              );

      HttpRequest request =
              HttpRequest.newBuilder(
                              COLLECTOR_TRACES_ENDPOINT
                      )
                      .timeout(Duration.ofSeconds(30))
                      .header(
                              "Content-Type",
                              "application/json"
                      )
                      .header(
                              "Accept",
                              "application/json"
                      )
                      .POST(
                              HttpRequest.BodyPublishers
                                      .ofString(
                                              requestBody,
                                              StandardCharsets.UTF_8
                                      )
                      )
                      .build();

      HttpResponse<String> response =
              httpClient.send(
                      request,
                      HttpResponse.BodyHandlers
                              .ofString(
                                      StandardCharsets.UTF_8
                              )
              );

      requestCount++;

      if (response.statusCode() != 200) {
        throw new IllegalStateException(
                "Collector request failed. "
                        + "requestCount="
                        + requestCount
                        + ", acceptedSpans="
                        + acceptedSpans
                        + ", status="
                        + response.statusCode()
                        + ", body="
                        + response.body()
        );
      }

      acceptedSpans += currentRequestSpanCount;

      System.out.printf(
              Locale.ROOT,
              "요청 %d 완료: 이번 %d개, 누적 %d/%d%n",
              requestCount,
              currentRequestSpanCount,
              acceptedSpans,
              totalSpans
      );
    }

    long elapsedNanos =
            System.nanoTime() - startedAt;

    double elapsedSeconds =
            elapsedNanos / 1_000_000_000.0;

    double acceptedSpansPerSecond =
            acceptedSpans / elapsedSeconds;

    System.out.println();
    System.out.println(
            "AeroTrace Collector queue 부하 전송 완료"
    );
    System.out.println(
            "- service.name: " + SERVICE_NAME
    );
    System.out.println(
            "- 요청 수: " + requestCount
    );
    System.out.println(
            "- 전송 Span 수: " + acceptedSpans
    );

    System.out.printf(
            Locale.ROOT,
            "- Collector 수신 시간: %.3f초%n",
            elapsedSeconds
    );

    System.out.printf(
            Locale.ROOT,
            "- Collector 수신 처리량: %.1f spans/s%n",
            acceptedSpansPerSecond
    );
  }

  private static int readPositiveInt(
          String[] args,
          int index,
          int defaultValue,
          String argumentName
  ) {
    if (args.length <= index) {
      return defaultValue;
    }

    int value;

    try {
      value =
              Integer.parseInt(args[index]);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
              argumentName
                      + " must be an integer",
              exception
      );
    }

    if (value <= 0) {
      throw new IllegalArgumentException(
              argumentName
                      + " must be greater than zero"
      );
    }

    return value;
  }

  private static String createRequestBody(
          long runNonce,
          int globalStartIndex,
          int spanCount
  ) {
    Instant now =
            Instant.now();

    long baseStartTimeUnixNano =
            Math.addExact(
                    Math.multiplyExact(
                            now.getEpochSecond(),
                            1_000_000_000L
                    ),
                    now.getNano()
            );

    StringBuilder spans =
            new StringBuilder(
                    spanCount * 350
            );

    for (int index = 0; index < spanCount; index++) {
      if (index > 0) {
        spans.append(',');
      }

      int globalIndex =
              globalStartIndex + index;

      String traceId =
              String.format(
                      Locale.ROOT,
                      "%016x%016x",
                      runNonce,
                      globalIndex + 1L
              );

      String spanId =
              String.format(
                      Locale.ROOT,
                      "%016x",
                      globalIndex + 1L
              );

      long startTimeUnixNano =
              baseStartTimeUnixNano
                      + index * 1_000_000L;

      long endTimeUnixNano =
              startTimeUnixNano
                      + 500_000L;

      spans.append(
              """
              {
                "traceId": "%s",
                "spanId": "%s",
                "name": "queue-load-span-%05d",
                "kind": 2,
                "startTimeUnixNano": "%d",
                "endTimeUnixNano": "%d",
                "status": {
                  "code": 1
                }
              }
              """.formatted(
                      traceId,
                      spanId,
                      globalIndex + 1,
                      startTimeUnixNano,
                      endTimeUnixNano
              )
      );
    }

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
                            "name": "aerotrace.collector.queue-load-verifier",
                            "version": "1.0.0"
                          },
                          "spans": [
                            %s
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(
            SERVICE_NAME,
            spans
    );
  }
}