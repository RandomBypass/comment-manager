package edu.random.bypass.service;

import edu.random.bypass.dto.Comment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TakeoutImporterTest {

    @TempDir
    Path tempDir;

    // ── Standard format (8 columns) ───────────────────────────────────────────

    @Test
    void standardFormat_parsesVideoComment() throws IOException {
        // 8-column header → standard format; video ID in col 5, text JSON in col 6
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level comment ID\n"
                        + "id001,chanId,2024-01-15T10:00:00+00:00,,,vid123,\"{\"\"text\"\":\"\"Hello world\"\"}\",id001\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        Comment c = result.get(0);
        assertEquals("id001", c.id());
        assertEquals("testUser", c.user());
        assertEquals("chanId", c.channel());
        assertEquals("vid123", c.video());
        assertEquals("Hello world", c.text());
        assertEquals("2024-01-15", c.date());
        assertEquals("https://www.youtube.com/watch?v=vid123", c.videoUrl());
    }

    @Test
    void standardFormat_multiSegmentText_concatenatesSegments() throws IOException {
        // Multiple {"text":"..."} objects in one JSON field
        String textJson = "{\"\"text\"\":\"\"Hello \"\"},{\"\"text\"\":\"\"world\"\"}";
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id002,ch,2024-01-01T00:00:00+00:00,,,vid1,\"" + textJson + "\",id002\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        assertEquals("Hello world", result.get(0).text());
    }

    // ── Extended format (9 columns) ───────────────────────────────────────────

    @Test
    void extendedFormat_parsesVideoComment() throws IOException {
        // 9-column header → extended format; post ID in col 5, video ID in col 6, text in col 7
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Post ID,Video ID,Comment text,Top-level\n"
                        + "id003,chanId,2024-03-10T12:00:00+00:00,,,,vid456,\"{\"\"text\"\":\"\"Video comment\"\"}\",id003\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        Comment c = result.get(0);
        assertEquals("vid456", c.video());
        assertEquals("Video comment", c.text());
        assertEquals("https://www.youtube.com/watch?v=vid456", c.videoUrl());
    }

    @Test
    void extendedFormat_parsesPostComment() throws IOException {
        // Post ID present, video ID empty → link to community post
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Post ID,Video ID,Comment text,Top-level\n"
                        + "id004,chanId,2024-02-20T08:00:00+00:00,,,postXYZ,,\"{\"\"text\"\":\"\"Post comment\"\"}\",id004\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        Comment c = result.get(0);
        assertEquals("postXYZ", c.video());
        assertEquals("Post comment", c.text());
        assertEquals("https://www.youtube.com/post/postXYZ", c.videoUrl());
    }

    // ── CSV parsing edge cases ────────────────────────────────────────────────

    @Test
    void quotedField_withEmbeddedComma() throws IOException {
        // Channel name contains a comma — must be quoted
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id005,\"Chan, Jr.\",2024-01-01T00:00:00+00:00,,,vid1,\"{\"\"text\"\":\"\"Hi\"\"}\",id005\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        assertEquals("Chan, Jr.", result.get(0).channel());
    }

    @Test
    void quotedField_withDoubledQuoteEscape() throws IOException {
        // Doubled-quote inside a quoted field represents a literal quote
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id006,\"She said \"\"hi\"\"\",2024-01-01T00:00:00+00:00,,,vid1,\"{\"\"text\"\":\"\"text\"\"}\",id006\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        assertEquals("She said \"hi\"", result.get(0).channel());
    }

    @Test
    void crlfLineEndings_parsedCorrectly() throws IOException {
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\r\n"
                        + "id007,ch,2024-01-01T00:00:00+00:00,,,vid1,\"{\"\"text\"\":\"\"CRLF test\"\"}\",id007\r\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        assertEquals("CRLF test", result.get(0).text());
    }

    @Test
    void utf8Bom_isStripped() throws IOException {
        // UTF-8 BOM: EF BB BF prepended to the file
        String content =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id008,ch,2024-01-01T00:00:00+00:00,,,vid1,\"{\"\"text\"\":\"\"BOM test\"\"}\",id008\n";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] withBom = new byte[bom.length + contentBytes.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(contentBytes, 0, withBom, bom.length, contentBytes.length);

        List<Comment> result = TakeoutImporter.importFromCsv(writeBytes(withBom), "testUser");

        assertEquals(1, result.size());
        assertEquals("BOM test", result.get(0).text());
    }

    // ── Filtering / skipping ──────────────────────────────────────────────────

    @Test
    void rowsWithEmptyText_areSkipped() throws IOException {
        // Row with empty JSON text field should not produce a Comment
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id009,ch,2024-01-01T00:00:00+00:00,,,vid1,\"{}\",id009\n";

        List<Comment> result = importCsv(csv);

        assertTrue(result.isEmpty());
    }

    @Test
    void rowsWithTooFewColumns_areSkipped() throws IOException {
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id010,ch\n"; // only 2 columns

        List<Comment> result = importCsv(csv);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyFile_returnsEmptyList() throws IOException {
        List<Comment> result = importCsv("");
        assertTrue(result.isEmpty());
    }

    // ── Date parsing ──────────────────────────────────────────────────────────

    @Test
    void isoOffsetDateTime_parsedToYyyyMmDd() throws IOException {
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id011,ch,2023-07-04T15:30:00+03:00,,,vid1,\"{\"\"text\"\":\"\"hi\"\"}\",id011\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        assertEquals("2023-07-04", result.get(0).date());
    }

    @Test
    void unparsableDate_keptAsIs() throws IOException {
        String csv =
                "Comment ID,Channel ID,Date,Price,Parent comment ID,Video ID,Comment text,Top-level\n"
                        + "id012,ch,2023-08-12,,,vid1,\"{\"\"text\"\":\"\"hi\"\"}\",id012\n";

        List<Comment> result = importCsv(csv);

        assertEquals(1, result.size());
        assertEquals("2023-08-12", result.get(0).date());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Comment> importCsv(String content) throws IOException {
        return TakeoutImporter.importFromCsv(write(content), "testUser");
    }

    private File write(String content) throws IOException {
        Path file = tempDir.resolve("comments.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }

    private File writeBytes(byte[] bytes) throws IOException {
        Path file = tempDir.resolve("comments.csv");
        Files.write(file, bytes);
        return file.toFile();
    }
}