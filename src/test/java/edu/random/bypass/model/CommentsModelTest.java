package edu.random.bypass.model;

import edu.random.bypass.dto.Comment;
import edu.random.bypass.integration.YouTubeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CommentsModelTest {

    private CommentsModel model;

    @BeforeEach
    void setUp() {
        model = new CommentsModel();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initiallyEmpty() {
        assertTrue(model.getComments().isEmpty());
        assertNull(model.getCurrentUser());
        assertNull(model.getClient());
        assertFalse(model.isLoggedIn());
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    @Test
    void setAndGetComments_returnsAllComments() {
        List<Comment> comments = List.of(
                comment("id1", "Hello"),
                comment("id2", "World")
        );
        model.setComments(comments);
        assertEquals(2, model.getComments().size());
        assertEquals("id1", model.getComments().get(0).id());
        assertEquals("id2", model.getComments().get(1).id());
    }

    @Test
    void getComments_returnsImmutableView() {
        model.setComments(List.of(comment("id1", "text")));
        assertThrows(UnsupportedOperationException.class,
                () -> model.getComments().add(comment("id2", "text")));
    }

    @Test
    void setComments_isDefensiveCopy() {
        List<Comment> original = new ArrayList<>();
        original.add(comment("id1", "text"));
        model.setComments(original);

        original.clear(); // mutate the original list

        assertEquals(1, model.getComments().size(), "Model should not be affected by changes to the source list");
    }

    @Test
    void removeComments_removesMatchingIds() {
        model.setComments(List.of(
                comment("id1", "keep me"),
                comment("id2", "delete me"),
                comment("id3", "delete me too")
        ));
        model.removeComments(Set.of("id2", "id3"));

        List<Comment> remaining = model.getComments();
        assertEquals(1, remaining.size());
        assertEquals("id1", remaining.get(0).id());
    }

    @Test
    void removeComments_ignoresUnknownIds() {
        model.setComments(List.of(comment("id1", "text")));
        model.removeComments(Set.of("nonexistent"));
        assertEquals(1, model.getComments().size());
    }

    @Test
    void removeComments_emptySetIsNoOp() {
        model.setComments(List.of(comment("id1", "text")));
        model.removeComments(Set.of());
        assertEquals(1, model.getComments().size());
    }

    // ── Auth state ────────────────────────────────────────────────────────────

    @Test
    void setCurrentUser_storesAndReturnsUser() {
        model.setCurrentUser("Alice");
        assertEquals("Alice", model.getCurrentUser());
    }

    @Test
    void isLoggedIn_falseByDefault() {
        assertFalse(model.isLoggedIn());
    }

    @Test
    void isLoggedIn_trueWhenClientSet() {
        model.setClient(mock(YouTubeClient.class));
        assertTrue(model.isLoggedIn());
    }

    @Test
    void isLoggedIn_falseAfterClientSetToNull() {
        model.setClient(mock(YouTubeClient.class));
        model.setClient(null);
        assertFalse(model.isLoggedIn());
    }

    @Test
    void clearAuthState_resetsEverything() {
        model.setComments(List.of(comment("id1", "text")));
        model.setCurrentUser("Alice");
        model.setClient(mock(YouTubeClient.class));

        model.clearAuthState();

        assertTrue(model.getComments().isEmpty());
        assertNull(model.getCurrentUser());
        assertNull(model.getClient());
        assertFalse(model.isLoggedIn());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Comment comment(String id, String text) {
        return new Comment(id, "user", "channel", "videoId", text, "2024-01-01",
                "https://www.youtube.com/watch?v=videoId");
    }
}