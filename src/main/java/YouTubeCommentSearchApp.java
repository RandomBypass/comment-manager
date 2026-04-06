import config.KeyringStore;
import dto.Comment;
import youtube.TakeoutImporter;
import youtube.YouTubeClient;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.PatternSyntaxException;

public class YouTubeCommentSearchApp extends JFrame {
    private JTextField userSearchField;
    private JTextField channelFilterField;
    private JTextField videoFilterField;
    private JTable commentsTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private JButton searchButton;
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

        // Search and filter panel
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        topPanel.setBorder(BorderFactory.createTitledBorder("Search & Filters"));

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        topPanel.add(new JLabel("Search User:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        userSearchField = new JTextField(15);
        topPanel.add(userSearchField, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchButton = new JButton("Search");
        topPanel.add(searchButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.anchor = GridBagConstraints.WEST;
        topPanel.add(new JLabel("Filter by Channel:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        channelFilterField = new JTextField(15);
        topPanel.add(channelFilterField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        topPanel.add(new JLabel("Filter by Video:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        videoFilterField = new JTextField(15);
        topPanel.add(videoFilterField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        clearFiltersButton = new JButton("Clear Filters");
        topPanel.add(clearFiltersButton, gbc);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(loginPanel, BorderLayout.NORTH);
        northPanel.add(topPanel, BorderLayout.CENTER);
        add(northPanel, BorderLayout.NORTH);

        // Table — column 6 ("ID") is kept in the model but hidden from the view
        // so comment IDs are available for API delete calls without cluttering the UI.
        String[] columns = {"Select", "User", "Channel", "Video/Post", "Comment", "Date", "ID"};
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
        commentsTable.removeColumn(commentsTable.getColumnModel().getColumn(6)); // hide ID column

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
        searchButton.addActionListener(e -> searchComments());
        clearFiltersButton.addActionListener(e -> clearFilters());
        deleteSelectedButton.addActionListener(e -> deleteSelectedComments());
        deleteFilteredButton.addActionListener(e -> deleteFilteredComments());
        channelFilterField.addActionListener(e -> applyFilters());
        videoFilterField.addActionListener(e -> applyFilters());
        userSearchField.addActionListener(e -> searchComments());
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

    private void setImportingState(boolean importing) {
        loginButton.setEnabled(!importing);
        importButton.setEnabled(!importing);
        searchButton.setEnabled(!importing);
        clearFiltersButton.setEnabled(!importing);
        deleteSelectedButton.setEnabled(!importing && currentUser != null);
        deleteFilteredButton.setEnabled(!importing && currentUser != null);
    }

    private void searchComments() {
        String searchUser = userSearchField.getText().trim();

        if (searchUser.isEmpty()) {
            loadAllComments();
            statusLabel.setText("Showing all comments. Enter a username to search.");
            return;
        }

        tableModel.setRowCount(0);
        List<Comment> userComments = new ArrayList<>();
        for (Comment comment : allComments) {
            if (comment.user().toLowerCase().contains(searchUser.toLowerCase())) {
                userComments.add(comment);
            }
        }

        for (Comment comment : userComments) {
            tableModel.addRow(new Object[]{
                    false, comment.user(), comment.channel(), comment.video(),
                    comment.text(), comment.date(), comment.id()
            });
        }

        statusLabel.setText("Found " + userComments.size() + " comments for user: " + searchUser);
        applyFilters();
    }

    private void applyFilters() {
        String channelFilter = channelFilterField.getText().trim();
        String videoFilter = videoFilterField.getText().trim();
        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        try {
            if (!channelFilter.isEmpty()) {
                filters.add(RowFilter.regexFilter("(?i)" + channelFilter, 2));
            }
            if (!videoFilter.isEmpty()) {
                filters.add(RowFilter.regexFilter("(?i)" + videoFilter, 3));
            }
            rowSorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));

            int visibleRows = commentsTable.getRowCount();
            if (!channelFilter.isEmpty() || !videoFilter.isEmpty()) {
                statusLabel.setText("Showing " + visibleRows + " comments after filtering");
            }
        } catch (PatternSyntaxException e) {
            statusLabel.setText("Invalid filter pattern");
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
            ids.add((String) tableModel.getValueAt(row, 6));
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

        setImportingState(false);
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
                setImportingState(true);
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
                    comment.text(), comment.date(), comment.id()
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
