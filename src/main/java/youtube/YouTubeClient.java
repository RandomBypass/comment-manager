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
import com.google.api.services.youtube.model.*;
import dto.Comment;

import java.io.*;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class YouTubeClient {

    private static final String APPLICATION_NAME = "YouTube Comment Manager";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String CREDENTIALS_FOLDER =
            System.getProperty("user.home") + "/.comment-manager/credentials";
    private static final List<String> SCOPES =
            List.of("https://www.googleapis.com/auth/youtube.force-ssl");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final YouTube youtube;
    private final String channelId;
    private final String channelTitle;
    private final Map<String, String> videoTitleCache = new HashMap<>();

    private YouTubeClient(YouTube youtube, String channelId, String channelTitle) {
        this.youtube = youtube;
        this.channelId = channelId;
        this.channelTitle = channelTitle;
    }

    /**
     * Authenticates via OAuth2 using the provided client_secrets.json file obtained
     * from Google Cloud Console. Opens a browser for user authorization on first run.
     * Credentials are cached locally at ~/.comment-manager/credentials for reuse.
     */
    public static YouTubeClient authenticate(File clientSecretsFile)
            throws IOException, GeneralSecurityException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        GoogleClientSecrets clientSecrets;
        try (Reader reader = new FileReader(clientSecretsFile)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }

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
        return new YouTubeClient(youtube, channel.getId(), channel.getSnippet().getTitle());
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    public String getChannelId() {
        return channelId;
    }

    /**
     * Fetches all comment threads related to the authenticated user's channel,
     * including top-level comments and their available replies (up to 5 per thread).
     * Paginates automatically to retrieve all results.
     */
    public List<Comment> fetchChannelComments() throws IOException {
        List<Comment> comments = new ArrayList<>();
        String nextPageToken = null;

        do {
            YouTube.CommentThreads.List request = youtube.commentThreads()
                    .list(List.of("snippet", "replies"))
                    .setAllThreadsRelatedToChannelId(channelId)
                    .setMaxResults(100L);

            if (nextPageToken != null) {
                request.setPageToken(nextPageToken);
            }

            CommentThreadListResponse response = request.execute();

            if (response.getItems() != null) {
                for (CommentThread thread : response.getItems()) {
                    com.google.api.services.youtube.model.Comment topLevel =
                            thread.getSnippet().getTopLevelComment();
                    addComment(comments, topLevel);

                    if (thread.getReplies() != null) {
                        for (com.google.api.services.youtube.model.Comment reply :
                                thread.getReplies().getComments()) {
                            addComment(comments, reply);
                        }
                    }
                }
            }

            nextPageToken = response.getNextPageToken();
        } while (nextPageToken != null);

        return comments;
    }

    private void addComment(List<Comment> comments,
                            com.google.api.services.youtube.model.Comment apiComment) {
        CommentSnippet snippet = apiComment.getSnippet();
        String videoTitle = fetchVideoTitle(snippet.getVideoId());
        String date = DATE_FORMATTER.format(Instant.ofEpochMilli(snippet.getPublishedAt().getValue()));
        comments.add(new Comment(
                apiComment.getId(),
                snippet.getAuthorDisplayName(),
                channelTitle,
                videoTitle,
                snippet.getTextDisplay(),
                date
        ));
    }

    private String fetchVideoTitle(String videoId) {
        return videoTitleCache.computeIfAbsent(videoId, id -> {
            try {
                VideoListResponse response = youtube.videos()
                        .list(List.of("snippet"))
                        .setId(List.of(id))
                        .execute();
                if (response.getItems() != null && !response.getItems().isEmpty()) {
                    return response.getItems().get(0).getSnippet().getTitle();
                }
            } catch (IOException ignored) {
            }
            return id;
        });
    }

    /**
     * Deletes a comment by its YouTube comment ID.
     * The authenticated user must own the comment or own the channel where it was posted.
     */
    public void deleteComment(String commentId) throws IOException {
        youtube.comments().delete(commentId).execute();
    }
}