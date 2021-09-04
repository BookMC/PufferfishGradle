package me.dreamhopping.pml.runtime.start.auth.microsoft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import me.dreamhopping.pml.runtime.start.auth.AuthData;
import me.dreamhopping.pml.runtime.start.auth.GameAuthenticator;
import me.dreamhopping.pml.runtime.start.auth.microsoft.entities.XBLToken;
import me.dreamhopping.pml.runtime.start.auth.microsoft.storage.SavedMicrosoftAuthData;
import me.dreamhopping.pml.runtime.start.auth.microsoft.storage.data.Oauth;
import me.dreamhopping.pml.runtime.start.auth.microsoft.storage.data.Token;
import me.dreamhopping.pml.runtime.start.auth.util.TimeUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Thanks DJ for saving me the mental stress :)
 * Original source code:
 * <a href="https://github.com/DJtheRedstoner/DevAuth">DevAuth</a>
 */
public class MicrosoftGameAuthenticator implements GameAuthenticator {
    private static final String CLIENT_ID = System.getProperty("pufferfishgradle.authentication.clientid", "d7905ded-a392-4943-844a-986ad32436d7");
    private static final String SCOPES = "XboxLive.signin XboxLive.offline_access";
    private static final String REDIRECT_URI = "http://localhost:" + System.getProperty("pufferfishgradle.authentication.port", "3000");
    private static final String OAUTH_URL = "https://login.live.com/oauth20_authorize.srf";
    private static final String OAUTH_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private final Gson gson = new Gson();
    private final CloseableHttpClient client = HttpClients.custom()
        .setUserAgent("Mozilla/5.0 (PufferfishGradle)")
        .build();
    private final Logger LOGGER = LogManager.getLogger(this);

