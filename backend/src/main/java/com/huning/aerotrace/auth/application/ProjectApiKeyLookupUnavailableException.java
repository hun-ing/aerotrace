package com.huning.aerotrace.auth.application;

public final class ProjectApiKeyLookupUnavailableException
        extends RuntimeException {

  public ProjectApiKeyLookupUnavailableException(
          Throwable cause
  ) {
    super(
            "Project API Key lookup is "
                    + "temporarily unavailable",
            cause
    );
  }
}