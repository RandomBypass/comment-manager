import config.KeyringStore;
import dto.Comment;
import youtube.TakeoutImporter;
import youtube.YouTubeClient;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

public class YouTubeCommentSearchApp extends JFrame {
    private JTextField channelFilterField;
    private JTextField videoFilterField;
    private JTable commentsTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private JButton clearFiltersButton;
    private JButton deleteSelectedButton;
    private JButton deleteFilteredButton;
    private JButton loginButton;
    private JButton importButton;
    private JLabel statusLabel;
    private JLabel userLabel;

    private String currentUser = null;
    private YouTubeClient youTubeClient = null;
    private List<Comment> allComments = new ArrayList<>();
    private final KeyringStore keyringStore = new KeyringStore();

    public YouTubeCommentSearchApp() {
        setupUI();
        setupEventHandlers();
        setVisible(true);
        autoLoginFromKeyring();
    }

    private void setupUI() {
        setTitle("YouTube Comment Manager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(1000, 700);
        setLocationRelativeTo(null);

        // Login panel
        JPanel loginPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        loginPanel.setBorder(BorderFactory.createTitledBorder("User Authentication"));

        userLabel = new JLabel("Not logged in");
        loginButton = new JButton("Login with Google");
        importButton = new JButton("Import from Takeout...");

        loginPanel.add(new JLabel("Account: "));
        loginPanel.add(userLabel);
        loginPanel.add(loginButton);
        loginPanel.add(importButton);

        // Filter panel
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        topPanel.setBorder(BorderFactory.createTitledBorder("Filters"));

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 4, 2, 4);
        topPanel.add(new JLabel("Channel:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        channelFilterField = new JTextField(20);
        topPanel.add(channelFilterField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        topPanel.add(new JLabel("Video/Post:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        videoFilterField = new JTextField(20);
        topPanel.add(videoFilterField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        clearFiltersButton = new JButton("Clear");
        topPanel.add(clearFiltersButton, gbc);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(loginPanel, BorderLayout.NORTH);
        northPanel.add(topPanel, BorderLayout.CENTER);
        add(northPanel, BorderLayout.NORTH);

        // Model columns: Select(0) User(1) Channel(2) Video/Post(3) Comment(4) Date(5) Link(6) ID(7)
        // Column 7 (ID) is hidden from the view — used only for API delete calls.
        String[] columns = {"Select", "User", "Channel", "Video/Post", "Comment", "Date", "Link", "ID"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }
        };

        commentsTable = new JTable(tableModel);
        commentsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        commentsTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        commentsTable.getColumnModel().getColumn(4).setPreferredWidth(300);
        commentsTable.getColumnModel().getColumn(6).setPreferredWidth(220);
        commentsTable.removeColumn(commentsTable.getColumnModel().getColumn(7)); // hide ID column

        // Render the Link column as a clickable blue hyperlink
        commentsTable.getColumnModel().getColumn(6).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(
                            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                        String url = value != null ? value.toString() : "";
                        setText(url.isEmpty() ? "" : "<html><u>" + url + "</u></html>");
                        setForeground(isSelected ? Color.WHITE : new Color(0, 102, 204));
                        return this;
                    }
                });

        // Open the video URL in the browser when the Link column is clicked
        commentsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = commentsTable.columnAtPoint(e.getPoint());
                if (commentsTable.convertColumnIndexToModel(viewCol) != 6) return;
                int viewRow = commentsTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                int modelRow = commentsTable.convertRowIndexToModel(viewRow);
                String url = (String) tableModel.getValueAt(modelRow, 6);
                if (url != null && !url.isEmpty()) {
                    try {
                        Desktop.getDesktop().browse(new URI(url));
                    } catch (Exception ex) {
                        statusLabel.setText("Could not open link: " + ex.getMessage());
                    }
                }
            }
        });

        // Change cursor to hand when hovering over the Link column
        commentsTable.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int viewCol = commentsTable.columnAtPoint(e.getPoint());
                boolean onLink = commentsTable.convertColumnIndexToModel(viewCol) == 6;
                commentsTable.setCursor(onLink
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });

        rowSorter = new TableRowSorter<>(tableModel);
        commentsTable.setRowSorter(rowSorter);

        JScrollPane scrollPane = new JScrollPane(commentsTable);
        add(scrollPane, BorderLayout.CENTER);

