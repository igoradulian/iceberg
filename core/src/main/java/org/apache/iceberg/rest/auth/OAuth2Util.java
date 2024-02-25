/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.rest.auth;

import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.base.Joiner;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Splitter;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.rest.ErrorHandlers;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.ResourcePaths;
import org.apache.iceberg.rest.responses.OAuthTokenResponse;
import org.apache.iceberg.util.JsonUtil;
import org.apache.iceberg.util.Pair;
import org.apache.iceberg.util.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OAuth2Util {
  private OAuth2Util() {}

  private static final Logger LOG = LoggerFactory.getLogger(OAuth2Util.class);

  // valid scope tokens are from ascii 0x21 to 0x7E, excluding 0x22 (") and 0x5C (\)
  private static final Pattern VALID_SCOPE_TOKEN = Pattern.compile("^[!-~&&[^\"\\\\]]+$");
  private static final Splitter SCOPE_DELIMITER = Splitter.on(" ");
  private static final Joiner SCOPE_JOINER = Joiner.on(" ");

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String BASIC_PREFIX = "Basic ";

  private static final Splitter CREDENTIAL_SPLITTER = Splitter.on(":").limit(2).trimResults();
  private static final String GRANT_TYPE = "grant_type";
  private static final String CLIENT_CREDENTIALS = "client_credentials";
  private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
  private static final String SCOPE = "scope";
  private static final String CATALOG = "catalog";

  // Client credentials flow
  private static final String CLIENT_ID = "client_id";
  private static final String CLIENT_SECRET = "client_secret";

  // Token exchange flow
  private static final String SUBJECT_TOKEN = "subject_token";
  private static final String SUBJECT_TOKEN_TYPE = "subject_token_type";
  private static final String ACTOR_TOKEN = "actor_token";
  private static final String ACTOR_TOKEN_TYPE = "actor_token_type";
  private static final Set<String> VALID_TOKEN_TYPES =
      Sets.newHashSet(
          OAuth2Properties.ACCESS_TOKEN_TYPE,
          OAuth2Properties.REFRESH_TOKEN_TYPE,
          OAuth2Properties.ID_TOKEN_TYPE,
          OAuth2Properties.SAML1_TOKEN_TYPE,
          OAuth2Properties.SAML2_TOKEN_TYPE,
          OAuth2Properties.JWT_TOKEN_TYPE);

  // response serialization
  private static final String ACCESS_TOKEN = "access_token";
  private static final String TOKEN_TYPE = "token_type";
  private static final String EXPIRES_IN = "expires_in";
  private static final String ISSUED_TOKEN_TYPE = "issued_token_type";
  private static final String REFRESH_TOKEN = "refresh_token";

  public static Map<String, String> authHeaders(String token) {
    if (token != null) {
      return ImmutableMap.of(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
    } else {
      return ImmutableMap.of();
    }
  }

  public static Map<String, String> basicAuthHeaders(String credential) {
    if (credential != null) {
      return ImmutableMap.of(
          AUTHORIZATION_HEADER,
          BASIC_PREFIX
              + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
    } else {
      return ImmutableMap.of();
    }
  }

  public static boolean isValidScopeToken(String scopeToken) {
    return VALID_SCOPE_TOKEN.matcher(scopeToken).matches();
  }

  public static List<String> parseScope(String scope) {
    return SCOPE_DELIMITER.splitToList(scope);
  }

  public static String toScope(Iterable<String> scopes) {
    return SCOPE_JOINER.join(scopes);
  }

  private static OAuthTokenResponse refreshToken(
      RESTClient client,
      Map<String, String> headers,
      String subjectToken,
      String subjectTokenType,
      String scope,
      String oauth2ServerUri) {
    Map<String, String> request =
        tokenExchangeRequest(
            subjectToken,
            subjectTokenType,
            scope != null ? ImmutableList.of(scope) : ImmutableList.of());

    OAuthTokenResponse response =
        client.postForm(
            oauth2ServerUri,
            request,
            OAuthTokenResponse.class,
            headers,
            ErrorHandlers.oauthErrorHandler());
    response.validate();

    return response;
  }

  public static OAuthTokenResponse exchangeToken(
      RESTClient client,
      Map<String, String> headers,
      String subjectToken,
      String subjectTokenType,
      String actorToken,
      String actorTokenType,
      String scope,
      String oauth2ServerUri) {
    Map<String, String> request =
        tokenExchangeRequest(
            subjectToken,
            subjectTokenType,
            actorToken,
            actorTokenType,
            scope != null ? ImmutableList.of(scope) : ImmutableList.of());

    OAuthTokenResponse response =
        client.postForm(
            oauth2ServerUri,
            request,
            OAuthTokenResponse.class,
            headers,
            ErrorHandlers.oauthErrorHandler());
    response.validate();

    return response;
  }

  public static OAuthTokenResponse exchangeToken(
      RESTClient client,
      Map<String, String> headers,
      String subjectToken,
      String subjectTokenType,
      String actorToken,
      String actorTokenType,
      String scope) {
    return exchangeToken(
        client,
        headers,
        subjectToken,
        subjectTokenType,
        actorToken,
        actorTokenType,
        scope,
        ResourcePaths.tokens());
  }

  public static OAuthTokenResponse fetchToken(
      RESTClient client,
      Map<String, String> headers,
      String credential,
      String scope,
      String oauth2ServerUri) {
    Map<String, String> request =
        clientCredentialsRequest(
            credential, scope != null ? ImmutableList.of(scope) : ImmutableList.of());

    OAuthTokenResponse response =
        client.postForm(
            oauth2ServerUri,
            request,
            OAuthTokenResponse.class,
            headers,
            ErrorHandlers.oauthErrorHandler());
    response.validate();

    return response;
  }

  public static OAuthTokenResponse fetchToken(
      RESTClient client, Map<String, String> headers, String credential, String scope) {

    return fetchToken(client, headers, credential, scope, ResourcePaths.tokens());
  }

  private static Map<String, String> tokenExchangeRequest(
      String subjectToken, String subjectTokenType, List<String> scopes) {
    return tokenExchangeRequest(subjectToken, subjectTokenType, null, null, scopes);
  }

  private static Map<String, String> tokenExchangeRequest(
      String subjectToken,
      String subjectTokenType,
      String actorToken,
      String actorTokenType,
      List<String> scopes) {
    Preconditions.checkArgument(
        VALID_TOKEN_TYPES.contains(subjectTokenType), "Invalid token type: %s", subjectTokenType);
    Preconditions.checkArgument(
        actorToken == null || VALID_TOKEN_TYPES.contains(actorTokenType),
        "Invalid token type: %s",
        actorTokenType);

    ImmutableMap.Builder<String, String> formData = ImmutableMap.builder();
    formData.put(GRANT_TYPE, TOKEN_EXCHANGE);
    formData.put(SCOPE, toScope(scopes));
    formData.put(SUBJECT_TOKEN, subjectToken);
    formData.put(SUBJECT_TOKEN_TYPE, subjectTokenType);
    if (actorToken != null) {
      formData.put(ACTOR_TOKEN, actorToken);
      formData.put(ACTOR_TOKEN_TYPE, actorTokenType);
    }

    return formData.build();
  }

  private static Pair<String, String> parseCredential(String credential) {
    Preconditions.checkNotNull(credential, "Invalid credential: null");
    List<String> parts = CREDENTIAL_SPLITTER.splitToList(credential);
    switch (parts.size()) {
      case 2:
        // client ID and client secret
        return Pair.of(parts.get(0), parts.get(1));
      case 1:
        // client secret
        return Pair.of(null, parts.get(0));
      default:
        // this should never happen because the credential splitter is limited to 2
        throw new IllegalArgumentException("Invalid credential: " + credential);
    }
  }

  private static Map<String, String> clientCredentialsRequest(
      String credential, List<String> scopes) {
    Pair<String, String> credentialPair = parseCredential(credential);
    return clientCredentialsRequest(credentialPair.first(), credentialPair.second(), scopes);
  }

  private static Map<String, String> clientCredentialsRequest(
      String clientId, String clientSecret, List<String> scopes) {
    ImmutableMap.Builder<String, String> formData = ImmutableMap.builder();
    formData.put(GRANT_TYPE, CLIENT_CREDENTIALS);
    if (clientId != null) {
      formData.put(CLIENT_ID, clientId);
    }
    formData.put(CLIENT_SECRET, clientSecret);
    formData.put(SCOPE, toScope(scopes));

    return formData.build();
  }

  public static String tokenResponseToJson(OAuthTokenResponse response) {
    return JsonUtil.generate(gen -> tokenResponseToJson(response, gen), false);
  }

  public static void tokenResponseToJson(OAuthTokenResponse response, JsonGenerator gen)
      throws IOException {
    response.validate();

    gen.writeStartObject();

    gen.writeStringField(ACCESS_TOKEN, response.token());
    gen.writeStringField(TOKEN_TYPE, response.tokenType());

    if (response.issuedTokenType() != null) {
      gen.writeStringField(ISSUED_TOKEN_TYPE, response.issuedTokenType());
    }

    if (response.expiresInSeconds() != null) {
      gen.writeNumberField(EXPIRES_IN, response.expiresInSeconds());
    }

    if (response.scopes() != null && !response.scopes().isEmpty()) {
      gen.writeStringField(SCOPE, toScope(response.scopes()));
    }

    gen.writeEndObject();
  }

  public static OAuthTokenResponse tokenResponseFromJson(String json) {
    return JsonUtil.parse(json, OAuth2Util::tokenResponseFromJson);
  }

  public static OAuthTokenResponse tokenResponseFromJson(JsonNode json) {
    Preconditions.checkArgument(
        json.isObject(), "Cannot parse token response from non-object: %s", json);

    OAuthTokenResponse.Builder builder =
        OAuthTokenResponse.builder()
            .withToken(JsonUtil.getString(ACCESS_TOKEN, json))
            .withTokenType(JsonUtil.getString(TOKEN_TYPE, json))
            .withIssuedTokenType(JsonUtil.getStringOrNull(ISSUED_TOKEN_TYPE, json));

    if (json.has(EXPIRES_IN)) {
      builder.setExpirationInSeconds(JsonUtil.getInt(EXPIRES_IN, json));
    }

    if (json.has(SCOPE)) {
      builder.addScopes(parseScope(JsonUtil.getString(SCOPE, json)));
    }

    return builder.build();
  }

  /**
   * If the token is a JWT, extracts the expiration timestamp from the ext claim or null.
   *
   * @param token a token String
   * @return The epoch millisecond the token expires at or null if it's not a valid JWT.
   */
  static Long expiresAtMillis(String token) {
    if (null == token) {
      return null;
    }

    List<String> parts = Splitter.on('.').splitToList(token);
    if (parts.size() != 3) {
      return null;
    }

    JsonNode node;
    try {
      node = JsonUtil.mapper().readTree(Base64.getUrlDecoder().decode(parts.get(1)));
    } catch (IOException e) {
      return null;
    }

    Long expiresAtSeconds = JsonUtil.getLongOrNull("exp", node);
    if (expiresAtSeconds != null) {
      return TimeUnit.SECONDS.toMillis(expiresAtSeconds);
    }

    return null;
  }

  /** Class to handle authorization headers and token refresh. */
  public static class AuthSession {
    private static int tokenRefreshNumRetries = 5;
    private static final long MAX_REFRESH_WINDOW_MILLIS = 300_000; // 5 minutes
    private static final long MIN_REFRESH_WAIT_MILLIS = 10;
    private volatile Map<String, String> headers;
    private volatile String token;
    private volatile String tokenType;
    private volatile Long expiresAtMillis;
    private final String credential;
    private final String scope;
    private volatile boolean keepRefreshed = true;
    private final String oauth2ServerUri;

    public AuthSession(
        Map<String, String> baseHeaders,
        String token,
        String tokenType,
        String credential,
        String scope,
        String oauth2ServerUri) {
      this.headers = RESTUtil.merge(baseHeaders, authHeaders(token));
      this.token = token;
      this.tokenType = tokenType;
      this.expiresAtMillis = OAuth2Util.expiresAtMillis(token);
      this.credential = credential;
      this.scope = scope;
      this.oauth2ServerUri = oauth2ServerUri;
    }

    /** @deprecated since 1.5.0, will be removed in 1.6.0 */
    @Deprecated
    public AuthSession(
        Map<String, String> baseHeaders,
        String token,
        String tokenType,
        String credential,
        String scope) {
      this.headers = RESTUtil.merge(baseHeaders, authHeaders(token));
      this.token = token;
      this.tokenType = tokenType;
      this.expiresAtMillis = OAuth2Util.expiresAtMillis(token);
      this.credential = credential;
      this.scope = scope;
      this.oauth2ServerUri = ResourcePaths.tokens();
    }

    public Map<String, String> headers() {
      return headers;
    }

    public String token() {
      return token;
    }

    public String tokenType() {
      return tokenType;
    }

    public Long expiresAtMillis() {
      return expiresAtMillis;
    }

    public String scope() {
      return scope;
    }

    public void stopRefreshing() {
      this.keepRefreshed = false;
    }

    public String credential() {
      return credential;
    }

    public String oauth2ServerUri() {
      return oauth2ServerUri;
    }

    @VisibleForTesting
    static void setTokenRefreshNumRetries(int retries) {
      tokenRefreshNumRetries = retries;
    }

    /**
     * A new {@link AuthSession} with empty headers.
     *
     * @return A new {@link AuthSession} with empty headers.
     */
    public static AuthSession empty() {
      return new AuthSession(
          ImmutableMap.of(), null, null, null, OAuth2Properties.CATALOG_SCOPE, null);
    }

    /**
     * Attempt to refresh the session token using the token exchange flow.
     *
     * @param client a RESTClient
     * @return interval to wait before calling refresh again, or null if no refresh is needed
     */
    public Pair<Integer, TimeUnit> refresh(RESTClient client) {
      if (token != null && keepRefreshed) {
        AtomicReference<OAuthTokenResponse> ref = new AtomicReference<>(null);
        boolean isSuccessful =
            Tasks.foreach(ref)
                .suppressFailureWhenFinished()
                .retry(tokenRefreshNumRetries)
                .onFailure(
                    (holder, err) -> {
                      // attempt to refresh using the client credential instead of the parent token
                      holder.set(refreshExpiredToken(client));
                      if (holder.get() == null) {
                        LOG.warn("Failed to refresh token", err);
                      }
                    })
                .exponentialBackoff(
                    COMMIT_MIN_RETRY_WAIT_MS_DEFAULT,
                    COMMIT_MAX_RETRY_WAIT_MS_DEFAULT,
                    COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT,
                    2.0 /* exponential */)
                .run(holder -> holder.set(refreshCurrentToken(client)));

        if (!isSuccessful || ref.get() == null) {
          return null;
        }

        OAuthTokenResponse response = ref.get();
        this.token = response.token();
        this.tokenType = response.issuedTokenType();
        this.expiresAtMillis = OAuth2Util.expiresAtMillis(token);
        this.headers = RESTUtil.merge(headers, authHeaders(token));

        if (response.expiresInSeconds() != null) {
          return Pair.of(response.expiresInSeconds(), TimeUnit.SECONDS);
        }
      }

      return null;
    }

    private OAuthTokenResponse refreshCurrentToken(RESTClient client) {
      if (null != expiresAtMillis && expiresAtMillis <= System.currentTimeMillis()) {
        // the token has already expired, attempt to refresh using the credential
        return refreshExpiredToken(client);
      } else {
        // attempt a normal refresh
        return refreshToken(client, headers(), token, tokenType, scope, oauth2ServerUri);
      }
    }

    private OAuthTokenResponse refreshExpiredToken(RESTClient client) {
      if (credential != null) {
        Map<String, String> basicHeaders = RESTUtil.merge(headers(), basicAuthHeaders(credential));
        return refreshToken(client, basicHeaders, token, tokenType, scope, oauth2ServerUri);
      }

      return null;
    }

    /**
     * Schedule refresh for a token using an expiration interval.
     *
     * @param client a RESTClient
     * @param executor a ScheduledExecutorService in which to run the refresh
     * @param session AuthSession to refresh
     * @param expiresAtMillis The epoch millis at which the token expires at
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    private static void scheduleTokenRefresh(
        RESTClient client,
        ScheduledExecutorService executor,
        AuthSession session,
        long expiresAtMillis) {
      long expiresInMillis = expiresAtMillis - System.currentTimeMillis();
      // how much ahead of time to start the request to allow it to complete
      long refreshWindowMillis = Math.min(expiresInMillis / 10, MAX_REFRESH_WINDOW_MILLIS);
      // how much time to wait before expiration
      long waitIntervalMillis = expiresInMillis - refreshWindowMillis;
      // how much time to actually wait
      long timeToWait = Math.max(waitIntervalMillis, MIN_REFRESH_WAIT_MILLIS);

      executor.schedule(
          () -> {
            long refreshStartTime = System.currentTimeMillis();
            Pair<Integer, TimeUnit> expiration = session.refresh(client);
            if (expiration != null) {
              scheduleTokenRefresh(
                  client,
                  executor,
                  session,
                  refreshStartTime + expiration.second().toMillis(expiration.first()));
            }
          },
          timeToWait,
          TimeUnit.MILLISECONDS);
    }

    public static AuthSession fromAccessToken(
        RESTClient client,
        ScheduledExecutorService executor,
        String token,
        Long defaultExpiresAtMillis,
        AuthSession parent) {
      AuthSession session =
          new AuthSession(
              parent.headers(),
              token,
              OAuth2Properties.ACCESS_TOKEN_TYPE,
              parent.credential(),
              parent.scope(),
              parent.oauth2ServerUri());

      long startTimeMillis = System.currentTimeMillis();
      Long expiresAtMillis = session.expiresAtMillis();

      if (null != expiresAtMillis && expiresAtMillis <= startTimeMillis) {
        Pair<Integer, TimeUnit> expiration = session.refresh(client);
        // if expiration is non-null, then token refresh was successful
        if (expiration != null) {
          if (null != session.expiresAtMillis()) {
            // use the new expiration time from the refreshed token
            expiresAtMillis = session.expiresAtMillis();
          } else {
            // otherwise use the expiration time from the token response
            expiresAtMillis = startTimeMillis + expiration.second().toMillis(expiration.first());
          }
        } else {
          // token refresh failed, don't reattempt with the original expiration
          expiresAtMillis = null;
        }
      } else if (null == expiresAtMillis && defaultExpiresAtMillis != null) {
        expiresAtMillis = defaultExpiresAtMillis;
      }

      if (null != executor && null != expiresAtMillis) {
        scheduleTokenRefresh(client, executor, session, expiresAtMillis);
      }

      return session;
    }

    public static AuthSession fromCredential(
        RESTClient client,
        ScheduledExecutorService executor,
        String credential,
        AuthSession parent) {
      long startTimeMillis = System.currentTimeMillis();
      OAuthTokenResponse response =
          fetchToken(
              client, parent.headers(), credential, parent.scope(), parent.oauth2ServerUri());
      return fromTokenResponse(client, executor, response, startTimeMillis, parent, credential);
    }

    public static AuthSession fromTokenResponse(
        RESTClient client,
        ScheduledExecutorService executor,
        OAuthTokenResponse response,
        long startTimeMillis,
        AuthSession parent) {
      return fromTokenResponse(
          client, executor, response, startTimeMillis, parent, parent.credential());
    }

    private static AuthSession fromTokenResponse(
        RESTClient client,
        ScheduledExecutorService executor,
        OAuthTokenResponse response,
        long startTimeMillis,
        AuthSession parent,
        String credential) {
      AuthSession session =
          new AuthSession(
              parent.headers(),
              response.token(),
              response.issuedTokenType(),
              credential,
              parent.scope(),
              parent.oauth2ServerUri());

      Long expiresAtMillis = session.expiresAtMillis();
      if (null == expiresAtMillis && response.expiresInSeconds() != null) {
        expiresAtMillis = startTimeMillis + TimeUnit.SECONDS.toMillis(response.expiresInSeconds());
      }

      if (null != executor && null != expiresAtMillis) {
        scheduleTokenRefresh(client, executor, session, expiresAtMillis);
      }

      return session;
    }

    public static AuthSession fromTokenExchange(
        RESTClient client,
        ScheduledExecutorService executor,
        String token,
        String tokenType,
        AuthSession parent) {
      long startTimeMillis = System.currentTimeMillis();
      OAuthTokenResponse response =
          exchangeToken(
              client,
              parent.headers(),
              token,
              tokenType,
              parent.token(),
              parent.tokenType(),
              parent.scope(),
              parent.oauth2ServerUri());
      return fromTokenResponse(client, executor, response, startTimeMillis, parent);
    }
  }
}
