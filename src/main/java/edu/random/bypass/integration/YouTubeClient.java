package edu.random.bypass.integration;

import com.github.javakeyring.Keyring;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.random.bypass.dto.VideoInfo;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YouTubeClient {

    private static final String APPLICATION_NAME    = "YouTube Comment Manager";
    private static final GsonFactory JSON_FACTORY   = GsonFactory.getDefaultInstance();
    private static final String CLIENT_SECRETS_RESOURCE = "/client_secret.json";
    private static final String KEYRING_SERVICE     = "youtube-comment-manager";
    private static final String KEYRING_ACCOUNT     = "refresh-token";
    private static final List<String> SCOPES        = List.of("https://www.googleapis.com/auth/youtube.force-ssl");
    private static final String REDIRECT_URI        = "http://localhost:8888";
    private static final String AUTH_ENDPOINT       = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT      = "https://oauth2.googleapis.com/token";

    private final YouTube youtube;
    private final String channelTitle;

    private YouTubeClient(YouTube youtube, String channelTitle) {
        this.youtube = youtube;
        this.channelTitle = channelTitle;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Authenticates via OAuth 2.0 + PKCE using the client credentials bundled in
     * {@code src/main/resources/client_secret.json} (Desktop app type from Google
     * Cloud Console). No client secret needs to be entered by the user.
     *
     * <p>On the first call the browser opens for user consent. Subsequent calls
     * reuse the refresh token cached in the OS keyring.</p>
     *
     * <p>Falls back to the full browser flow automatically if the cached token
     * has been revoked or expired.</p>
     */
    public static YouTubeClient authenticate() throws IOException, GeneralSecurityException {
        GoogleClientSecrets secrets = loadClientSecrets();
        String clientId     = secrets.getInstalled().getClientId();
        String clientSecret = secrets.getInstalled().getClientSecret();

        String refreshToken = loadRefreshToken();
        if (refreshToken != null) {
            try {
                return buildClient(clientId, clientSecret, refreshToken);
            } catch (IOException e) {
                clearStoredToken(); // revoked — fall through to full flow
            }
        }
        return doFullPkceFlow(clientId, clientSecret);
    }

    /** Returns {@code true} if a cached refresh token exists in the OS keyring (silent login possible). */
    public static boolean hasStoredRefreshToken() {
        return loadRefreshToken() != null;
    }

    /** Deletes the cached refresh token from the OS keyring (call on logout). */
    public static void clearStoredToken() {
        try (Keyring keyring = Keyring.create()) {
            keyring.deletePassword(KEYRING_SERVICE, KEYRING_ACCOUNT);
        } catch (Exception ignored) {}
    }

    public String getChannelTitle() { return channelTitle; }

    /**
     * Fetches video title and channel name for the given video IDs,
     * in batches of 50 (API limit).
     */
    public Map<String, VideoInfo> fetchVideoInfo(Collection<String> videoIds) throws IOException {
        Map<String, VideoInfo> info = new HashMap<>();
        List<String> ids = new ArrayList<>(videoIds);
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(i + 50, ids.size()));
            VideoListResponse response = youtube.videos()
                    .list(List.of("snippet"))
                    .setId(batch)
                    .execute();
            if (response.getItems() != null) {
                for (Video v : response.getItems()) {
                    info.put(v.getId(), new VideoInfo(
                            v.getSnippet().getTitle(),
                            v.getSnippet().getChannelTitle()
                    ));
                }
            }
        }
        return info;
    }

    public void deleteComment(String commentId) throws IOException {
        youtube.comments().delete(commentId).execute();
    }

    // ── PKCE flow ─────────────────────────────────────────────────────────────

    private static YouTubeClient doFullPkceFlow(String clientId, String clientSecret)
            throws IOException, GeneralSecurityException {
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String authCode      = authorize(clientId, codeChallenge);

        JsonObject tokens = exchangeCode(clientId, clientSecret, authCode, codeVerifier);
        String refreshToken = tokens.get("refresh_token").getAsString();
        saveRefreshToken(refreshToken);

        return buildClient(clientId, clientSecret, refreshToken);
    }

    /**
     * Builds a YouTube client from a (valid) refresh token. Calls
     * {@code credential.refreshToken()} to obtain an initial access token;
     * throws {@link IOException} if the token has been revoked.
     */
    private static YouTubeClient buildClient(String clientId, String clientSecret, String refreshToken)
            throws IOException, GeneralSecurityException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(httpTransport)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new GenericUrl(TOKEN_ENDPOINT))
                .setClientAuthentication(new ClientParametersAuthentication(clientId, clientSecret))
                .build()
                .setRefreshToken(refreshToken);

        credential.refreshToken(); // validates + obtains access token

        YouTube youtube = new YouTube.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        ChannelListResponse resp = youtube.channels()
                .list(List.of("snippet"))
                .setMine(true)
                .execute();

        if (resp.getItems() == null || resp.getItems().isEmpty()) {
            throw new IOException("No YouTube channel found for the authenticated account.");
        }

        return new YouTubeClient(youtube, resp.getItems().get(0).getSnippet().getTitle());
    }

    // ── Browser redirect / local server ───────────────────────────────────────

    /**
     * Opens the Google consent screen in the user's browser and waits for the
     * authorization code to arrive at the localhost callback (port 8888).
     */
    private static String authorize(String clientId, String codeChallenge) throws IOException {
        String scope = URLEncoder.encode(String.join(" ", SCOPES), StandardCharsets.UTF_8);
        String authUrl = AUTH_ENDPOINT
                + "?client_id="            + URLEncoder.encode(clientId,     StandardCharsets.UTF_8)
                + "&redirect_uri="         + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope="                + scope
                + "&code_challenge="       + codeChallenge
                + "&code_challenge_method=S256"
                + "&access_type=offline"
                + "&prompt=consent"; // ensures a refresh_token is always returned

        Desktop.getDesktop().browse(URI.create(authUrl));

        // Block until the browser redirects back to localhost:8888
        try (ServerSocket server = new ServerSocket(8888)) {
            server.setSoTimeout(300_000); // 5-minute window for the user to approve
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                String requestLine = in.readLine(); // "GET /?code=...&scope=... HTTP/1.1"

                // Reply so the browser shows a completion message
                String body = "<html><body style='font-family:sans-serif;text-align:center;margin-top:60px'>"
                        + "<h2>Authorization complete!</h2>"
                        + "<p>You may close this window and return to the app.</p></body></html>";
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                out.print("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n\r\n"
                        + body);
                out.flush();

                if (requestLine == null) throw new IOException("Empty HTTP callback from browser.");

                int queryStart = requestLine.indexOf('?');
                int pathEnd    = requestLine.lastIndexOf(' ');
                if (queryStart < 0) throw new IOException("No query string in callback: " + requestLine);
                String query = requestLine.substring(queryStart + 1, pathEnd > queryStart ? pathEnd : requestLine.length());

                String code = extractQueryParam(query, "code");
                if (code == null) {
                    String error = extractQueryParam(query, "error");
                    throw new IOException("Authorization denied" + (error != null ? ": " + error : "."));
                }
                return URLDecoder.decode(code, StandardCharsets.UTF_8);
            }
        }
    }

    private static String extractQueryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return eq + 1 < pair.length() ? pair.substring(eq + 1) : "";
            }
        }
        return null;
    }

    // ── PKCE cryptography ─────────────────────────────────────────────────────

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // SHA-256 is always available
        }
    }

    // ── Token exchange ────────────────────────────────────────────────────────

    private static JsonObject exchangeCode(String clientId, String clientSecret,
                                           String code, String codeVerifier) throws IOException {
        String body = "client_id="     + enc(clientId)
                + "&client_secret="    + enc(clientSecret)
                + "&code="             + enc(code)
                + "&code_verifier="    + enc(codeVerifier)
                + "&grant_type=authorization_code"
                + "&redirect_uri="     + enc(REDIRECT_URI);
        return postForm(body);
    }

    private static JsonObject postForm(String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bodyBytes);
            }

            int status = conn.getResponseCode();
            String response;
            try (InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (status >= 400) {
                throw new IOException("Token request failed (HTTP " + status + "): " + response);
            }
            return JsonParser.parseString(response).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── Token persistence (OS keyring) ────────────────────────────────────────

    private static String loadRefreshToken() {
        try (Keyring keyring = Keyring.create()) {
            return keyring.getPassword(KEYRING_SERVICE, KEYRING_ACCOUNT);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveRefreshToken(String token) throws IOException {
        try (Keyring keyring = Keyring.create()) {
            keyring.setPassword(KEYRING_SERVICE, KEYRING_ACCOUNT, token);
        } catch (Exception e) {
            throw new IOException("Failed to store refresh token in OS keyring: " + e.getMessage(), e);
        }
    }

    // ── Client secrets ────────────────────────────────────────────────────────

    private static GoogleClientSecrets loadClientSecrets() throws IOException {
        InputStream is = YouTubeClient.class.getResourceAsStream(CLIENT_SECRETS_RESOURCE);
        if (is == null) {
            throw new IOException("client_secret.json not found in resources. "
                    + "Download it from Google Cloud Console \u2192 APIs & Services \u2192 Credentials "
                    + "and place it at src/main/resources/client_secret.json.");
        }
        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return GoogleClientSecrets.load(JSON_FACTORY, reader);
        }
    }
}