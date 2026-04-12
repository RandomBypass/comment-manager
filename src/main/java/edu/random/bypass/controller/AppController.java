package edu.random.bypass.controller;

import edu.random.bypass.dto.Comment;
import edu.random.bypass.dto.VideoInfo;
import edu.random.bypass.integration.YouTubeClient;
import edu.random.bypass.model.CommentsModel;
import edu.random.bypass.service.TakeoutImporter;
import edu.random.bypass.view.MainView;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

/**
 * Application edu.random.bypass.controller (MVC). Wires {@link MainView} events to {@link CommentsModel} state
 * changes and runs all background work via {@link SwingWorker}.
 */
public class AppController {

    private final CommentsModel model;
    private final MainView view;
    private final Preferences prefs = Preferences.userNodeForPackage(AppController.class);

    public AppController(CommentsModel model, MainView view) {
        this.model = model;
        this.view = view;
        wireListeners();
    }

    /**
     * Called after the edu.random.bypass.view is shown. Attempts silent background login if credentials are cached.
     */
    public void start() {
        if (!YouTubeClient.hasStoredRefreshToken()) return;
        view.setStatus("Authenticating in background...");
        loginWithYouTube();
    }

    // ── Listener wiring ───────────────────────────────────────────────────────

    private void wireListeners() {
        view.getLoginButton().addActionListener(e -> handleLogin());
        view.getImportButton().addActionListener(e -> handleImport());
        view.getDeleteSelectedButton().addActionListener(e -> handleDeleteSelected());
        view.getDeleteFilteredButton().addActionListener(e -> handleDeleteFiltered());
    }

    // ── Login / logout ────────────────────────────────────────────────────────

    private void handleLogin() {
        if (!model.isLoggedIn()) {
            loginWithYouTube();
        } else {
            YouTubeClient.clearStoredToken();
            model.clearAuthState();
            view.loadComments(List.of());
            view.onLoggedOut();
            view.setStatus("Logged out.");
        }
    }

