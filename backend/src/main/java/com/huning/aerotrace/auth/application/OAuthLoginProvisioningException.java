package com.huning.aerotrace.auth.application;

public class OAuthLoginProvisioningException
        extends RuntimeException {

  public OAuthLoginProvisioningException() {
    super("OAuth login is invalid or unavailable");
  }

  public OAuthLoginProvisioningException(Throwable cause) {
    super("OAuth login is invalid or unavailable", cause);
  }
}
