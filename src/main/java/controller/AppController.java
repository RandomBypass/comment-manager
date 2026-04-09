package controller;

import dto.Comment;
import dto.VideoInfo;
import model.CommentsModel;
import view.MainView;
import youtube.TakeoutImporter;
import youtube.YouTubeClient;

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
 * Application controller (MVC). Wires {@link MainView} events to {@link CommentsModel} state
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
     * Called after the view is shown. Attempts silent background login if credentials are cached.
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
     * After import, fetches real video titles and channel names from the YouTube API
     * to replace the raw video IDs. Skips post comments (non-video URLs).
     */
    private void resolveVideoNamesInBackground() {
        if (!model.isLoggedIn() || model.getComments().isEmpty()) return;

        Set<String> videoIds = new HashSet<>();
        for (Comment c : model.getComments()) {
            if (c.videoUrl().contains("watch?v=")) videoIds.add(c.video());
        }
        if (videoIds.isEmpty()) return;

        view.setStatus("Resolving video titles and channel names...");

        new SwingWorker<Map<String, VideoInfo>, Void>() {
            @Override
            protected Map<String, VideoInfo> doInBackground() throws Exception {
                return model.getClient().fetchVideoInfo(videoIds);
            }

            @Override
            protected void done() {
                try {
                    Map<String, VideoInfo> infoMap = get();
                    List<Comment> resolved = new ArrayList<>();
                    for (Comment c : model.getComments()) {
                        VideoInfo info = infoMap.get(c.video());
                        resolved.add(info == null ? c : new Comment(
                                c.id(), c.user(),
                                info.channelTitle(),
                                info.title(),
                                c.text(), c.date(), c.videoUrl()
                        ));
                    }
                    model.setComments(resolved);
                    view.loadComments(model.getComments());
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
        if (view.confirmBatchDelete(visible)) {
            deleteByModelRows(view.getVisibleModelRows());
        }
    }

    private void deleteByModelRows(List<Integer> modelRows) {
        // Sort descending so row-index removals don't shift lower indices
        modelRows.sort(Collections.reverseOrder());

        List<String> ids = new ArrayList<>();
        for (int row : modelRows) ids.add(view.getCommentIdAt(row));

        if (!model.isLoggedIn()) {
            // No API client: remove from model only
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