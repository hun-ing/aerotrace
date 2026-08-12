package com.huning.aerotrace.ingest.infrastructure;

import com.huning.aerotrace.ingest.application.SpanWriteResult;
import com.huning.aerotrace.ingest.application.SpanWriter;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcSpanWriter implements SpanWriter {

  private static final Logger log =
          LoggerFactory.getLogger(JdbcSpanWriter.class);

  private final JdbcTemplate jdbcTemplate;
  private final JdbcSpanPersistenceSupport persistenceSupport;
  private final int batchSize;

  public JdbcSpanWriter(
          JdbcTemplate jdbcTemplate,
          JdbcSpanPersistenceSupport persistenceSupport,
          @Value(
                  "${aerotrace.ingest.jdbc.batch-size:1000}"
          )
          int batchSize
  ) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
              "JDBC Span batch size must be greater than zero"
      );
    }

    this.jdbcTemplate = jdbcTemplate;
    this.persistenceSupport = persistenceSupport;
    this.batchSize = batchSize;
  }

  @Override
  public SpanWriteResult insertBatch(
          UUID tenantId,
          UUID projectId,
          List<ParsedSpan> spans
  ) {
    if (spans.isEmpty()) {
      return SpanWriteResult.empty();
    }

    long writerStartedAt =
            System.nanoTime();

    long totalPrepareRowsNanos = 0L;
    long totalBatchUpdateNanos = 0L;

    List<Integer> chunkSizes =
            new ArrayList<>();

    List<Integer> rowCounts =
            new ArrayList<>();

    List<Long> chunkPrepareRowsNanos =
            new ArrayList<>();

    List<Long> chunkBatchUpdateNanos =
            new ArrayList<>();

    int insertedCount = 0;
    int duplicateCount = 0;
    int unknownSuccessCount = 0;
    int batchExecutionCount = 0;

    int fromIndex = 0;

    while (fromIndex < spans.size()) {
      int remainingCount =
              spans.size() - fromIndex;

      int currentBatchSize =
              Math.min(
                      batchSize,
                      remainingCount
              );

      int toIndex =
              fromIndex + currentBatchSize;

      List<ParsedSpan> chunk =
              spans.subList(
                      fromIndex,
                      toIndex
              );

      long prepareRowsStartedAt =
              System.nanoTime();

      List<JdbcSpanPersistenceSupport.PreparedSpanRow> rows =
              persistenceSupport.prepareRows(
                      tenantId,
                      projectId,
                      chunk
              );

      long prepareRowsNanos =
              System.nanoTime()
                      - prepareRowsStartedAt;

      totalPrepareRowsNanos +=
              prepareRowsNanos;

      long batchUpdateStartedAt =
              System.nanoTime();

      int[] updateCounts =
              executeBatch(rows);

      long batchUpdateNanos =
              System.nanoTime()
                      - batchUpdateStartedAt;

      totalBatchUpdateNanos +=
              batchUpdateNanos;

      SpanWriteResult chunkResult =
              classifyResult(
                      rows.size(),
                      updateCounts
              );

      insertedCount +=
              chunkResult.insertedCount();

      duplicateCount +=
              chunkResult.duplicateCount();

      unknownSuccessCount +=
              chunkResult.unknownSuccessCount();

      chunkSizes.add(
              currentBatchSize
      );

      rowCounts.add(
              rows.size()
      );

      chunkPrepareRowsNanos.add(
              prepareRowsNanos
      );

      chunkBatchUpdateNanos.add(
              batchUpdateNanos
      );

      batchExecutionCount++;
      fromIndex = toIndex;
    }

    SpanWriteResult result =
            new SpanWriteResult(
                    spans.size(),
                    insertedCount,
                    duplicateCount,
                    unknownSuccessCount
            );

    long writerTotalNanos =
            System.nanoTime()
                    - writerStartedAt;

    long writerOtherNanos =
            writerTotalNanos
                    - totalPrepareRowsNanos
                    - totalBatchUpdateNanos;

    log.info(
            "JDBC Span writer timing: requested={}, executions={}, writerTotalNanos={}, prepareRowsNanos={}, batchUpdateNanos={}, writerOtherNanos={}, chunkSizes={}, rowCounts={}, chunkPrepareRowsNanos={}, chunkBatchUpdateNanos={}",
            result.requestedCount(),
            batchExecutionCount,
            writerTotalNanos,
            totalPrepareRowsNanos,
            totalBatchUpdateNanos,
            writerOtherNanos,
            chunkSizes,
            rowCounts,
            chunkPrepareRowsNanos,
            chunkBatchUpdateNanos
    );

    log.debug(
            "JDBC Span batch completed: requested={}, batchSize={}, executions={}, inserted={}, duplicates={}, unknown={}",
            result.requestedCount(),
            batchSize,
            batchExecutionCount,
            result.insertedCount(),
            result.duplicateCount(),
            result.unknownSuccessCount()
    );

    return result;
  }

  private int[] executeBatch(
          List<JdbcSpanPersistenceSupport.PreparedSpanRow> rows
  ) {
    return jdbcTemplate.batchUpdate(
            JdbcSpanPersistenceSupport.INSERT_SQL,
            new BatchPreparedStatementSetter() {

              @Override
              public void setValues(
                      PreparedStatement statement,
                      int index
              ) throws SQLException {
                persistenceSupport.bind(
                        statement,
                        rows.get(index)
                );
              }

              @Override
              public int getBatchSize() {
                return rows.size();
              }
            }
    );
  }

  private SpanWriteResult classifyResult(
          int requestedCount,
          int[] updateCounts
  ) {
    int insertedCount = 0;
    int duplicateCount = 0;
    int unknownSuccessCount = 0;

    for (int updateCount : updateCounts) {
      if (updateCount > 0) {
        insertedCount++;
        continue;
      }

      if (updateCount == 0) {
        duplicateCount++;
        continue;
      }

      if (updateCount == Statement.SUCCESS_NO_INFO) {
        unknownSuccessCount++;
        continue;
      }

      if (updateCount == Statement.EXECUTE_FAILED) {
        throw new IllegalStateException(
                "A JDBC batch entry failed"
        );
      }

      throw new IllegalStateException(
              "Unexpected JDBC batch update count: "
                      + updateCount
      );
    }

    return new SpanWriteResult(
            requestedCount,
            insertedCount,
            duplicateCount,
            unknownSuccessCount
    );
  }
}