    @Override
    public AuthData authenticate() {
        File dataFile = new File("pg_auth_data_microsoft.dat");
        SavedMicrosoftAuthData data = null;

        if (dataFile.exists()) {
            try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(dataFile))) {
                data = (SavedMicrosoftAuthData) stream.readObject();
            } catch (Throwable e) {
                LOGGER.error("Failed to read cached authentication data: " + e);
                dataFile.delete();
            }
        }

        return tryLogin(data, dataFile);
    }

    private AuthData tryLogin(SavedMicrosoftAuthData authData, File file) {
        try {
            if (authData != null) {
                if (authData.getAccessToken().isExpired()) {
                    if (authData.getOauth().isExpired()) {
                        try {
                            LOGGER.info("OAuth token expired, attempting refresh");
                            authData.setOauth(refreshAuthToken(authData.getOauth()));
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to refresh", e);
                        }
                    }
                    authData.setAccessToken(oauthToMinecraft(authData.getOauth()));
                }

                return getMinecraftProfile(authData.getAccessToken().getToken());
            }

            Oauth oauthToken = getAuthTokenWithOAuth();
            Token Token = oauthToMinecraft(oauthToken);

            saveAuthData(new SavedMicrosoftAuthData(oauthToken, Token), file);
            return getMinecraftProfile(Token.getToken());
        } catch (Exception e) {
            throw new RuntimeException("Error logging in", e);
        }
    }

    private Token oauthToMinecraft(Oauth oauthToken) {
        XBLToken xblToken = getXBLToken(oauthToken.getToken());
        String xstsToken = getXSTSToken(xblToken);
        return getMinecraftToken(xblToken, xstsToken);
    }

    private Oauth refreshAuthToken(Oauth oauthToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", oauthToken.getRefreshToken());

        return getAuthorizationToken(params);
    }

    private Oauth getAuthTokenWithOAuth() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String codeVerifier = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);

        String oauthCode = getAuthorizationCode(codeVerifier);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", oauthCode);
        params.put("grant_type", "authorization_code");
        params.put("code_verifier", codeVerifier);

        return getAuthorizationToken(params);
    }

    private String getAuthorizationCode(String codeVerifier) {
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 3000), 0);
            CompletableFuture<String> future = new CompletableFuture<>();
            server.createContext("/", (req) -> {
                URI uri = req.getRequestURI();

                // all other paths are 404
                if (!uri.getPath().equals("/")) {
                    req.sendResponseHeaders(404, 0);
                    req.getResponseBody().close();
                    return;
                }

                byte[] response = "<html><body><h1>You can now return to your game.</h1></body></html>".getBytes(StandardCharsets.UTF_8);
                req.getResponseHeaders().add("Content-Type", "text/html");
                req.sendResponseHeaders(200, response.length);
                req.getResponseBody().write(response);
                req.getResponseBody().close();

                Map<String, String> query = parseQuery(req.getRequestURI().getRawQuery());

                if (query.containsKey("error")) {
                    future.completeExceptionally(oauthError(query.get("error"), query.get("error_description")));
                }

                future.complete(query.get("code"));
            });
            server.start();

            Map<String, String> query = new LinkedHashMap<>();
            query.put("client_id", CLIENT_ID);
            query.put("response_type", "code");
            query.put("redirect_uri", REDIRECT_URI);
            query.put("scope", SCOPES);
            query.put("prompt", "select_account");
            query.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(DigestUtils.sha256(codeVerifier)));
            query.put("code_challenge_method", "S256");

            String queryString = buildQuery(query);

            LOGGER.info("OAuth URL, open this in a browser to complete authentication: " + OAUTH_URL + "?" + queryString);

            return future.join();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get authorization code", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private Oauth getAuthorizationToken(Map<String, String> params) {
        try {
            params = cloneMap(params);
            params.put("client_id", CLIENT_ID);
            params.put("scope", SCOPES);
            params.put("redirect_uri", REDIRECT_URI);

            HttpPost request = new HttpPost(OAUTH_TOKEN_URL);
            request.setEntity(new UrlEncodedFormEntity(buildNameValuePairs(params)));

            HttpResponse response = client.execute(request);

            String body = EntityUtils.toString(response.getEntity());

            if (response.getStatusLine().getStatusCode() != 200) {
                throw httpError(response, body);
            }

            JsonObject object = JsonParser.parseString(body).getAsJsonObject();

            String token = object.get("access_token").getAsString();
            String refreshToken = object.get("refresh_token").getAsString();
            long expiry = expiryToTime(object.get("expires_in").getAsInt());

            return new Oauth(token, expiry, refreshToken);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get authorization token", e);
        }
    }

    private XBLToken getXBLToken(String oauthToken) {
        try {
            JsonObject object = new JsonObject();

            JsonObject properties = new JsonObject();
            object.add("Properties", properties);
            properties.addProperty("AuthMethod", "RPS");
            properties.addProperty("SiteName", "user.auth.xboxlive.com");
            properties.addProperty("RpsTicket", "d=" + oauthToken);

            //noinspection HttpUrlsUsage
            object.addProperty("RelyingParty", "http://auth.xboxlive.com");
            object.addProperty("TokenType", "JWT");

            JsonObject xblObject = jsonPostRequest(XBL_URL, object);

            String token = xblObject.get("Token").getAsString();
            String userhash = xblObject
                .get("DisplayClaims").getAsJsonObject()
                .get("xui").getAsJsonArray()
                .get(0).getAsJsonObject()
                .get("uhs").getAsString();

            OffsetDateTime expiryTime = OffsetDateTime.parse(xblObject.get("NotAfter").getAsString(), DateTimeFormatter.ISO_DATE_TIME);
            long expiry = Instant.from(expiryTime).getEpochSecond();

            return new XBLToken(token, expiry, userhash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get XBL token", e);
        }
    }

    private String getXSTSToken(XBLToken xblToken) {
        try {
            JsonObject object = new JsonObject();

            JsonObject properties = new JsonObject();
            object.add("Properties", properties);
            properties.addProperty("SandboxId", "RETAIL");
            JsonArray userTokens = new JsonArray();
            userTokens.add(xblToken.getToken());
            properties.add("UserTokens", userTokens);

            object.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
            object.addProperty("TokenType", "JWT");

            JsonObject xstsObject = jsonPostRequest(XSTS_URL, object);

            return xstsObject.get("Token").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get XSTS token", e);
        }
    }

    private Token getMinecraftToken(XBLToken xblToken, String xstsToken) {
        try {
            JsonObject object = new JsonObject();
            object.addProperty("identityToken", "XBL3.0 x=" + xblToken.getUserHash() + ";" + xstsToken);

            JsonObject minecraftObject = jsonPostRequest(MINECRAFT_URL, object);

            String token = minecraftObject.get("access_token").getAsString();
            long expiry = expiryToTime(minecraftObject.get("expires_in").getAsInt());

            return new Token(token, expiry);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Minecraft token", e);
        }
    }

    private AuthData getMinecraftProfile(String minecraftToken) {
        try {
            HttpGet request = new HttpGet(MINECRAFT_PROFILE_URL);
            request.setHeader("Authorization", "Bearer " + minecraftToken);

            HttpResponse response = client.execute(request);
            String body = EntityUtils.toString(response.getEntity());

            if (response.getStatusLine().getStatusCode() == 404) {
                throw new RuntimeException("404 received for minecraft profile, does the user own the game?");
            } else if (response.getStatusLine().getStatusCode() != 200) {
                throw httpError(response, body);
            }

            JsonObject profileObject = JsonParser.parseString(body).getAsJsonObject();

            return new AuthData(
                profileObject.get("name").getAsString(),
                profileObject.get("id").getAsString(),
                minecraftToken
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Minecraft profile", e);
        }
    }

    private RuntimeException oauthError(String error, String description) {
        return new RuntimeException("OAuth error: " + error + " Description: " + description);
    }

    private RuntimeException httpError(HttpResponse response, String body) {
        throw new RuntimeException(
            "Received invalid response from the server: " +
                response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase() +
                " body: " + body
        );
    }

    private String buildQuery(Map<String, String> params) {
        StringJoiner queryString = new StringJoiner("&");

        for (Map.Entry<String, String> param : params.entrySet()) {
            queryString.add(param.getKey() + "=" + URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }

        return queryString.toString();
    }

    private List<NameValuePair> buildNameValuePairs(Map<String, String> params) {
        List<NameValuePair> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            list.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    private JsonObject jsonPostRequest(String url, JsonObject object) throws Exception {
        HttpPost request = new HttpPost(url);
        request.setEntity(new StringEntity(gson.toJson(object), ContentType.APPLICATION_JSON));
        request.setHeader("Accept", "application/json");

        HttpResponse response = client.execute(request);

        String body = EntityUtils.toString(response.getEntity());

        if (response.getStatusLine().getStatusCode() != 200) {
            throw httpError(response, body);
        }

        return JsonParser.parseString(body).getAsJsonObject();
    }

    private Map<String, String> parseQuery(String query) {
        return URLEncodedUtils
            .parse(query, StandardCharsets.UTF_8)
            .stream().collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));
    }

    private long expiryToTime(long expiry) {
        return TimeUtils.secondsSinceEpoch() + expiry;
    }

    @SuppressWarnings("unchecked")
    private <K, V> Map<K, V> cloneMap(Map<K, V> map) {
        if (map instanceof HashMap) {
            return (Map<K, V>) ((HashMap<K, V>) map).clone();
        }
        throw new IllegalArgumentException("Unable to clone map of class " + map.getClass());
    }
}
