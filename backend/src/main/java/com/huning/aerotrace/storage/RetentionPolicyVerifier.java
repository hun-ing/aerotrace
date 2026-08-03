package com.huning.aerotrace.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class RetentionPolicyVerifier {

  private static final URI COLLECTOR_ENDPOINT =
          URI.create("http://localhost:4318/v1/traces");

  private static final String SERVICE_NAME =
          "retention-policy-verification";

  private static final String SPAN_NAME =
          "retention-expired-check";

  private RetentionPolicyVerifier() {
  }

  public static void main(String[] args)
          throws IOException, InterruptedException {
    Instant startTime =
            Instant.now().minus(Duration.ofDays(35));

    Instant endTime =
            startTime.plusMillis(5);

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
            "AeroTrace retention policy verifier"
    );
    System.out.println("- service.name: " + SERVICE_NAME);
    System.out.println("- span.name: " + SPAN_NAME);
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
                            "name": "aerotrace.retention.verifier",
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
            SPAN_NAME,
            startTimeUnixNano,
            endTimeUnixNano
    );
  }
}