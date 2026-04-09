package model;

import dto.Comment;
import youtube.YouTubeClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Application model: holds the comment list and authentication state.
 * No threading, no UI — pure data store.
 */
public class CommentsModel {

    private List<Comment> comments = new ArrayList<>();
    private String currentUser;
    private YouTubeClient client;

    // ── Comments ──────────────────────────────────────────────────────────────

    public List<Comment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    public void setComments(List<Comment> comments) {
        this.comments = new ArrayList<>(comments);
    }

    public void removeComments(Set<String> ids) {
        comments.removeIf(c -> ids.contains(c.id()));
    }

    // ── Auth state ────────────────────────────────────────────────────────────

    public String getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(String user) {
        this.currentUser = user;
    }

    public YouTubeClient getClient() {
        return client;
    }

    public void setClient(YouTubeClient client) {
        this.client = client;
    }

    public boolean isLoggedIn() {
        return client != null;
    }

    public void clearAuthState() {
        client = null;
        currentUser = null;
        comments.clear();
    }
}