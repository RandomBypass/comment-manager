import dto.Comment;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.*;
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
    private JLabel statusLabel;
    private JLabel userLabel;

    // User permissions
    private String currentUser = null;
    private final Set<String> adminUsers = Set.of("admin", "moderator", "channel_owner");
    private final Set<String> channelModerators = new HashSet<>();

    // Sample data - in real app, this would come from YouTube API
    private List<Comment> allComments;

    public YouTubeCommentSearchApp() {
        initializeData();
        setupUI();
        setupEventHandlers();
        setVisible(true);
    }

    private void initializeData() {
        // Sample comments data - replace with actual YouTube API calls
        allComments = new ArrayList<>(Arrays.asList(
                new Comment("john_doe", "TechChannel", "How to Code in Java", "Great tutorial! Very helpful.", "2024-01-15"),
                new Comment("jane_smith", "TechChannel", "Python vs Java", "I prefer Python for beginners", "2024-01-10"),
                new Comment("john_doe", "MusicChannel", "Best Songs 2024", "Love this playlist!", "2024-01-12"),
                new Comment("alex_dev", "TechChannel", "How to Code in Java", "Thanks for the clear explanation", "2024-01-14"),
                new Comment("john_doe", "GameChannel", "Top 10 Games", "Minecraft should be #1", "2024-01-08"),
                new Comment("sarah_codes", "TechChannel", "Web Development Basics", "HTML is easier than I thought", "2024-01-11"),
                new Comment("john_doe", "TechChannel", "Web Development Basics", "CSS is tricky though", "2024-01-11"),
                new Comment("mike_gamer", "GameChannel", "Top 10 Games", "What about Fortnite?", "2024-01-09"),
                new Comment("jane_smith", "MusicChannel", "Best Songs 2024", "Missing some classics here", "2024-01-13"),
                new Comment("john_doe", "NewsChannel", "Tech News Weekly", "AI is changing everything", "2024-01-16")
        ));

        // Setup channel moderators (users who can delete comments on specific channels)
        channelModerators.add("TechChannel:admin");
        channelModerators.add("TechChannel:sarah_codes");
        channelModerators.add("GameChannel:mike_gamer");
    }

    private void setupUI() {
        setTitle("YouTube Comment Search Tool");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(1000, 700);
        setLocationRelativeTo(null);

        // User login panel
        JPanel loginPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        loginPanel.setBorder(BorderFactory.createTitledBorder("User Authentication"));

        userLabel = new JLabel("Not logged in");
        loginButton = new JButton("Login");
        loginPanel.add(new JLabel("Current User: "));
        loginPanel.add(userLabel);
        loginPanel.add(loginButton);

        // Top panel for search and filters
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        topPanel.setBorder(BorderFactory.createTitledBorder("Search & Filters"));

        // User search
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

        // Channel filter
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.anchor = GridBagConstraints.WEST;
        topPanel.add(new JLabel("Filter by Channel:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        channelFilterField = new JTextField(15);
        topPanel.add(channelFilterField, gbc);

        // Video filter
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

        // Combine login and search panels
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(loginPanel, BorderLayout.NORTH);
        northPanel.add(topPanel, BorderLayout.CENTER);
        add(northPanel, BorderLayout.NORTH);

        // Table for displaying comments
        String[] columns = {"Select", "User", "Channel", "Video/Post", "Comment", "Date"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // Only checkbox column is editable
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }
        };

        commentsTable = new JTable(tableModel);
        commentsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        commentsTable.getColumnModel().getColumn(0).setPreferredWidth(50);  // Checkbox column
        commentsTable.getColumnModel().getColumn(4).setPreferredWidth(300); // Comment column wider

        rowSorter = new TableRowSorter<>(tableModel);
        commentsTable.setRowSorter(rowSorter);

        JScrollPane scrollPane = new JScrollPane(commentsTable);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom panel for delete operations
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

        // Status bar
        statusLabel = new JLabel("Ready. Please login to enable comment deletion.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(bottomPanel, BorderLayout.NORTH);
        southPanel.add(statusLabel, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);

        // Load all comments initially
        loadAllComments();
    }

    private void setupEventHandlers() {
        loginButton.addActionListener(e -> handleLogin());
        searchButton.addActionListener(e -> searchComments());
        clearFiltersButton.addActionListener(e -> clearFilters());
        deleteSelectedButton.addActionListener(e -> deleteSelectedComments());
        deleteFilteredButton.addActionListener(e -> deleteFilteredComments());

        // Real-time filtering as user types
        channelFilterField.addActionListener(e -> applyFilters());
        videoFilterField.addActionListener(e -> applyFilters());

        // Enter key triggers search
        userSearchField.addActionListener(e -> searchComments());
    }

    private void searchComments() {
        String searchUser = userSearchField.getText().trim();

        if (searchUser.isEmpty()) {
            loadAllComments();
            statusLabel.setText("Showing all comments. Enter a username to search.");
            return;
        }

        // Clear table
        tableModel.setRowCount(0);

        // Filter comments by user
        List<Comment> userComments = new ArrayList<>();
        for (Comment comment : allComments) {
            if (comment.user().toLowerCase().contains(searchUser.toLowerCase())) {
                userComments.add(comment);
            }
        }

        // Add filtered comments to table
        for (Comment comment : userComments) {
            Object[] row = {
                    false, // Checkbox
                    comment.user(),
                    comment.channel(),
                    comment.video(),
                    comment.text(),
                    comment.date()
            };
            tableModel.addRow(row);
        }

        statusLabel.setText("Found " + userComments.size() + " comments for user: " + searchUser);

        // Apply additional filters
        applyFilters();
    }

    private void applyFilters() {
        String channelFilter = channelFilterField.getText().trim();
        String videoFilter = videoFilterField.getText().trim();

        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        try {
            if (!channelFilter.isEmpty()) {
                filters.add(RowFilter.regexFilter("(?i)" + channelFilter, 2)); // Channel column (index 2)
            }

            if (!videoFilter.isEmpty()) {
                filters.add(RowFilter.regexFilter("(?i)" + videoFilter, 3)); // Video column (index 3)
            }

            if (filters.isEmpty()) {
                rowSorter.setRowFilter(null);
            } else {
                rowSorter.setRowFilter(RowFilter.andFilter(filters));
            }

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

    private void handleLogin() {
        if (currentUser == null) {
            // Show login dialog
            String[] users = {"admin", "moderator", "sarah_codes", "mike_gamer", "john_doe", "jane_smith"};
            String selectedUser = (String) JOptionPane.showInputDialog(
                    this,
                    "Select user to login as:",
                    "Login",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    users,
                    users[0]
            );

            if (selectedUser != null) {
                currentUser = selectedUser;
                userLabel.setText(currentUser + " (" + getUserRole(currentUser) + ")");
                loginButton.setText("Logout");
                updateDeleteButtonStates();
                statusLabel.setText("Logged in as " + currentUser + " with " + getUserRole(currentUser) + " permissions");
            }
        } else {
            // Logout
            currentUser = null;
            userLabel.setText("Not logged in");
            loginButton.setText("Login");
            deleteSelectedButton.setEnabled(false);
            deleteFilteredButton.setEnabled(false);
            statusLabel.setText("Logged out. Please login to enable comment deletion.");
        }
    }

    private String getUserRole(String user) {
        if (adminUsers.contains(user)) {
            return "Global Admin";
        }

        // Check if user is a channel moderator
        for (String moderator : channelModerators) {
            if (moderator.endsWith(":" + user)) {
                return "Channel Moderator";
            }
        }

        return "Regular User";
    }

    private boolean canDeleteComment(String commentUser, String channel) {
        if (currentUser == null) return false;

        // Users can always delete their own comments
        if (currentUser.equals(commentUser)) return true;

        // Global admins can delete any comment
        if (adminUsers.contains(currentUser)) return true;

        // Channel moderators can delete comments on their channels
        String moderatorKey = channel + ":" + currentUser;
        return channelModerators.contains(moderatorKey);
    }

    private void updateDeleteButtonStates() {
        boolean hasPermissions = currentUser != null;
        deleteSelectedButton.setEnabled(hasPermissions);
        deleteFilteredButton.setEnabled(hasPermissions);
    }

    private void deleteSelectedComments() {
        if (currentUser == null) {
            JOptionPane.showMessageDialog(this, "Please login first to delete comments.");
            return;
        }

        List<Integer> selectedRows = new ArrayList<>();
        List<String> unauthorizedComments = new ArrayList<>();

        // Find selected comments
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean selected = (Boolean) tableModel.getValueAt(i, 0);
            if (selected != null && selected) {
                int modelRow = commentsTable.convertRowIndexToModel(i);
                String commentUser = (String) tableModel.getValueAt(modelRow, 1);
                String channel = (String) tableModel.getValueAt(modelRow, 2);

                if (canDeleteComment(commentUser, channel)) {
                    selectedRows.add(modelRow);
                } else {
                    unauthorizedComments.add(commentUser + " on " + channel);
                }
            }
        }

        if (selectedRows.isEmpty() && unauthorizedComments.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No comments selected for deletion.");
            return;
        }

        StringBuilder message = new StringBuilder();
        if (!selectedRows.isEmpty()) {
            message.append("Delete ").append(selectedRows.size()).append(" selected comment(s)?");
        }
        if (!unauthorizedComments.isEmpty()) {
            if (message.length() > 0) message.append("\n\n");
            message.append("You don't have permission to delete ").append(unauthorizedComments.size())
                    .append(" comment(s):\n").append(String.join("\n", unauthorizedComments));
        }

        if (!selectedRows.isEmpty()) {
            int result = JOptionPane.showConfirmDialog(this, message.toString(), "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (result == JOptionPane.YES_OPTION) {
                deleteCommentsByTableRows(selectedRows);
                statusLabel.setText("Deleted " + selectedRows.size() + " comment(s)");
            }
        } else {
            JOptionPane.showMessageDialog(this, message.toString(), "No Permission", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteFilteredComments() {
        if (currentUser == null) {
            JOptionPane.showMessageDialog(this, "Please login first to delete comments.");
            return;
        }

        List<Integer> deletableRows = new ArrayList<>();
        int totalVisible = commentsTable.getRowCount();
        int unauthorized = 0;

        // Check all visible (filtered) comments
        for (int i = 0; i < totalVisible; i++) {
            int modelRow = commentsTable.convertRowIndexToModel(i);
            String commentUser = (String) tableModel.getValueAt(modelRow, 1);
            String channel = (String) tableModel.getValueAt(modelRow, 2);

            if (canDeleteComment(commentUser, channel)) {
                deletableRows.add(modelRow);
            } else {
                unauthorized++;
            }
        }

        if (deletableRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No comments in current filter can be deleted with your permissions.");
            return;
        }

        String message = String.format(
                "Delete %d filtered comment(s)?%s",
                deletableRows.size(),
                unauthorized > 0 ? String.format("\n(%d comment(s) will be skipped due to insufficient permissions)", unauthorized) : ""
        );

        int result = JOptionPane.showConfirmDialog(this, message, "Confirm Batch Deletion",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            deleteCommentsByTableRows(deletableRows);
            statusLabel.setText("Deleted " + deletableRows.size() + " filtered comment(s)" +
                    (unauthorized > 0 ? " (" + unauthorized + " skipped)" : ""));
        }
    }

    private void deleteCommentsByTableRows(List<Integer> tableRows) {
        // Sort in reverse order to avoid index shifting issues
        tableRows.sort((a, b) -> b.compareTo(a));

        // Remove from data source
        for (int tableRow : tableRows) {
            String user = (String) tableModel.getValueAt(tableRow, 1);
            String channel = (String) tableModel.getValueAt(tableRow, 2);
            String video = (String) tableModel.getValueAt(tableRow, 3);
            String text = (String) tableModel.getValueAt(tableRow, 4);
            String date = (String) tableModel.getValueAt(tableRow, 5);

            // Find and remove from allComments
            allComments.removeIf(comment ->
                    comment.user().equals(user) &&
                            comment.channel().equals(channel) &&
                            comment.video().equals(video) &&
                            comment.text().equals(text) &&
                            comment.date().equals(date)
            );

            // Remove from table model
            tableModel.removeRow(tableRow);
        }
    }

    private void loadAllComments() {
        tableModel.setRowCount(0);
        for (Comment comment : allComments) {
            Object[] row = {
                    false, // Checkbox
                    comment.user(),
                    comment.channel(),
                    comment.video(),
                    comment.text(),
                    comment.date()
            };
            tableModel.addRow(row);
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