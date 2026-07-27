package com.huning.aerotrace.ingest.infrastructure;

import com.huning.aerotrace.ingest.application.SpanWriteResult;
import com.huning.aerotrace.ingest.application.SpanWriter;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcSpanWriter implements SpanWriter {

  private final JdbcTemplate jdbcTemplate;
  private final JdbcSpanPersistenceSupport persistenceSupport;

  public JdbcSpanWriter(
          JdbcTemplate jdbcTemplate,
          JdbcSpanPersistenceSupport persistenceSupport
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.persistenceSupport = persistenceSupport;
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

    List<JdbcSpanPersistenceSupport.PreparedSpanRow> rows =
            persistenceSupport.prepareRows(
                    tenantId,
                    projectId,
                    spans
            );

    int[] updateCounts = jdbcTemplate.batchUpdate(
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

    return classifyResult(
            rows.size(),
            updateCounts
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