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

public final class CollectorPersistentQueueVerifier {

  private static final URI COLLECTOR_TRACES_ENDPOINT =
          URI.create(
                  "http://localhost:4318/v1/traces"
          );

  private static final String SERVICE_NAME =
          "collector-persistent-queue-verification";

  private static final int SPAN_COUNT = 100;

  private CollectorPersistentQueueVerifier() {
  }

  public static void main(
          String[] args
  ) throws IOException, InterruptedException {
    String requestBody =
            createRequestBody();

    HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(3)
                    )
                    .build();

    HttpRequest request =
            HttpRequest.newBuilder(
                            COLLECTOR_TRACES_ENDPOINT
                    )
                    .timeout(
                            Duration.ofSeconds(10)
                    )
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

    System.out.println();
    System.out.println(
            "AeroTrace Collector persistent queue 실험"
    );
    System.out.println(
            "- Collector endpoint: "
                    + COLLECTOR_TRACES_ENDPOINT
    );
    System.out.println(
            "- service.name: "
                    + SERVICE_NAME
    );
    System.out.println(
            "- 전송 Span 수: "
                    + SPAN_COUNT
    );
    System.out.println(
            "- HTTP 상태: "
                    + response.statusCode()
    );
    System.out.println(
            "- 응답 본문: "
                    + response.body()
    );

    if (response.statusCode() != 200) {
      throw new IllegalStateException(
              "Collector did not accept the OTLP request. "
                      + "status="
                      + response.statusCode()
                      + ", body="
                      + response.body()
      );
    }
  }

  private static String createRequestBody() {
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
            new StringBuilder();

    for (
            int index = 0;
            index < SPAN_COUNT;
            index++
    ) {
      if (index > 0) {
        spans.append(',');
      }

      String traceId =
              String.format(
                      Locale.ROOT,
                      "%032x",
                      index + 1
              );

      String spanId =
              String.format(
                      Locale.ROOT,
                      "%016x",
                      index + 1
              );

      long startTimeUnixNano =
              baseStartTimeUnixNano
                      + index * 10_000_000L;

      long endTimeUnixNano =
              startTimeUnixNano
                      + 5_000_000L;

      spans.append(
              """
              {
                "traceId": "%s",
                "spanId": "%s",
                "name": "persistent-queue-span-%03d",
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
                      index + 1,
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
                            "name": "aerotrace.collector.persistent-queue-verifier",
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