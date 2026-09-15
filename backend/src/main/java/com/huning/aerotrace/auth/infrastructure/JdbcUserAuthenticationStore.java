package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.UserAuthenticationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserAuthenticationStore
        implements UserAuthenticationStore {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUserAuthenticationStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void lockProviderSubject(
          String provider,
          String providerSubject
  ) {
    long lockKey = providerSubjectLockKey(
            provider,
            providerSubject
    );

    jdbcTemplate.query(
            "SELECT pg_advisory_xact_lock(?)",
            resultSet -> {
              // The transaction-scoped advisory lock is the result.
            },
            lockKey
    );
  }

  @Override
  public Optional<StoredUserIdentity> findByProviderSubject(
          String provider,
          String providerSubject
  ) {
    List<StoredUserIdentity> identities =
            jdbcTemplate.query(
                    """
                    SELECT users.id AS user_id,
                           identities.id AS identity_id,
                           users.status AS user_status,
                           identities.provider_subject
                    FROM user_identities identities
                    JOIN app_users users
                      ON users.id = identities.user_id
                    WHERE identities.provider = ?
                      AND identities.provider_subject = ?
                    """,
                    (
                            resultSet,
                            rowNumber
                    ) -> new StoredUserIdentity(
                            resultSet.getObject(
                                    "user_id",
                                    UUID.class
                            ),
                            resultSet.getObject(
                                    "identity_id",
                                    UUID.class
                            ),
                            UserStatus.valueOf(
                                    resultSet.getString(
                                            "user_status"
                                    )
                            ),
                            resultSet.getString(
                                    "provider_subject"
                            )
                    ),
                    provider,
                    providerSubject
            );

    if (identities.size() > 1) {
      throw new IllegalStateException(
              "Provider subject returned duplicate identities"
      );
    }

    return identities.stream().findFirst();
  }

  @Override
  public void createUser(
          NewUser user
  ) {
    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO app_users (
                        id,
                        display_name,
                        avatar_url,
                        status,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                    """,
                    user.id(),
                    user.displayName(),
                    user.avatarUrl(),
                    utc(user.createdAt()),
                    utc(user.createdAt())
            );

    requireExactlyOneRow(
            insertedRows,
            "User creation"
    );
  }

  @Override
  public void createIdentity(
          NewUserIdentity identity
  ) {
    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO user_identities (
                        id,
                        user_id,
                        provider,
                        provider_subject,
                        provider_login,
                        created_at,
                        last_seen_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    identity.id(),
                    identity.userId(),
                    identity.provider(),
                    identity.providerSubject(),
                    identity.providerLogin(),
                    utc(identity.createdAt()),
                    utc(identity.createdAt())
            );

    requireExactlyOneRow(
            insertedRows,
            "User identity creation"
    );
  }

  @Override
  public int updateLoginMetadata(
          UUID userId,
          UUID identityId,
          String displayName,
          String avatarUrl,
          String providerLogin,
          java.time.Instant seenAt
  ) {
    OffsetDateTime seenAtUtc = utc(seenAt);

    int updatedUsers =
            jdbcTemplate.update(
                    """
                    UPDATE app_users
                    SET display_name = ?,
                        avatar_url = ?,
                        updated_at = ?,
                        last_login_at = ?
                    WHERE id = ?
                      AND status = 'ACTIVE'
                    """,
                    displayName,
                    avatarUrl,
                    seenAtUtc,
                    seenAtUtc,
                    userId
            );

    int updatedIdentities =
            jdbcTemplate.update(
                    """
                    UPDATE user_identities
                    SET provider_login = ?,
                        last_seen_at = ?
                    WHERE id = ?
                      AND user_id = ?
                    """,
                    providerLogin,
                    seenAtUtc,
                    identityId,
                    userId
            );

    return updatedUsers + updatedIdentities;
  }

  private long providerSubjectLockKey(
          String provider,
          String providerSubject
  ) {
    try {
      MessageDigest digest = MessageDigest.getInstance(
              "SHA-256"
      );

      byte[] hash = digest.digest(
              (provider + "\u0000" + providerSubject)
                      .getBytes(StandardCharsets.UTF_8)
      );

      return ByteBuffer.wrap(hash).getLong();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
              "SHA-256 is unavailable",
              exception
      );
    }
  }

  private OffsetDateTime utc(
          java.time.Instant instant
  ) {
    return OffsetDateTime.ofInstant(
            instant,
            ZoneOffset.UTC
    );
  }

  private void requireExactlyOneRow(
          int changedRows,
          String operation
  ) {
    if (changedRows != 1) {
      throw new IllegalStateException(
              operation + " did not change exactly one row"
      );
    }
  }
}
