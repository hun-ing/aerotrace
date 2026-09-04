package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.UserSessionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcUserSessionStore
        implements UserSessionStore {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUserSessionStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int deleteByUserId(UUID userId) {
    return jdbcTemplate.update(
            """
            DELETE FROM aerotrace_session
            WHERE principal_name = ?
            """,
            userId.toString()
    );
  }

  @Override
  public int deleteAll() {
    return jdbcTemplate.update(
            "DELETE FROM aerotrace_session"
    );
  }
}