        // Delete panel
        JPanel bottomPanel = new JPanel(new FlowLayout());
        bottomPanel.setBorder(BorderFactory.createTitledBorder("Delete Operations"));

        deleteSelectedButton = new JButton("Delete Selected");
        deleteSelectedButton.setEnabled(false);
        deleteSelectedButton.setBackground(new Color(220, 53, 69));
        deleteSelectedButton.setForeground(Color.WHITE);

        deleteFilteredButton = new JButton("Delete All Filtered");
        deleteFilteredButton.setEnabled(false);
        deleteFilteredButton.setBackground(new Color(220, 53, 69));
        deleteFilteredButton.setForeground(Color.WHITE);

        bottomPanel.add(deleteSelectedButton);
        bottomPanel.add(deleteFilteredButton);

        statusLabel = new JLabel("Import a Google Takeout comments CSV to view comments. Login with Google to enable deletion.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(bottomPanel, BorderLayout.NORTH);
        southPanel.add(statusLabel, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);
    }

    private void setupEventHandlers() {
        loginButton.addActionListener(e -> handleLogin());
        importButton.addActionListener(e -> importFromTakeout());
        clearFiltersButton.addActionListener(e -> clearFilters());
        deleteSelectedButton.addActionListener(e -> deleteSelectedComments());
        deleteFilteredButton.addActionListener(e -> deleteFilteredComments());
        addRealTimeFilter(channelFilterField);
        addRealTimeFilter(videoFilterField);
    }