    private void loginWithYouTube() {
        view.getLoginButton().setEnabled(false);
        view.setStatus("Opening browser for Google authorization...");

        new SwingWorker<YouTubeClient, Void>() {
            @Override
            protected YouTubeClient doInBackground() throws Exception {
                return YouTubeClient.authenticate();
            }

            @Override
            protected void done() {
                view.getLoginButton().setEnabled(true);
                try {
                    YouTubeClient client = get();
                    model.setClient(client);
                    model.setCurrentUser(client.getChannelTitle());
                    view.onLoggedIn(model.getCurrentUser());
                    view.updateQuota(client.getQuotaUsed(), YouTubeClient.DAILY_QUOTA_LIMIT);
                    view.setStatus("Logged in as " + model.getCurrentUser()
                            + ". Import a Takeout CSV to load comments.");
                } catch (ExecutionException e) {
                    view.showError("Login Error", "Authentication failed: " + e.getCause().getMessage());
                    view.setStatus("Authentication failed. Please try again.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    view.setStatus("Authentication was interrupted.");
                }
            }
        }.execute();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private void handleImport() {
        File csvFile = view.showImportFileChooser(prefs.get("lastImportDir", null));
        if (csvFile == null) return;
        prefs.put("lastImportDir", csvFile.getParent());

        String authorName = model.isLoggedIn() ? model.getCurrentUser() : "me";
        view.setBusy(true, model.isLoggedIn());

        new SwingWorker<List<Comment>, Void>() {
            @Override
            protected List<Comment> doInBackground() throws Exception {
                return TakeoutImporter.importFromCsv(csvFile, authorName);
            }

            @Override
            protected void done() {
                view.setBusy(false, model.isLoggedIn());
                try {
                    List<Comment> imported = get();
                    model.setComments(imported);
                    view.loadComments(model.getComments());
                    view.setStatus("Imported " + imported.size() + " comments from " + csvFile.getName() + ".");
                    resolveVideoNamesInBackground();
                } catch (ExecutionException e) {
                    view.showError("Import Error", "Import failed: " + e.getCause().getMessage());
                    view.setStatus("Import failed.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * After import, fetches video titles/channel names and post channel names from
     * the YouTube API to replace the raw IDs stored in the model.
     */
    private void resolveVideoNamesInBackground() {
        if (!model.isLoggedIn() || model.getComments().isEmpty()) return;

        Set<String> videoIds = new HashSet<>();
        Set<String> postChannelIds = new HashSet<>();
        for (Comment c : model.getComments()) {
            if (c.videoUrl().contains("watch?v=")) {
                videoIds.add(c.video());
            } else if (c.videoUrl().contains("/post/")) {
                postChannelIds.add(c.channel());
            }
        }
        if (videoIds.isEmpty() && postChannelIds.isEmpty()) return;

        view.setStatus("Resolving video titles and channel names...");

        // Snapshot the list so the background thread doesn't race with EDT writes
        List<Comment> snapshot = new ArrayList<>(model.getComments());

        new SwingWorker<List<Comment>, Void>() {
            @Override
            protected List<Comment> doInBackground() throws Exception {
                Map<String, VideoInfo> videoInfoMap = videoIds.isEmpty()
                        ? Map.of() : model.getClient().fetchVideoInfo(videoIds);
                Map<String, String> channelTitleMap = postChannelIds.isEmpty()
                        ? Map.of() : model.getClient().fetchChannelTitles(postChannelIds);

                List<Comment> resolved = new ArrayList<>();
                for (Comment c : snapshot) {
                    if (c.videoUrl().contains("watch?v=")) {
                        VideoInfo info = videoInfoMap.get(c.video());
                        resolved.add(info == null ? c : new Comment(
                                c.id(), c.user(), info.channelTitle(), info.title(),
                                c.text(), c.date(), c.videoUrl()));
                    } else if (c.videoUrl().contains("/post/")) {
                        String title = channelTitleMap.get(c.channel());
                        resolved.add(title == null ? c : new Comment(
                                c.id(), c.user(), title, c.video(),
                                c.text(), c.date(), c.videoUrl()));
                    } else {
                        resolved.add(c);
                    }
                }
                return resolved;
            }

            @Override
            protected void done() {
                try {
                    List<Comment> resolved = get();
                    model.setComments(resolved);
                    view.loadComments(model.getComments());
                    view.updateQuota(model.getClient().getQuotaUsed(), YouTubeClient.DAILY_QUOTA_LIMIT);
                    view.setStatus("Loaded " + resolved.size() + " comments with titles resolved.");
                } catch (Exception ignored) {
                    // Non-fatal: raw IDs already shown as fallback
                }
            }
        }.execute();
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    private void handleDeleteSelected() {
        List<Integer> modelRows = view.getCheckedModelRows();
        if (modelRows.isEmpty()) {
            JOptionPane.showMessageDialog(view, "No comments selected for deletion.");
            return;
        }
        if (model.isLoggedIn() && !hasQuotaFor(modelRows.size())) return;
        if (view.confirmDelete(modelRows.size())) {
            deleteByModelRows(modelRows);
        }
    }

    private void handleDeleteFiltered() {
        int visible = view.getVisibleRowCount();
        if (visible == 0) {
            JOptionPane.showMessageDialog(view, "No comments visible to delete.");
            return;
        }
        if (model.isLoggedIn() && !hasQuotaFor(visible)) return;
        if (view.confirmBatchDelete(visible)) {
            deleteByModelRows(view.getVisibleModelRows());
        }
    }

    /**
     * Returns {@code true} if the API client has enough estimated quota to delete
     * {@code count} comments. Shows an error dialog and returns {@code false} otherwise.
     */
    private boolean hasQuotaFor(int count) {
        int needed = count * YouTubeClient.QUOTA_COST_DELETE;
        int remaining = model.getClient().getQuotaRemaining();
        if (remaining >= needed) return true;
        view.showError("Insufficient Quota",
                String.format("Deleting %,d comment(s) requires %,d quota units, "
                                + "but only %,d are estimated to remain today.",
                        count, needed, remaining));
        return false;
    }

    private void deleteByModelRows(List<Integer> modelRows) {
        // Sort descending so row-index removals don't shift lower indices
        modelRows.sort(Collections.reverseOrder());

        List<String> ids = new ArrayList<>();
        for (int row : modelRows) ids.add(view.getCommentIdAt(row));

        if (!model.isLoggedIn()) {
            model.removeComments(new HashSet<>(ids));
            view.loadComments(model.getComments());
            view.setStatus("Removed " + ids.size() + " comment(s) from view.");
            return;
        }

        view.setBusy(true, true);
        view.setStatus("Deleting " + ids.size() + " comment(s) via YouTube API...");

        new SwingWorker<Set<String>, Void>() {
            @Override
            protected Set<String> doInBackground() {
                Set<String> deleted = new LinkedHashSet<>();
                for (String id : ids) {
                    if (id == null || id.isEmpty()) continue;
                    try {
                        model.getClient().deleteComment(id);
                        deleted.add(id);
                    } catch (IOException ignored) {
                    }
                }
                return deleted;
            }

            @Override
            protected void done() {
                view.setBusy(false, model.isLoggedIn());
                try {
                    Set<String> deletedIds = get();
                    model.removeComments(deletedIds);
                    view.loadComments(model.getComments());
                    view.updateQuota(model.getClient().getQuotaUsed(), YouTubeClient.DAILY_QUOTA_LIMIT);

                    int failed = ids.size() - deletedIds.size();
                    if (failed > 0) {
                        view.setStatus("Deleted " + deletedIds.size() + " comment(s). "
                                + failed + " could not be deleted (insufficient permissions or API error).");
                    } else {
                        view.setStatus("Successfully deleted " + deletedIds.size() + " comment(s).");
                    }
                } catch (ExecutionException | InterruptedException e) {
                    view.setStatus("Error during deletion: " + e.getMessage());
                }
            }
        }.execute();
    }
}