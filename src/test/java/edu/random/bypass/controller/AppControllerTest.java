package edu.random.bypass.controller;

import edu.random.bypass.dto.Comment;
import edu.random.bypass.integration.YouTubeClient;
import edu.random.bypass.model.CommentsModel;
import edu.random.bypass.view.MainView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the synchronous code paths in AppController that do not involve
 * SwingWorker background threads or network calls.
 */
@ExtendWith(MockitoExtension.class)
class AppControllerTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private MainView view;
    private CommentsModel model;
    private AppController controller;

    private JButton loginButton;
    private JButton importButton;
    private JButton deleteSelectedButton;
    private JButton deleteFilteredButton;

    @BeforeEach
    void setUp() {
        view = mock(MainView.class);

        // Wire real JButton instances so wireListeners() can attach ActionListeners
        loginButton = new JButton();
        importButton = new JButton();
        deleteSelectedButton = new JButton();
        deleteFilteredButton = new JButton();

        when(view.getLoginButton()).thenReturn(loginButton);
        when(view.getImportButton()).thenReturn(importButton);
        when(view.getDeleteSelectedButton()).thenReturn(deleteSelectedButton);
        when(view.getDeleteFilteredButton()).thenReturn(deleteFilteredButton);

        model = new CommentsModel();
        controller = new AppController(model, view);
    }

    // ── start() ───────────────────────────────────────────────────────────────

    @Test
    void start_withNoStoredToken_doesNotSetStatus() {
        // In the test environment the OS keyring has no entry → hasStoredRefreshToken() returns false
        controller.start();
        verify(view, never()).setStatus(anyString());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void loginButtonClick_whenLoggedIn_logsOut() {
        // Put the model in a logged-in state with comments
        model.setClient(mock(YouTubeClient.class));
        model.setCurrentUser("Alice");
        model.setComments(List.of(comment("id1")));

        loginButton.doClick();

        assertFalse(model.isLoggedIn());
        assertNull(model.getCurrentUser());
        assertTrue(model.getComments().isEmpty());
        verify(view).loadComments(List.of());
        verify(view).onLoggedOut();
        verify(view).setStatus("Logged out.");
    }

    // ── Delete without login ──────────────────────────────────────────────────

    @Test
    void deleteSelectedButton_withoutLogin_removesFromModelAndReloadsView() {
        model.setComments(List.of(comment("id1"), comment("id2")));

        when(view.getCheckedModelRows()).thenReturn(new ArrayList<>(List.of(0)));
        when(view.confirmDelete(1)).thenReturn(true);
        when(view.getCommentIdAt(0)).thenReturn("id1");

        deleteSelectedButton.doClick();

        assertEquals(1, model.getComments().size());
        assertEquals("id2", model.getComments().get(0).id());
        verify(view).loadComments(model.getComments());
        verify(view).setStatus(contains("Removed 1"));
    }

    @Test
    void deleteSelectedButton_withoutLogin_deletionCancelledLeavesModelUnchanged() {
        model.setComments(List.of(comment("id1")));

        when(view.getCheckedModelRows()).thenReturn(new ArrayList<>(List.of(0)));
        when(view.confirmDelete(1)).thenReturn(false); // user cancels

        deleteSelectedButton.doClick();

        assertEquals(1, model.getComments().size());
        verify(view, never()).loadComments(any());
    }

    @Test
    void deleteFilteredButton_withoutLogin_removesAllVisibleComments() {
        model.setComments(List.of(comment("id1"), comment("id2"), comment("id3")));

        when(view.getVisibleRowCount()).thenReturn(3);
        when(view.confirmBatchDelete(3)).thenReturn(true);
        when(view.getVisibleModelRows()).thenReturn(new ArrayList<>(List.of(0, 1, 2)));
        when(view.getCommentIdAt(0)).thenReturn("id1");
        when(view.getCommentIdAt(1)).thenReturn("id2");
        when(view.getCommentIdAt(2)).thenReturn("id3");

        deleteFilteredButton.doClick();

        assertTrue(model.getComments().isEmpty());
        verify(view).loadComments(List.of());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Comment comment(String id) {
        return new Comment(id, "user", "channel", "video", "text", "2024-01-01",
                "https://www.youtube.com/watch?v=video");
    }
}