    /** Applies filters on every keystroke in the given field. */
    private void addRealTimeFilter(JTextField field) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
        });
    }

    /**
     * Attempts silent background login using client secrets stored in the OS keyring.
     * If a valid refresh token is cached no browser interaction is needed.
     * Does nothing if no secrets are in the keyring yet.
     */
    private void autoLoginFromKeyring() {
        String json = keyringStore.load();
        if (json == null) return;
        statusLabel.setText("Authenticating in background...");
        loginWithYouTube(json);
    }

    private void handleLogin() {
        if (currentUser == null) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select client_secrets.json (download from Google Cloud Console \u2192 APIs & Services \u2192 Credentials)");
            fileChooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
            if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                String json = Files.readString(fileChooser.getSelectedFile().toPath());
                keyringStore.save(json);
                loginWithYouTube(json);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "Failed to read or secure credentials: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            youTubeClient = null;
            currentUser = null;
            allComments.clear();
            tableModel.setRowCount(0);
            userLabel.setText("Not logged in");
            loginButton.setText("Login with Google");
            deleteSelectedButton.setEnabled(false);
            deleteFilteredButton.setEnabled(false);
            statusLabel.setText("Logged out. Comments remain loaded; login again to enable deletion.");
        }
    }

    private void loginWithYouTube(String secretsJson) {
        statusLabel.setText("Opening browser for Google authorization...");
        loginButton.setEnabled(false);

        new SwingWorker<YouTubeClient, Void>() {
            @Override
            protected YouTubeClient doInBackground() throws Exception {
                return YouTubeClient.authenticate(secretsJson);
            }

            @Override
            protected void done() {
                loginButton.setEnabled(true);
                try {
                    youTubeClient = get();
                    currentUser = youTubeClient.getChannelTitle();
                    userLabel.setText(currentUser);
                    loginButton.setText("Logout");
                    deleteSelectedButton.setEnabled(true);
                    deleteFilteredButton.setEnabled(true);
                    statusLabel.setText("Logged in as " + currentUser + ". Import a Takeout CSV to load comments.");
                } catch (ExecutionException e) {
                    JOptionPane.showMessageDialog(YouTubeCommentSearchApp.this,
                            "Authentication failed: " + e.getCause().getMessage(),
                            "Login Error", JOptionPane.ERROR_MESSAGE);
                    statusLabel.setText("Authentication failed. Please try again.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Authentication was interrupted.");
                }
            }
        }.execute();
    }

    private void importFromTakeout() {
        JOptionPane.showMessageDialog(this,
                "<html><b>Expected file structure</b><br><br>" +
                "Export your comments from <b>takeout.google.com</b>:<br>" +
                "YouTube and YouTube Music \u2192 Comments<br><br>" +
                "Two CSV formats are supported (detected automatically):<br><br>" +
                "<b>Standard (8 columns) \u2014 video comments only:</b><br>" +
                "&nbsp;&nbsp;Comment ID, Channel ID, Date, Price, Parent comment ID,<br>" +
                "&nbsp;&nbsp;Video ID, Comment text, Top-level comment ID<br><br>" +
                "<b>Extended (9 columns) \u2014 video and post comments:</b><br>" +
                "&nbsp;&nbsp;Comment ID, Channel ID, Date, Price, Parent comment ID,<br>" +
                "&nbsp;&nbsp;<u>Post ID</u>, Video ID, Comment text, Top-level comment ID<br><br>" +
                "Column headers may be in any language; column order is fixed.</html>",
                "Import from Google Takeout",
                JOptionPane.INFORMATION_MESSAGE);

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Google Takeout comments CSV file");
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File csvFile = fileChooser.getSelectedFile();
        String authorName = currentUser != null ? currentUser : "me";

        setImportingState(true);

        new SwingWorker<List<Comment>, Void>() {
            @Override
            protected List<Comment> doInBackground() throws Exception {
                return TakeoutImporter.importFromCsv(csvFile, authorName);
            }

            @Override
            protected void done() {
                setImportingState(false);
                try {
                    allComments = new ArrayList<>(get());
                    loadAllComments();
                    statusLabel.setText("Imported " + allComments.size() + " comments from " + csvFile.getName() + ".");
                    resolveNamesInBackground();
                } catch (ExecutionException e) {
                    JOptionPane.showMessageDialog(YouTubeCommentSearchApp.this,
                            "Import failed: " + e.getCause().getMessage(),
                            "Import Error", JOptionPane.ERROR_MESSAGE);
                    statusLabel.setText("Import failed.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * If the user is logged in, fetches real video titles and channel names from the
     * YouTube API to replace the raw IDs stored by the Takeout importer.
     * Runs in the background; reloads the table when done.
     */
    /**
     * If the user is logged in, fetches real video titles and the names of the channels
     * where comments were published (via the video's owning channel, not the CSV's
     * "channel ID" column which holds the commenter's own channel ID).
     * Runs in the background; reloads the table when done.
     */
    private void resolveNamesInBackground() {
        if (youTubeClient == null || allComments.isEmpty()) return;

        // c.video() holds either a video ID or a post ID; only send real video IDs to the API
        Set<String> videoIds = new HashSet<>();
        for (Comment c : allComments) {
            if (c.videoUrl().contains("watch?v=")) videoIds.add(c.video());
        }

        statusLabel.setText("Resolving video titles and channel names...");

        new SwingWorker<Void, Void>() {
            Map<String, YouTubeClient.VideoInfo> videoInfo;

            @Override
            protected Void doInBackground() throws Exception {
                videoInfo = youTubeClient.fetchVideoInfo(videoIds);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    List<Comment> resolved = new ArrayList<>();
                    for (Comment c : allComments) {
                        YouTubeClient.VideoInfo info = videoInfo.get(c.video());
                        resolved.add(info == null ? c : new Comment(
                                c.id(), c.user(),
                                info.channelTitle(), // channel where the video/comment lives
                                info.title(),        // video title
                                c.text(), c.date(), c.videoUrl()
                        ));
                    }
                    allComments = resolved;
                    loadAllComments();
                    statusLabel.setText("Loaded " + allComments.size() + " comments with titles resolved.");
                } catch (Exception ignored) {
                    // Non-fatal: raw IDs remain as fallback values already shown in the table
                }
            }
        }.execute();
    }

    private void setImportingState(boolean importing) {
        loginButton.setEnabled(!importing);
        importButton.setEnabled(!importing);
        clearFiltersButton.setEnabled(!importing);
        deleteSelectedButton.setEnabled(!importing && currentUser != null);
        deleteFilteredButton.setEnabled(!importing && currentUser != null);
    }

    private void applyFilters() {
        String channelFilter = channelFilterField.getText().trim();
        String videoFilter   = videoFilterField.getText().trim();
        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        if (!channelFilter.isEmpty()) {
            // Pattern.quote treats the input as a literal string (no regex special chars)
            filters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(channelFilter), 2));
        }
        if (!videoFilter.isEmpty()) {
            filters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(videoFilter), 3));
        }

        rowSorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));

        int visible = commentsTable.getRowCount();
        int total   = tableModel.getRowCount();
        if (!channelFilter.isEmpty() || !videoFilter.isEmpty()) {
            statusLabel.setText("Showing " + visible + " of " + total + " comments");
        } else if (total > 0) {
            statusLabel.setText("Showing all " + total + " comments");
        }
    }

    private void clearFilters() {
        channelFilterField.setText("");
        videoFilterField.setText("");
        rowSorter.setRowFilter(null);
        statusLabel.setText("Filters cleared");
    }

    private void deleteSelectedComments() {
        List<Integer> selectedRows = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean selected = (Boolean) tableModel.getValueAt(i, 0);
            if (selected != null && selected) {
                selectedRows.add(commentsTable.convertRowIndexToModel(i));
            }
        }

        if (selectedRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No comments selected for deletion.");
            return;
        }

        int result = JOptionPane.showConfirmDialog(this,
                "Delete " + selectedRows.size() + " selected comment(s) via YouTube API?",
                "Confirm Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            deleteCommentsByTableRows(selectedRows);
        }
    }

    private void deleteFilteredComments() {
        int totalVisible = commentsTable.getRowCount();
        if (totalVisible == 0) {
            JOptionPane.showMessageDialog(this, "No comments visible to delete.");
            return;
        }

        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < totalVisible; i++) {
            rows.add(commentsTable.convertRowIndexToModel(i));
        }

        int result = JOptionPane.showConfirmDialog(this,
                "Delete all " + totalVisible + " visible comment(s) via YouTube API?",
                "Confirm Batch Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            deleteCommentsByTableRows(rows);
        }
    }

    private void deleteCommentsByTableRows(List<Integer> tableRows) {
        // Sort descending so removals from high indices don't shift lower ones
        tableRows.sort(Collections.reverseOrder());

        // Capture comment data before the background operation starts
        List<String> ids = new ArrayList<>();
        List<Integer> modelRows = new ArrayList<>();
        for (int row : tableRows) {
            ids.add((String) tableModel.getValueAt(row, 7)); // model col 7 = hidden ID
            modelRows.add(row);
        }

        if (youTubeClient == null) {
            // Fallback: no API client, remove from in-memory state only
            for (int i = 0; i < modelRows.size(); i++) {
                final String id = ids.get(i);
                allComments.removeIf(c -> c.id().equals(id));
                tableModel.removeRow(modelRows.get(i));
            }
            statusLabel.setText("Deleted " + modelRows.size() + " comment(s).");
            return;
        }

        setImportingState(true);
        statusLabel.setText("Deleting " + tableRows.size() + " comment(s) via YouTube API...");

        new SwingWorker<Set<String>, Void>() {
            @Override
            protected Set<String> doInBackground() {
                Set<String> deleted = new LinkedHashSet<>();
                for (String commentId : ids) {
                    if (commentId != null && !commentId.isEmpty()) {
                        try {
                            youTubeClient.deleteComment(commentId);
                            deleted.add(commentId);
                        } catch (IOException ignored) {
                        }
                    }
                }
                return deleted;
            }

            @Override
            protected void done() {
                setImportingState(false);
                try {
                    Set<String> deletedIds = get();

                    // Remove successfully deleted rows (descending order is already maintained)
                    for (int i = 0; i < modelRows.size(); i++) {
                        if (deletedIds.contains(ids.get(i))) {
                            tableModel.removeRow(modelRows.get(i));
                        }
                    }
                    allComments.removeIf(c -> deletedIds.contains(c.id()));

                    int failed = tableRows.size() - deletedIds.size();
                    if (failed > 0) {
                        statusLabel.setText("Deleted " + deletedIds.size() + " comment(s). "
                                + failed + " could not be deleted (insufficient permissions or API error).");
                    } else {
                        statusLabel.setText("Successfully deleted " + deletedIds.size() + " comment(s).");
                    }
                } catch (ExecutionException | InterruptedException e) {
                    statusLabel.setText("Error during deletion: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void loadAllComments() {
        tableModel.setRowCount(0);
        for (Comment comment : allComments) {
            tableModel.addRow(new Object[]{
                    false, comment.user(), comment.channel(), comment.video(),
                    comment.text(), comment.date(), comment.videoUrl(), comment.id()
            });
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new YouTubeCommentSearchApp();
        });
    }
}
