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

import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JdbcSpanWriteBenchmark {

  private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/aerotrace";

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final String SERVICE_NAME =
          "jdbc-benchmark-service";

  private static final Instant BASE_START_TIME =
          Instant.parse("2026-07-27T00:00:00Z");

  private final JdbcTemplate jdbcTemplate;
  private final JdbcSpanWriter batchWriter;
  private final JdbcSpanPersistenceSupport persistenceSupport;
  private final TransactionTemplate transactionTemplate;

  private JdbcSpanWriteBenchmark(
          JdbcTemplate jdbcTemplate,
          JdbcSpanWriter batchWriter,
          JdbcSpanPersistenceSupport persistenceSupport,
          PlatformTransactionManager transactionManager
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.batchWriter = batchWriter;
    this.persistenceSupport = persistenceSupport;
    this.transactionTemplate =
            new TransactionTemplate(transactionManager);
  }

  public static void main(String[] args) {
    int spanCount = parsePositiveArgument(
            args,
            0,
            1_000,
            "spanCount"
    );

    int measurementCount = parsePositiveArgument(
            args,
            1,
            7,
            "measurementCount"
    );

    boolean rewriteBatchedInserts =
            parseBooleanArgument(
                    args,
                    2,
                    false,
                    "rewriteBatchedInserts"
            );

    String benchmarkJdbcUrl =
            buildBenchmarkJdbcUrl(
                    rewriteBatchedInserts
            );

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
      JdbcSpanWriteBenchmark benchmark =
              new JdbcSpanWriteBenchmark(
                      context.getBean(JdbcTemplate.class),
                      context.getBean(JdbcSpanWriter.class),
                      context.getBean(
                              JdbcSpanPersistenceSupport.class
                      ),
                      context.getBean(
                              PlatformTransactionManager.class
                      )
              );

      benchmark.run(
              spanCount,
              measurementCount,
              rewriteBatchedInserts
      );
    }
  }

  private void run(
          int spanCount,
          int measurementCount,
          boolean rewriteBatchedInserts
  ) {
    prepareTenantAndProject();

    List<ParsedSpan> spans =
            createSpans(spanCount);

    System.out.printf(
            "%nAeroTrace JDBC 저장 벤치마크%n"
                    + "- Span 수: %,d%n"
                    + "- 측정 횟수: %d%n"
                    + "- reWriteBatchedInserts: %s%n"
                    + "- 측정 범위: JSON 직렬화 + 트랜잭션 + JDBC 저장%n"
                    + "- 저장 결과 검증과 테스트 데이터 삭제는 측정에서 제외%n%n",
            spanCount,
            measurementCount,
            rewriteBatchedInserts
    );

    warmUp(spans);

    List<Long> singleTimes = new ArrayList<>();
    List<Long> batchTimes = new ArrayList<>();

    for (int index = 0;
         index < measurementCount;
         index++) {

      // 실행 순서로 인한 캐시 편향을 줄이기 위해
      // 매 회차마다 먼저 실행하는 방식을 바꾼다.
      if (index % 2 == 0) {
        singleTimes.add(measureSingle(spans));
        batchTimes.add(measureBatch(spans));
      } else {
        batchTimes.add(measureBatch(spans));
        singleTimes.add(measureSingle(spans));
      }
    }

    long singleMedian = median(singleTimes);
    long batchMedian = median(batchTimes);

    printResult(
            "단건 반복",
            spanCount,
            singleTimes,
            singleMedian,
            spanCount
    );

    printResult(
            "JDBC batch",
            spanCount,
            batchTimes,
            batchMedian,
            1
    );

    double speedup =
            (double) singleMedian / batchMedian;

    System.out.printf(
            "%n중앙값 기준 batch 배율: %.2fx%n",
            speedup
    );

    cleanBenchmarkSpans();
  }

  private void warmUp(List<ParsedSpan> spans) {
    measureSingle(spans);
    measureBatch(spans);

    System.out.println(
            "워밍업 완료"
    );
  }

  private long measureSingle(
          List<ParsedSpan> spans
  ) {
    cleanBenchmarkSpans();

    long startNano = System.nanoTime();

    transactionTemplate.executeWithoutResult(status -> {
      List<JdbcSpanPersistenceSupport.PreparedSpanRow> rows =
              persistenceSupport.prepareRows(
                      TENANT_ID,
                      PROJECT_ID,
                      spans
              );

      for (
              JdbcSpanPersistenceSupport.PreparedSpanRow row
              : rows
      ) {
        int affectedRows = jdbcTemplate.update(
                JdbcSpanPersistenceSupport.INSERT_SQL,
                statement ->
                        persistenceSupport.bind(
                                statement,
                                row
                        )
        );

        if (affectedRows != 1) {
          throw new IllegalStateException(
                  "Expected one inserted row, but got "
                          + affectedRows
          );
        }
      }
    });

    long elapsedNano =
            System.nanoTime() - startNano;

    verifyStoredCount(spans.size());

    return elapsedNano;
  }

  private long measureBatch(
          List<ParsedSpan> spans
  ) {
    cleanBenchmarkSpans();

    long startNano = System.nanoTime();

    SpanWriteResult result =
            transactionTemplate.execute(status ->
                    batchWriter.insertBatch(
                            TENANT_ID,
                            PROJECT_ID,
                            spans
                    )
            );

    long elapsedNano =
            System.nanoTime() - startNano;

    if (result == null) {
      throw new IllegalStateException(
              "Batch transaction returned no result"
      );
    }

    if (
            result.insertedCount()
                    + result.unknownSuccessCount()
                    != spans.size()
    ) {
      throw new IllegalStateException(
              "Unexpected batch result: " + result
      );
    }

    if (result.duplicateCount() != 0) {
      throw new IllegalStateException(
              "Benchmark batch contained duplicates"
      );
    }

    verifyStoredCount(spans.size());

    return elapsedNano;
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
                'AeroTrace 벤치마크',
                'aerotrace-benchmark'
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
                'JDBC 저장 벤치마크',
                'jdbc-write-benchmark'
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
    Long actualCount = jdbcTemplate.queryForObject(
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
            "99999999999999999999999999999999";

    for (int index = 0;
         index < spanCount;
         index++) {

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
                              "benchmark.span_count",
                              spanCount
                      ),
                      Map.of(
                              "benchmark.index",
                              sequence,
                              "benchmark.mode",
                              "persistence-only"
                      ),
                      List.of(),
                      List.of(),
                      "jdbc-benchmark",
                      "1.0.0",
                      traceId,
                      spanId,
                      null,
                      "",
                      1,
                      "benchmark-span-" + sequence,
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

  private void printResult(
          String label,
          int spanCount,
          List<Long> times,
          long medianNano,
          int jdbcTemplateCallCount
  ) {
    double medianMillis =
            medianNano / 1_000_000.0;

    double throughput =
            spanCount
                    / (medianNano / 1_000_000_000.0);

    System.out.printf(
            "%n[%s]%n"
                    + "- 각 측정: %s ms%n"
                    + "- 중앙값: %.3f ms%n"
                    + "- 처리량: %.0f spans/sec%n"
                    + "- JdbcTemplate 호출 수: %,d%n",
            label,
            formatMillis(times),
            medianMillis,
            throughput,
            jdbcTemplateCallCount
    );
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

  private static String buildBenchmarkJdbcUrl(
          boolean rewriteBatchedInserts
  ) {
    String baseUrl = System.getenv()
            .getOrDefault(
                    "AEROTRACE_DB_URL",
                    DEFAULT_DB_URL
            );

    if (
            baseUrl.toLowerCase(Locale.ROOT)
                    .contains("rewritebatchedinserts=")
    ) {
      throw new IllegalArgumentException(
              "Remove reWriteBatchedInserts from "
                      + "AEROTRACE_DB_URL during the benchmark"
      );
    }

    String separator =
            baseUrl.contains("?") ? "&" : "?";

    return baseUrl
            + separator
            + "reWriteBatchedInserts="
            + rewriteBatchedInserts;
  }

  private static boolean parseBooleanArgument(
          String[] args,
          int index,
          boolean defaultValue,
          String argumentName
  ) {
    if (args.length <= index) {
      return defaultValue;
    }

    return switch (
            args[index]
                    .trim()
                    .toLowerCase(Locale.ROOT)
            ) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException(
              argumentName
                      + " must be true or false"
      );
    };
  }

  private static int parsePositiveArgument(
          String[] args,
          int index,
          int defaultValue,
          String argumentName
  ) {
    if (args.length <= index) {
      return defaultValue;
    }

    final int value;

    try {
      value = Integer.parseInt(args[index]);
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
}