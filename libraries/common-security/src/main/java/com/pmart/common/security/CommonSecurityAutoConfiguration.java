package com.pmart.common.security;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * common-security auto-configuration (Spring MVC).
 *
 * <ul>
 *   <li>Stateless JWT 리소스 서버. {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}(또는
 *       issuer-uri)는 서비스/환경 설정. JwtDecoder 가 없으면 체인을 만들지 않는다(설정 누락을 조용히 허용하지 않음 — 기동 시 Spring
 *       Security 기본 체인이 모든 요청을 막는다).
 *   <li>{@code permit-paths} 는 익명, {@code internal-paths} 는 TYPE_SERVICE, {@code guest-paths} 는 비회원
 *       토큰도 허용, 나머지는 인증 필요이고 비회원(TYPE_GUEST) 토큰은 403.
 *   <li>401/403 은 application/problem+json.
 *   <li>RestClient 에 X-Source-System·X-Correlation-Id·Bearer. {@code /internal/} 호출은 서비스 신원
 *       토큰(ADR-0011), 그 외는 사용자 토큰 전파.
 *   <li>{@code pmart.security.service-token.token-uri} 가 있으면 {@link ServiceTokenProvider} 등록(secret
 *       필수).
 *   <li>{@code pmart.security.authz.base-url} 이 있으면 {@link ActionAuthorizer} 등록(fail-closed).
 * </ul>
 */
// before: Spring Boot 의 기본 리소스서버 체인(@ConditionalOnDefaultWebSecurity)보다 먼저 등록해 그쪽이 물러나게 한다.
// JwtDecoder 는 Boot 자동구성(jwk-set-uri/issuer-uri)이 만들며, 없으면 체인 생성 시점에 명확히 실패한다(조용한 허용 금지).
@AutoConfiguration(
    before = OAuth2ResourceServerAutoConfiguration.class,
    after = RestClientAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(SecurityProperties.class)
public class CommonSecurityAutoConfiguration {

  /** 인증됐고 비회원(TYPE_GUEST)이 아님. */
  static final AuthorizationManager<RequestAuthorizationContext> NOT_GUEST =
      AuthorizationManagers.allOf(
          AuthenticatedAuthorizationManager.authenticated(),
          AuthorizationManagers.not(AuthorityAuthorizationManager.hasAuthority("TYPE_GUEST")));

  @Bean
  @ConditionalOnMissingBean
  public ProblemAuthHandlers problemAuthHandlers(ObjectProvider<ObjectMapper> mapper) {
    ObjectMapper m = mapper.getIfAvailable();
    return new ProblemAuthHandlers(m == null ? new ObjectMapper() : m);
  }

  @Bean
  @ConditionalOnMissingBean
  public PmartJwtAuthenticationConverter pmartJwtAuthenticationConverter() {
    return new PmartJwtAuthenticationConverter();
  }

  @Bean
  @ConditionalOnMissingBean(SecurityFilterChain.class)
  public SecurityFilterChain pmartSecurityFilterChain(
      HttpSecurity http,
      SecurityProperties props,
      ProblemAuthHandlers handlers,
      PmartJwtAuthenticationConverter converter)
      throws Exception {
    String[] permit = props.getPermitPaths().toArray(String[]::new);
    String[] internal = props.getInternalPaths().toArray(String[]::new);
    String[] guest = props.getGuestPaths().toArray(String[]::new);
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(permit).permitAll();
              auth.requestMatchers(internal).hasAuthority("TYPE_SERVICE");
              if (guest.length > 0) {
                auth.requestMatchers(guest).authenticated();
              }
              // 비회원 토큰(typ=GUEST)은 guest-paths 밖에서 403(회원 전용 API 보호)
              auth.anyRequest().access(NOT_GUEST);
            })
        .oauth2ResourceServer(
            rs ->
                rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(handlers)
                    .accessDeniedHandler(handlers))
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
        .cors(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  @ConditionalOnMissingBean(name = "serviceCallHeadersCustomizer")
  public RestClientCustomizer serviceCallHeadersCustomizer(
      Environment env, ObjectProvider<ServiceTokenProvider> serviceTokens) {
    return new ServiceCallHeadersCustomizer(
        env.getProperty("spring.application.name", "unknown"), serviceTokens);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "pmart.security.service-token", name = "token-uri")
  static class ServiceTokenClient {
    @Bean
    @ConditionalOnMissingBean
    public ServiceTokenProvider serviceTokenProvider(
        SecurityProperties props, Environment env, ObjectProvider<RestClient.Builder> builders) {
      SecurityProperties.ServiceToken t = props.getServiceToken();
      if (t.getClientSecret() == null || t.getClientSecret().isBlank()) {
        throw new IllegalStateException(
            "pmart.security.service-token.client-secret 이 비어 있다(PMART_SERVICE_CLIENT_SECRET, Secret Manager).");
      }
      String clientId =
          t.getClientId() == null || t.getClientId().isBlank()
              ? env.getRequiredProperty("spring.application.name")
              : t.getClientId();
      JdkClientHttpRequestFactory factory =
          new JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(t.getConnectTimeout()).build());
      factory.setReadTimeout(t.getReadTimeout());
      RestClient client =
          builders.getIfAvailable(RestClient::builder).clone().requestFactory(factory).build();
      return new ServiceTokenProvider(
          client,
          t.getTokenUri(),
          clientId,
          t.getClientSecret(),
          t.getRefreshSkew(),
          java.time.Clock.systemUTC());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "pmart.security.authz", name = "base-url")
  static class AuthzClient {
    @Bean
    @ConditionalOnMissingBean
    public ActionAuthorizer actionAuthorizer(
        SecurityProperties props, ObjectProvider<RestClient.Builder> builderProvider) {
      SecurityProperties.Authz a = props.getAuthz();
      if (a.getConnectTimeout().compareTo(Duration.ZERO) <= 0) {
        throw new IllegalStateException("pmart.security.authz.connect-timeout 은 양수여야 합니다.");
      }
      JdkClientHttpRequestFactory factory =
          new JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(a.getConnectTimeout()).build());
      factory.setReadTimeout(a.getReadTimeout());
      RestClient.Builder builder = builderProvider.getIfAvailable(RestClient::builder);
      RestClient client = builder.clone().baseUrl(a.getBaseUrl()).requestFactory(factory).build();
      return new ActionAuthorizer(client);
    }
  }
}
