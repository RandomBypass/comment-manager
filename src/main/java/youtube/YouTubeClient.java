package youtube;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.List;

public class YouTubeClient {

    private static final String APPLICATION_NAME = "YouTube Comment Manager";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String CREDENTIALS_FOLDER =
            System.getProperty("user.home") + "/.comment-manager/credentials";
    private static final List<String> SCOPES =
            List.of("https://www.googleapis.com/auth/youtube.force-ssl");

    private final YouTube youtube;
    private final String channelTitle;

    private YouTubeClient(YouTube youtube, String channelTitle) {
        this.youtube = youtube;
        this.channelTitle = channelTitle;
    }

    /**
     * Authenticates via OAuth2 using the provided client_secrets.json file.
     * Convenience overload for first-time setup when the user selects the file manually.
     */
    public static YouTubeClient authenticate(File clientSecretsFile)
            throws IOException, GeneralSecurityException {
        try (Reader reader = new FileReader(clientSecretsFile)) {
            return authenticate(reader);
        }
    }

    /**
     * Authenticates via OAuth2 using the client_secrets.json content as a string.
     * Used when the content has been loaded from the OS keyring.
     */
    public static YouTubeClient authenticate(String clientSecretsJson)
            throws IOException, GeneralSecurityException {
        return authenticate(new StringReader(clientSecretsJson));
    }

    /**
     * Core OAuth2 flow. Opens a browser on first run; subsequent calls reuse
     * the refresh token cached at ~/.comment-manager/credentials.
     */
    private static YouTubeClient authenticate(Reader clientSecretsReader)
            throws IOException, GeneralSecurityException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, clientSecretsReader);

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(CREDENTIALS_FOLDER)))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        YouTube youtube = new YouTube.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        ChannelListResponse channelResponse = youtube.channels()
                .list(List.of("snippet"))
                .setMine(true)
                .execute();

        if (channelResponse.getItems() == null || channelResponse.getItems().isEmpty()) {
            throw new IOException("No YouTube channel found for the authenticated Google account");
        }

        Channel channel = channelResponse.getItems().get(0);
        return new YouTubeClient(youtube, channel.getSnippet().getTitle());
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    /**
     * Deletes a comment by its YouTube comment ID.
     * The authenticated user must own the comment.
     */
    public void deleteComment(String commentId) throws IOException {
        youtube.comments().delete(commentId).execute();
    }
}