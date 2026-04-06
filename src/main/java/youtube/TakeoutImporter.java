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
 * <p>Google Takeout exports are available at takeout.google.com under
 * "YouTube and YouTube Music → comments". Column headers are localized but
 * the column order is consistent across all languages:</p>
 *
 * <pre>
 *  0  Comment ID
 *  1  Channel ID         (channel where the comment was posted)
 *  2  Date published
 *  3  Price              (Super Chat amount, usually 0 — ignored)
 *  4  Parent comment ID  (empty for top-level comments — ignored)
 *  5  Video ID
 *  6  Comment text       (JSON segments: {"text":"…"},{"text":"…","mention":{…}}, …)
 *  7  Top comment ID     (ignored)
 * </pre>
 */
public class TakeoutImporter {

    private static final int COL_ID        = 0;
    private static final int COL_CHANNEL   = 1;
    private static final int COL_DATE      = 2;
    private static final int COL_VIDEO_ID  = 5;
    private static final int COL_TEXT_JSON = 6;

    private static final int MIN_COLUMNS = COL_TEXT_JSON + 1;

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

        // Row 0 is the header (localized); skip it
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < MIN_COLUMNS) continue;

            String id      = cell(row, COL_ID);
            String channel = cell(row, COL_CHANNEL);
            String date    = parseDate(cell(row, COL_DATE));
            String videoId = cell(row, COL_VIDEO_ID);
            String text    = extractText(cell(row, COL_TEXT_JSON));

            if (text.isEmpty()) continue;

            String videoUrl = videoId.isEmpty() ? "" : "https://www.youtube.com/watch?v=" + videoId;
            comments.add(new Comment(id, authorName, channel, videoId, text, date, videoUrl));
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
        byte[] raw = new FileInputStream(file).readAllBytes();

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