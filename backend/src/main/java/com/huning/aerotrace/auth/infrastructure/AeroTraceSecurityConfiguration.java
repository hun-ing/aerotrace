package com.huning.aerotrace.auth.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ClientSettings;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import jakarta.servlet.http.HttpServletResponse;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(
        AeroTraceAuthProperties.class
)
public class AeroTraceSecurityConfiguration {

  @Bean
  Clock applicationClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnProperty(
          name = "aerotrace.auth.enabled",
          havingValue = "false",
          matchIfMissing = true
  )
  SecurityFilterChain disabledAuthFilterChain(
          HttpSecurity http
  ) throws Exception {
    http
            .authorizeHttpRequests(
                    authorization -> authorization
                            .requestMatchers(
                                    "/v1/traces",
                                    "/api/v1/traces/**",
                                    "/actuator/health",
                                    "/actuator/metrics/**"
                            ).permitAll()
                            .anyRequest().denyAll()
            )
            .csrf(
                    csrf -> csrf.ignoringRequestMatchers(
                            "/v1/traces"
                    )
            )
            .sessionManagement(
                    session -> session
                            .sessionCreationPolicy(
                                    SessionCreationPolicy.STATELESS
                            )
            )
            .requestCache(requestCache -> requestCache.disable())
            .formLogin(formLogin -> formLogin.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .logout(logout -> logout.disable());

    return http.build();
  }

  @Bean
  @ConditionalOnProperty(
          name = "aerotrace.auth.enabled",
          havingValue = "true"
  )
  ClientRegistrationRepository clientRegistrationRepository(
          AeroTraceAuthProperties properties
  ) {
    AeroTraceAuthProperties.ValidatedSettings settings =
            properties.validateEnabled();

    ClientRegistration registration =
            CommonOAuth2Provider.GITHUB
                    .getBuilder("github")
                    .clientId(settings.clientId())
                    .clientSecret(settings.clientSecret())
                    .scope(settings.allowedOauthScopes())
                    .redirectUri(
                            settings.publicOrigin()
                                    + "/login/oauth2/code/github"
                    )
                    .clientSettings(
                            ClientSettings.builder()
                                    .requireProofKey(true)
                                    .build()
                    )
                    .build();

    return new InMemoryClientRegistrationRepository(
            registration
    );
  }

  @Bean
  @ConditionalOnProperty(
          name = "aerotrace.auth.enabled",
          havingValue = "true"
  )
  CookieSerializer sessionCookieSerializer(
          AeroTraceAuthProperties properties
  ) {
    AeroTraceAuthProperties.ValidatedSettings settings =
            properties.validateEnabled();
    DefaultCookieSerializer serializer =
            new DefaultCookieSerializer();

    serializer.setCookieName(settings.cookieName());
    serializer.setCookiePath("/");
    serializer.setUseSecureCookie(
            settings.secureCookie()
    );
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Lax");

    return serializer;
  }

  @Bean
  @ConditionalOnProperty(
          name = "aerotrace.auth.enabled",
          havingValue = "true"
  )
  SecurityFilterChain enabledAuthFilterChain(
          HttpSecurity http,
          AeroTraceAuthProperties properties,
          RequestScopedOAuth2AuthorizedClientRepository
                  authorizedClientRepository,
          OAuthLoginSuccessHandler successHandler,
          OAuthLoginFailureHandler failureHandler,
          ExpectedOriginFilter expectedOriginFilter,
          AuthenticationRateLimitFilter
                  authenticationRateLimitFilter,
          AuthenticatedSessionValidationFilter
                  authenticatedSessionValidationFilter,
          ObjectProvider<OAuth2AccessTokenResponseClient<
                  OAuth2AuthorizationCodeGrantRequest>>
                  accessTokenResponseClientProvider,
          ObjectProvider<OAuth2UserService<
                  OAuth2UserRequest,
                  OAuth2User>> oauth2UserServiceProvider
  ) throws Exception {
    properties.validateEnabled();

    http
            .authorizeHttpRequests(
                    authorization -> authorization
                            .requestMatchers(
                                    "/v1/traces",
                                    "/api/v1/traces/**",
                                    "/actuator/health",
                                    "/actuator/metrics/**",
                                    "/oauth2/authorization/github",
                                    "/login/oauth2/code/github",
                                    "/api/v1/auth/csrf",
                                    "/api/v1/onboarding/intents"
                            ).permitAll()
                            .requestMatchers(HttpMethod.GET,
                                    "/api/v1/tenants",
                                    "/api/v1/tenants/*/projects",
                                    "/api/v1/projects/*",
                                    "/api/v1/projects/*/traces",
                                    "/api/v1/projects/*/traces/*"
                            ).authenticated()
                            .requestMatchers(
                                    "/api/v1/me",
                                    "/api/v1/logout"
                            ).authenticated()
                            .anyRequest().denyAll()
            )
            .csrf(
                    csrf -> csrf.ignoringRequestMatchers(
                            "/v1/traces"
                    )
            )
            .sessionManagement(
                    session -> session
                            .sessionFixation(
                                    fixation -> fixation
                                            .changeSessionId()
                            )
            )
            .requestCache(requestCache -> requestCache.disable())
            .oauth2Login(oauth -> {
              oauth.authorizedClientRepository(
                              authorizedClientRepository
                      )
                      .securityContextRepository(
                              new RequestAttributeSecurityContextRepository()
                      )
                      .successHandler(successHandler)
                      .failureHandler(failureHandler);

              OAuth2AccessTokenResponseClient<
                      OAuth2AuthorizationCodeGrantRequest>
                      accessTokenResponseClient =
                      accessTokenResponseClientProvider
                              .getIfUnique();

              if (accessTokenResponseClient != null) {
                oauth.tokenEndpoint(
                        token -> token
                                .accessTokenResponseClient(
                                        accessTokenResponseClient
                                )
                );
              }

              OAuth2UserService<
                      OAuth2UserRequest,
                      OAuth2User> oauth2UserService =
                      oauth2UserServiceProvider.getIfUnique();

              if (oauth2UserService != null) {
                oauth.userInfoEndpoint(
                        userInfo -> userInfo.userService(
                                oauth2UserService
                        )
                );
              }
            })
            .logout(
                    logout -> logout
                            .logoutUrl("/api/v1/logout")
                            .clearAuthentication(true)
                            .invalidateHttpSession(true)
                            .logoutSuccessHandler(
                                    (
                                            request,
                                            response,
                                            authentication
                                    ) -> {
                                      response.setStatus(
                                              HttpServletResponse.SC_NO_CONTENT
                                      );
                                      response.setHeader(
                                              "Cache-Control",
                                              "no-store"
                                      );
                                      response.setHeader(
                                              "Pragma",
                                              "no-cache"
                                      );
                                    }
                            )
            )
            .exceptionHandling(
                    exceptions -> exceptions
                            .authenticationEntryPoint(
                                    (
                                            request,
                                            response,
                                            exception
                                    ) -> {
                                      response.setStatus(
                                              HttpServletResponse.SC_UNAUTHORIZED
                                      );
                                      response.setHeader(
                                              "Cache-Control",
                                              "no-store"
                                      );
                                    }
                            )
                            .accessDeniedHandler(
                                    (
                                            request,
                                            response,
                                            exception
                                    ) -> {
                                      response.setStatus(
                                              HttpServletResponse.SC_FORBIDDEN
                                      );
                                      response.setHeader(
                                              "Cache-Control",
                                              "no-store"
                                      );
                                    }
                            )
            )
            .addFilterBefore(
                    expectedOriginFilter,
                    CsrfFilter.class
            )
            .addFilterAfter(
                    authenticationRateLimitFilter,
                    CsrfFilter.class
            )
            .addFilterBefore(
                    authenticatedSessionValidationFilter,
                    AuthorizationFilter.class
            )
            .formLogin(formLogin -> formLogin.disable())
            .httpBasic(httpBasic -> httpBasic.disable());

    return http.build();
  }
}
