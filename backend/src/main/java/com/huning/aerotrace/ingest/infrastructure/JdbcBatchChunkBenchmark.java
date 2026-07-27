package com.huning.aerotrace.ingest.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.ingest.application.SpanWriteResult;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class JdbcBatchChunkBenchmark {

  private static final int TOTAL_SPAN_COUNT = 5_000;
  private static final int MEASUREMENT_COUNT = 5;

  private static final List<Integer> CHUNK_SIZES =
          List.of(
                  50,
                  100,
                  250,
                  500,
                  1_000,
                  2_000,
                  5_000
          );

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final String SERVICE_NAME =
          "jdbc-chunk-benchmark-service";

  private static final Instant BASE_START_TIME =
          Instant.parse("2026-07-27T01:00:00Z");

  private static final String DEFAULT_DB_URL =
          "jdbc:postgresql://localhost:5432/aerotrace";

  private final JdbcTemplate jdbcTemplate;
  private final JdbcSpanWriter batchWriter;
  private final TransactionTemplate transactionTemplate;

  private JdbcBatchChunkBenchmark(
          JdbcTemplate jdbcTemplate,
          JdbcSpanWriter batchWriter,
          PlatformTransactionManager transactionManager
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.batchWriter = batchWriter;
    this.transactionTemplate =
            new TransactionTemplate(transactionManager);
  }

  public static void main(String[] args) {
    String benchmarkJdbcUrl =
            buildBenchmarkJdbcUrl();

    try (
            ConfigurableApplicationContext context =
                    new SpringApplicationBuilder(
                            BackendApplication.class
                    )
                            .web(WebApplicationType.NONE)
                            .properties(
                                    "spring.main.banner-mode=off",
                                    "logging.level.root=WARN",
                                    "spring.datasource.url="
                                            + benchmarkJdbcUrl
                            )
                            .run()
    ) {
      JdbcBatchChunkBenchmark benchmark =
              new JdbcBatchChunkBenchmark(
                      context.getBean(JdbcTemplate.class),
                      context.getBean(JdbcSpanWriter.class),
                      context.getBean(
                              PlatformTransactionManager.class
                      )
              );

      benchmark.run();
    }
  }

  private void run() {
    prepareTenantAndProject();

    List<ParsedSpan> spans =
            createSpans(TOTAL_SPAN_COUNT);

    Map<Integer, List<Long>> measurements =
            new LinkedHashMap<>();

    for (int chunkSize : CHUNK_SIZES) {
      measurements.put(
              chunkSize,
              new ArrayList<>()
      );
    }

    System.out.printf(
            "%nAeroTrace JDBC batch chunk 벤치마크%n"
                    + "- 총 Span 수: %,d%n"
                    + "- 측정 횟수: %d%n"
                    + "- chunk 크기: %s%n"
                    + "- reWriteBatchedInserts: false%n"
                    + "- 모든 chunk는 요청당 트랜잭션 1개%n"
                    + "- 측정 범위: JSON 직렬화 + 트랜잭션 + JDBC 저장%n"
                    + "- 저장 검증과 데이터 삭제는 측정에서 제외%n%n",
            TOTAL_SPAN_COUNT,
            MEASUREMENT_COUNT,
            CHUNK_SIZES
    );

    try {
      warmUp(spans);

      for (
              int round = 0;
              round < MEASUREMENT_COUNT;
              round++
      ) {
        List<Integer> executionOrder =
                new ArrayList<>(CHUNK_SIZES);

        if (round % 2 == 1) {
          Collections.reverse(executionOrder);
        }

        for (int chunkSize : executionOrder) {
          long elapsedNano =
                  measureChunkedBatch(
                          spans,
                          chunkSize
                  );

          measurements
                  .get(chunkSize)
                  .add(elapsedNano);
        }
      }

      printResults(measurements);
    } finally {
      cleanBenchmarkSpans();
    }
  }

  private void warmUp(
          List<ParsedSpan> spans
  ) {
    measureChunkedBatch(spans, 50);
    measureChunkedBatch(spans, 5_000);

    System.out.println("워밍업 완료");
  }

  private long measureChunkedBatch(
          List<ParsedSpan> spans,
          int chunkSize
  ) {
    cleanBenchmarkSpans();

    long startNano = System.nanoTime();

    transactionTemplate.executeWithoutResult(status -> {
      for (
              int fromIndex = 0;
              fromIndex < spans.size();
              fromIndex += chunkSize
      ) {
        int toIndex =
                Math.min(
                        fromIndex + chunkSize,
                        spans.size()
                );

        List<ParsedSpan> chunk =
                spans.subList(
                        fromIndex,
                        toIndex
                );

        SpanWriteResult result =
                batchWriter.insertBatch(
                        TENANT_ID,
                        PROJECT_ID,
                        chunk
                );

        validateWriteResult(
                result,
                chunk.size()
        );
      }
    });

    long elapsedNano =
            System.nanoTime() - startNano;

    verifyStoredCount(spans.size());

    return elapsedNano;
  }

  private void validateWriteResult(
          SpanWriteResult result,
          int expectedCount
  ) {
    if (
            result.insertedCount()
                    + result.unknownSuccessCount()
                    != expectedCount
    ) {
      throw new IllegalStateException(
              "Unexpected batch result: " + result
      );
    }

    if (result.duplicateCount() != 0) {
      throw new IllegalStateException(
              "Benchmark batch contained duplicates: "
                      + result
      );
    }
  }

  private void printResults(
          Map<Integer, List<Long>> measurements
  ) {
    long fullBatchMedian =
            median(
                    measurements.get(TOTAL_SPAN_COUNT)
            );

    System.out.println();

    for (
            Map.Entry<Integer, List<Long>> entry
            : measurements.entrySet()
    ) {
      int chunkSize = entry.getKey();
      List<Long> times = entry.getValue();

      long medianNano =
              median(times);

      double medianMillis =
              medianNano / 1_000_000.0;

      double throughput =
              TOTAL_SPAN_COUNT
                      / (
                      medianNano
                              / 1_000_000_000.0
              );

      int batchExecutionCount =
              divideRoundingUp(
                      TOTAL_SPAN_COUNT,
                      chunkSize
              );

      double relativeToFullBatch =
              (double) fullBatchMedian
                      / medianNano;

      System.out.printf(
              "[chunk %,d]%n"
                      + "- 각 측정: %s ms%n"
                      + "- 중앙값: %.3f ms%n"
                      + "- 처리량: %.0f spans/sec%n"
                      + "- batchUpdate 실행 횟수: %,d%n"
                      + "- chunk 5,000 대비 배율: %.3fx%n%n",
              chunkSize,
              formatMillis(times),
              medianMillis,
              throughput,
              batchExecutionCount,
              relativeToFullBatch
      );
    }
  }

  private int divideRoundingUp(
          int dividend,
          int divisor
  ) {
    return (
            dividend + divisor - 1
    ) / divisor;
  }

  private void prepareTenantAndProject() {
    jdbcTemplate.update(
            """
            INSERT INTO tenants (
                id,
                name,
                slug
            )
            VALUES (
                ?,
                'AeroTrace 로컬 개발',
                'aerotrace-local-dev'
            )
            ON CONFLICT (id)
            DO NOTHING
            """,
            TENANT_ID
    );

    jdbcTemplate.update(
            """
            INSERT INTO projects (
                id,
                tenant_id,
                name,
                slug
            )
            VALUES (
                ?,
                ?,
                'AeroTrace 로컬 프로젝트',
                'aerotrace-local-project'
            )
            ON CONFLICT (id)
            DO NOTHING
            """,
            PROJECT_ID,
            TENANT_ID
    );
  }

  private void cleanBenchmarkSpans() {
    jdbcTemplate.update(
            """
            DELETE FROM spans
            WHERE tenant_id = ?
              AND project_id = ?
              AND service_name = ?
            """,
            TENANT_ID,
            PROJECT_ID,
            SERVICE_NAME
    );
  }

  private void verifyStoredCount(
          int expectedCount
  ) {
    Long actualCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM spans
                    WHERE tenant_id = ?
                      AND project_id = ?
                      AND service_name = ?
                    """,
                    Long.class,
                    TENANT_ID,
                    PROJECT_ID,
                    SERVICE_NAME
            );

    if (
            actualCount == null
                    || actualCount != expectedCount
    ) {
      throw new IllegalStateException(
              "Expected "
                      + expectedCount
                      + " rows, but found "
                      + actualCount
      );
    }
  }

  private List<ParsedSpan> createSpans(
          int spanCount
  ) {
    List<ParsedSpan> spans =
            new ArrayList<>(spanCount);

    String traceId =
            "88888888888888888888888888888888";

    for (
            int index = 0;
            index < spanCount;
            index++
    ) {
      long sequence = index + 1L;

      String spanId =
              String.format(
                      "%016x",
                      sequence
              );

      Instant startTime =
              BASE_START_TIME.plusMillis(index);

      Instant endTime =
              startTime.plusMillis(1);

      spans.add(
              new ParsedSpan(
                      SERVICE_NAME,
                      Map.of(
                              "service.name",
                              SERVICE_NAME,
                              "benchmark.total_span_count",
                              spanCount
                      ),
                      Map.of(
                              "benchmark.index",
                              sequence,
                              "benchmark.type",
                              "batch-chunk"
                      ),
                      List.of(),
                      List.of(),
                      "jdbc-chunk-benchmark",
                      "1.0.0",
                      traceId,
                      spanId,
                      null,
                      "",
                      1,
                      "chunk-benchmark-span-"
                              + sequence,
                      (short) 2,
                      (short) 1,
                      "",
                      startTime,
                      endTime,
                      Duration.between(
                              startTime,
                              endTime
                      ).toNanos(),
                      0,
                      0,
                      0
              )
      );
    }

    return List.copyOf(spans);
  }

  private String formatMillis(
          List<Long> times
  ) {
    return times.stream()
            .map(nano ->
                    String.format(
                            "%.3f",
                            nano / 1_000_000.0
                    )
            )
            .toList()
            .toString();
  }

  private long median(
          List<Long> times
  ) {
    List<Long> sorted =
            new ArrayList<>(times);

    Collections.sort(sorted);

    int middle = sorted.size() / 2;

    if (sorted.size() % 2 == 1) {
      return sorted.get(middle);
    }

    return (
            sorted.get(middle - 1)
                    + sorted.get(middle)
    ) / 2;
  }

  private static String buildBenchmarkJdbcUrl() {
    String baseUrl = System.getenv()
            .getOrDefault(
                    "AEROTRACE_DB_URL",
                    DEFAULT_DB_URL
            );

    if (
            baseUrl.toLowerCase(Locale.ROOT)
                    .contains(
                            "rewritebatchedinserts="
                    )
    ) {
      throw new IllegalArgumentException(
              "Remove reWriteBatchedInserts from "
                      + "AEROTRACE_DB_URL"
      );
    }

    String separator =
            baseUrl.contains("?") ? "&" : "?";

    return baseUrl
            + separator
            + "reWriteBatchedInserts=false";
  }
}