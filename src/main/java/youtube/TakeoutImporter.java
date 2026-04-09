package youtube;

import dto.Comment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/**
 * Imports YouTube comments from a Google Takeout comments CSV export.
 *
 * <p>Two CSV formats are supported (headers are localized but column order is fixed):</p>
 *
 * <p><b>Standard format</b> (8 columns — video comments only):</p>
 * <pre>
 *  0  Comment ID
 *  1  Channel ID         (channel where the comment was posted)
 *  2  Date published
 *  3  Price              (Super Chat amount — ignored)
 *  4  Parent comment ID  (ignored)
 *  5  Video ID
 *  6  Comment text       (JSON segments: {"text":"…"},{"text":"…","mention":{…}}, …)
 *  7  Top comment ID     (ignored)
 * </pre>
 *
 * <p><b>Extended format</b> (9 columns — video and post comments):</p>
 * <pre>
 *  0  Comment ID
 *  1  Channel ID
 *  2  Date published
 *  3  Price              (ignored)
 *  4  Parent comment ID  (ignored)
 *  5  Post ID            (YouTube Community post ID; empty for video comments)
 *  6  Video ID           (empty for post comments)
 *  7  Comment text       (JSON segments)
 *  8  Top comment ID     (ignored)
 * </pre>
 *
 * <p>The format is detected automatically from the header column count.</p>
 */
public class TakeoutImporter {

    private static final int COL_ID      = 0;
    private static final int COL_CHANNEL = 1;
    private static final int COL_DATE    = 2;

    // Standard format (8 cols)
    private static final int STD_COL_VIDEO_ID  = 5;
    private static final int STD_COL_TEXT_JSON = 6;
    private static final int STD_MIN_COLUMNS   = 7;

    // Extended format (9 cols, adds Post ID before Video ID)
    private static final int EXT_COL_POST_ID   = 5;
    private static final int EXT_COL_VIDEO_ID  = 6;
    private static final int EXT_COL_TEXT_JSON = 7;
    private static final int EXT_MIN_COLUMNS   = 8;
    private static final int EXT_HEADER_COLS   = 9;

    private static final DateTimeFormatter DATE_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private static final List<DateTimeFormatter> DATE_PARSERS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
    );

    // Matches every "text":"…" segment in the comment JSON; handles escaped chars inside the value
    private static final Pattern TEXT_SEGMENT =
            Pattern.compile("\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Parses the Takeout CSV and returns one {@link Comment} per data row.
     *
     * @param csvFile    the CSV file selected by the user
     * @param authorName display name to assign to all rows (the account owner)
     */
    public static List<Comment> importFromCsv(File csvFile, String authorName) throws IOException {
        List<Comment> comments = new ArrayList<>();
        List<List<String>> rows = parseCsv(csvFile);
        if (rows.isEmpty()) return comments;

        // Detect format from header column count
        boolean extended = rows.get(0).size() >= EXT_HEADER_COLS;
        int colVideoId  = extended ? EXT_COL_VIDEO_ID  : STD_COL_VIDEO_ID;
        int colTextJson = extended ? EXT_COL_TEXT_JSON : STD_COL_TEXT_JSON;
        int minColumns  = extended ? EXT_MIN_COLUMNS   : STD_MIN_COLUMNS;

        // Row 0 is the header (localized); skip it
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < minColumns) continue;

            String id      = cell(row, COL_ID);
            String channel = cell(row, COL_CHANNEL);
            String date    = parseDate(cell(row, COL_DATE));
            String videoId = cell(row, colVideoId);
            String postId  = extended ? cell(row, EXT_COL_POST_ID) : "";
            String text    = extractText(cell(row, colTextJson));

            if (text.isEmpty()) continue;

            String contentId;
            String url;
            if (!videoId.isEmpty()) {
                contentId = videoId;
                url = "https://www.youtube.com/watch?v=" + videoId;
            } else if (!postId.isEmpty()) {
                contentId = postId;
                url = "https://www.youtube.com/post/" + postId;
            } else {
                contentId = "";
                url = "";
            }

            comments.add(new Comment(id, authorName, channel, contentId, text, date, url));
        }

        return comments;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String cell(List<String> row, int idx) {
        return idx < row.size() ? row.get(idx).trim() : "";
    }

    /**
     * Extracts and concatenates all {@code "text"} values from the comment JSON field.
     *
     * <p>The field contains one or more JSON objects (not wrapped in an array):
     * {@code {"text":"hello"},{"text":" world","mention":{…}}}. Only the {@code "text"}
     * keys are relevant; mention metadata is discarded.</p>
     */
    private static String extractText(String jsonSegments) {
        Matcher m = TEXT_SEGMENT.matcher(jsonSegments);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString().trim();
    }

    private static String parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_PARSERS) {
            try {
                return DATE_OUT.format(fmt.parse(raw, Instant::from));
            } catch (DateTimeParseException ignored) {
            }
        }
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    // ── RFC 4180 CSV parser ───────────────────────────────────────────────────

    /**
     * Parses an entire CSV file into a list of rows. Handles:
     * <ul>
     *   <li>Quoted fields containing commas or literal newlines</li>
     *   <li>Doubled-quote escape sequences ({@code ""} → {@code "})</li>
     *   <li>UTF-8 BOM at the start of the file</li>
     *   <li>Both CRLF and LF line endings</li>
     * </ul>
     */
    private static List<List<String>> parseCsv(File file) throws IOException {
        byte[] raw;
        try (FileInputStream fis = new FileInputStream(file)) {
            raw = fis.readAllBytes();
        }

        // Strip UTF-8 BOM (EF BB BF) if present
        int offset = (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) ? 3 : 0;

        String content = new String(raw, offset, raw.length - offset, StandardCharsets.UTF_8);

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"'); // escaped quote: "" → "
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                switch (c) {
                    case '"'  -> inQuotes = true;
                    case ','  -> { row.add(field.toString()); field.setLength(0); }
                    case '\r' -> { /* skip – handled by \n */ }
                    case '\n' -> {
                        row.add(field.toString());
                        field.setLength(0);
                        rows.add(new ArrayList<>(row));
                        row.clear();
                    }
                    default -> field.append(c);
                }
            }
        }

        // Flush the final field/row (file may not end with newline)
        row.add(field.toString());
        if (row.stream().anyMatch(s -> !s.isEmpty())) {
            rows.add(row);
        }

        return rows;
    }
}