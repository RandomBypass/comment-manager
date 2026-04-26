package edu.random.bypass.view;

import edu.random.bypass.dto.Comment;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The application's main window (View in MVC).
 * <p>Owns all Swing components and exposes a clean API for the edu.random.bypass.controller to:
 * <ul>
 *   <li>update display state ({@link #setStatus}, {@link #onLoggedIn}, {@link #onLoggedOut}, {@link #setBusy})</li>
 *   <li>populate the table ({@link #loadComments})</li>
 *   <li>read user selections ({@link #getCheckedModelRows}, {@link #getVisibleModelRows}, {@link #getCommentIdAt})</li>
 *   <li>show dialogs ({@link #showImportFileChooser}, {@link #confirmDelete}, {@link #confirmBatchDelete}, {@link #showError})</li>
 * </ul>
 * Filter application, Clear button, and link-click handling are wired internally and require no edu.random.bypass.controller involvement.
 */
public class MainView extends JFrame {

    // ── Table column indices (edu.random.bypass.model) ──────────────────────────────────────────
    private static final int COL_SELECT = 0;
    private static final int COL_CHANNEL = 2;
    private static final int COL_VIDEO = 3;
    private static final int COL_COMMENT = 4;
    private static final int COL_DATE = 5;
    private static final int COL_LINK = 6;
    private static final int COL_ID = 7; // hidden

    private static final String IMPORT_INFO_HTML =
            "<html><b>Expected file structure</b><br><br>"
                    + "Export your comments from <b>takeout.google.com</b>:<br>"
                    + "YouTube and YouTube Music \u2192 Comments<br><br>"
                    + "Two CSV formats are supported (detected automatically):<br><br>"
                    + "<b>Standard (8 columns) \u2014 video comments only:</b><br>"
                    + "&nbsp;&nbsp;Comment ID, Channel ID, Date, Price, Parent comment ID,<br>"
                    + "&nbsp;&nbsp;Video ID, Comment text, Top-level comment ID<br><br>"
                    + "<b>Extended (9 columns) \u2014 video and post comments:</b><br>"
                    + "&nbsp;&nbsp;Comment ID, Channel ID, Date, Price, Parent comment ID,<br>"
                    + "&nbsp;&nbsp;<u>Post ID</u>, Video ID, Comment text, Top-level comment ID<br><br>"
                    + "Column headers may be in any language; column order is fixed.</html>";

    /**
     * Cost of one comments.delete call in quota units (mirrors YouTubeClient constant).
     */
    private static final int QUOTA_COST_PER_DELETE = 50;

    // ── Widgets exposed to controller ─────────────────────────────────────────
    private JButton loginButton;
    private JButton importButton;
    private JButton deleteSelectedButton;
    private JButton deleteFilteredButton;

    // ── Internal widgets ──────────────────────────────────────────────────────
    private JLabel userLabel;
    private JLabel quotaLabel;
    private JLabel statusLabel;

    // ── Button-state tracking ─────────────────────────────────────────────────
    private boolean loggedIn = false;
    private int quotaRemaining = 0;
    private JTextField channelFilterField;
    private JTextField videoFilterField;
    private JTextField commentFilterField;
    private JCheckBox dateFromCheck;
    private JSpinner dateFromSpinner;
    private JCheckBox dateToCheck;
    private JSpinner dateToSpinner;
    private JButton clearFiltersButton;
    private JTable commentsTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;

    public MainView() {
        setupUI();
        setupInternalListeners();
    }

    // ── Controller-facing API: display updates ────────────────────────────────

    public void setStatus(String message) {
        statusLabel.setText(message);
    }

    public void onLoggedIn(String userName) {
        loggedIn = true;
        userLabel.setText(userName);
        loginButton.setText("Logout");
        refreshDeleteButtons();
    }

    public void onLoggedOut() {
        loggedIn = false;
        quotaRemaining = 0;
        userLabel.setText("Not logged in");
        loginButton.setText("Login with Google");
        quotaLabel.setVisible(false);
        refreshDeleteButtons();
    }

    /**
     * Updates the quota display and re-evaluates delete-button availability.
     * Must be called after every successful YouTube API round-trip.
     *
     * @param used  cumulative quota units consumed this session
     * @param limit daily quota limit (typically 10,000)
     */
    public void updateQuota(int used, int limit) {
        quotaRemaining = limit - used;
        quotaLabel.setText(String.format("API quota: %,d\u202F/\u202F%,d remaining (est.)",
                quotaRemaining, limit));
        quotaLabel.setForeground(
                quotaRemaining < 200 ? Color.RED :
                        quotaRemaining < 1000 ? new Color(200, 100, 0) :
                                new Color(0, 150, 0));
        quotaLabel.setVisible(true);
        refreshDeleteButtons();
    }

    /**
     * Disables/enables all controls during a long-running background operation.
     *
     * @param busy     {@code true} while an operation is in progress
     * @param loggedIn whether the user is currently authenticated
     */
    public void setBusy(boolean busy, boolean loggedIn) {
        loginButton.setEnabled(!busy);
        importButton.setEnabled(!busy);
        clearFiltersButton.setEnabled(!busy);
        if (busy) {
            deleteSelectedButton.setEnabled(false);
            deleteFilteredButton.setEnabled(false);
        } else {
            refreshDeleteButtons();
        }
    }

    private void refreshDeleteButtons() {
        boolean canDelete = loggedIn && quotaRemaining >= QUOTA_COST_PER_DELETE;
        deleteSelectedButton.setEnabled(canDelete);
        deleteFilteredButton.setEnabled(canDelete);
    }

    // ── Controller-facing API: table management ───────────────────────────────

    public void loadComments(List<Comment> comments) {
        tableModel.setRowCount(0);
        for (Comment c : comments) {
            tableModel.addRow(new Object[]{
                    false, c.user(), c.channel(), c.video(),
                    c.text(), c.date(), c.videoUrl(), c.id()
            });
        }
    }

    // ── Controller-facing API: reading selections ─────────────────────────────

    /**
     * Returns edu.random.bypass.model-row indices whose Select checkbox is ticked.
     */
    public List<Integer> getCheckedModelRows() {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean checked = (Boolean) tableModel.getValueAt(i, COL_SELECT);
            if (checked != null && checked) rows.add(i);
        }
        return rows;
    }

    /**
     * Returns edu.random.bypass.model-row indices for all currently visible (non-filtered) rows.
     */
    public List<Integer> getVisibleModelRows() {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < commentsTable.getRowCount(); i++) {
            rows.add(commentsTable.convertRowIndexToModel(i));
        }
        return rows;
    }

    public int getVisibleRowCount() {
        return commentsTable.getRowCount();
    }

    /**
     * Returns the YouTube comment ID stored in the hidden edu.random.bypass.model column for the given edu.random.bypass.model row.
     */
    public String getCommentIdAt(int modelRow) {
        return (String) tableModel.getValueAt(modelRow, COL_ID);
    }

    // ── Controller-facing API: dialogs ────────────────────────────────────────

    /**
     * Shows the Takeout info dialog, then a file chooser.
     *
     * @param lastDir directory to open initially, or {@code null} for the default
     * @return the selected file, or {@code null} if the user cancelled
     */
    public File showImportFileChooser(String lastDir) {
        JOptionPane.showMessageDialog(this, IMPORT_INFO_HTML,
                "Import from Google Takeout", JOptionPane.INFORMATION_MESSAGE);

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select Google Takeout comments CSV file");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        if (lastDir != null) fc.setCurrentDirectory(new File(lastDir));
        return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }

    public boolean confirmDelete(int count) {
        return JOptionPane.showConfirmDialog(this,
                "Delete " + count + " selected comment(s) via YouTube API?",
                "Confirm Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    public boolean confirmBatchDelete(int count) {
        return JOptionPane.showConfirmDialog(this,
                "Delete all " + count + " visible comment(s) via YouTube API?",
                "Confirm Batch Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    public void showError(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    // ── Controller-facing API: button access ──────────────────────────────────

    public JButton getLoginButton() {
        return loginButton;
    }

    public JButton getImportButton() {
        return importButton;
    }

    public JButton getDeleteSelectedButton() {
        return deleteSelectedButton;
    }

    public JButton getDeleteFilteredButton() {
        return deleteFilteredButton;
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void setupUI() {
        setTitle("YouTube Comment Manager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(1000, 700);
        setLocationRelativeTo(null);

        add(buildNorthPanel(), BorderLayout.NORTH);
        add(buildTablePanel(), BorderLayout.CENTER);
        add(buildSouthPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildNorthPanel() {
        JPanel loginPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        loginPanel.setBorder(BorderFactory.createTitledBorder("User Authentication"));
        userLabel = new JLabel("Not logged in");
        loginButton = new JButton("Login with Google");
        importButton = new JButton("Import from Takeout...");
        quotaLabel = new JLabel();
        quotaLabel.setVisible(false);
        loginPanel.add(new JLabel("Account: "));
        loginPanel.add(userLabel);
        loginPanel.add(loginButton);
        loginPanel.add(importButton);
        loginPanel.add(Box.createHorizontalStrut(16));
        loginPanel.add(quotaLabel);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(loginPanel, BorderLayout.NORTH);
        northPanel.add(buildFilterPanel(), BorderLayout.CENTER);
        return northPanel;
    }

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Filters"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 4, 2, 4);

        // Row 0 — Channel
        channelFilterField = addFilterBox(gbc, 0, panel, "Channel:");

        // Row 1 — Video/Post
        videoFilterField = addFilterBox(gbc, 1, panel, "Video/Post:");

        // Row 2 — Comment
        commentFilterField = addFilterBox(gbc, 2, panel, "Comment:");

        // Row 3 — Date range + Clear
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        dateFromCheck = new JCheckBox("Date from:");
        panel.add(dateFromCheck, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        dateFromSpinner = makeDateSpinner();
        dateFromSpinner.setEnabled(false);
        panel.add(dateFromSpinner, gbc);
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        dateToCheck = new JCheckBox("to:");
        panel.add(dateToCheck, gbc);
        gbc.gridx = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        dateToSpinner = makeDateSpinner();
        dateToSpinner.setEnabled(false);
        panel.add(dateToSpinner, gbc);
        gbc.gridx = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        clearFiltersButton = new JButton("Clear");
        panel.add(clearFiltersButton, gbc);

        return panel;
    }

    private JTextField addFilterBox(GridBagConstraints gbc, int gridy, JPanel panel, String text) {
        gbc.gridx = 0;
        gbc.gridy = gridy;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel(text), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        JTextField filterField = new JTextField(20);
        panel.add(filterField, gbc);
        return filterField;
    }

    private JScrollPane buildTablePanel() {
        String[] columns = {"Select", "User", "Channel", "Video/Post", "Comment", "Date", "Link", "ID"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return col == COL_SELECT;
            }

            @Override
            public Class<?> getColumnClass(int col) {
                return col == COL_SELECT ? Boolean.class : String.class;
            }
        };

        commentsTable = new JTable(tableModel);
        commentsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        commentsTable.getColumnModel().getColumn(COL_SELECT).setPreferredWidth(50);
        commentsTable.getColumnModel().getColumn(COL_COMMENT).setPreferredWidth(300);
        commentsTable.getColumnModel().getColumn(COL_LINK).setPreferredWidth(220);
        commentsTable.removeColumn(commentsTable.getColumnModel().getColumn(COL_ID));

        commentsTable.getColumnModel().getColumn(COL_LINK).setCellRenderer(
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

        rowSorter = new TableRowSorter<>(tableModel);
        commentsTable.setRowSorter(rowSorter);

        return new JScrollPane(commentsTable);
    }

    private JPanel buildSouthPanel() {
        deleteSelectedButton = new JButton("Delete Selected");
        deleteSelectedButton.setEnabled(false);

        deleteFilteredButton = new JButton("Delete All Filtered");
        deleteFilteredButton.setEnabled(false);

        JPanel deletePanel = new JPanel(new FlowLayout());
        deletePanel.setBorder(BorderFactory.createTitledBorder("Delete Operations"));
        deletePanel.add(deleteSelectedButton);
        deletePanel.add(deleteFilteredButton);

        statusLabel = new JLabel(
                "Import a Google Takeout comments CSV to edu.random.bypass.view comments. Login with Google to enable deletion.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(deletePanel, BorderLayout.NORTH);
        southPanel.add(statusLabel, BorderLayout.SOUTH);
        return southPanel;
    }

    // ── Internal listeners (edu.random.bypass.view-only concerns) ───────────────────────────────

    private void setupInternalListeners() {
        // Real-time text filters
        addDocumentFilter(channelFilterField);
        addDocumentFilter(videoFilterField);
        addDocumentFilter(commentFilterField);

        // Date range spinners
        dateFromCheck.addActionListener(e -> {
            dateFromSpinner.setEnabled(dateFromCheck.isSelected());
            applyFilters();
        });
        dateFromSpinner.addChangeListener(e -> applyFilters());
        dateToCheck.addActionListener(e -> {
            dateToSpinner.setEnabled(dateToCheck.isSelected());
            applyFilters();
        });
        dateToSpinner.addChangeListener(e -> applyFilters());

        // Clear button
        clearFiltersButton.addActionListener(e -> clearFilters());

        // Link column — open in browser on click
        commentsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = commentsTable.columnAtPoint(e.getPoint());
                if (commentsTable.convertColumnIndexToModel(viewCol) != COL_LINK) return;
                int viewRow = commentsTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                String url = (String) tableModel.getValueAt(commentsTable.convertRowIndexToModel(viewRow), COL_LINK);
                if (url != null && !url.isEmpty()) {
                    try {
                        Desktop.getDesktop().browse(new URI(url));
                    } catch (Exception ex) {
                        setStatus("Could not open link: " + ex.getMessage());
                    }
                }
            }
        });

        // Link column — hand cursor on hover
        commentsTable.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int viewCol = commentsTable.columnAtPoint(e.getPoint());
                boolean onLink = commentsTable.convertColumnIndexToModel(viewCol) == COL_LINK;
                commentsTable.setCursor(onLink
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
    }

    private void addDocumentFilter(JTextField field) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyFilters();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyFilters();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyFilters();
            }
        });
    }

    private void applyFilters() {
        String channel = channelFilterField.getText().trim();
        String video = videoFilterField.getText().trim();
        String comment = commentFilterField.getText().trim();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        String dateFrom = dateFromCheck.isSelected() ? fmt.format((Date) dateFromSpinner.getValue()) : "";
        String dateTo = dateToCheck.isSelected() ? fmt.format((Date) dateToSpinner.getValue()) : "";

        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        if (!channel.isEmpty()) filters.add(RowFilter.regexFilter("(?iu)" + Pattern.quote(channel), COL_CHANNEL));
        if (!video.isEmpty()) filters.add(RowFilter.regexFilter("(?iu)" + Pattern.quote(video), COL_VIDEO));
        if (!comment.isEmpty()) filters.add(RowFilter.regexFilter("(?iu)" + Pattern.quote(comment), COL_COMMENT));
        if (!dateFrom.isEmpty() || !dateTo.isEmpty()) {
            filters.add(new RowFilter<>() {
                @Override
                public boolean include(Entry<?, ?> entry) {
                    String date = (String) entry.getValue(COL_DATE);
                    if (date == null || date.isEmpty()) return false;
                    if (!dateFrom.isEmpty() && date.compareTo(dateFrom) < 0) return false;
                    return dateTo.isEmpty() || date.compareTo(dateTo) <= 0;
                }
            });
        }

        rowSorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));

        boolean anyFilter = !channel.isEmpty() || !video.isEmpty() || !comment.isEmpty()
                || !dateFrom.isEmpty() || !dateTo.isEmpty();
        int visible = commentsTable.getRowCount();
        int total = tableModel.getRowCount();
        if (anyFilter) {
            statusLabel.setText("Showing " + visible + " of " + total + " comments");
        } else if (total > 0) {
            statusLabel.setText("Showing all " + total + " comments");
        }
    }

    private void clearFilters() {
        channelFilterField.setText("");
        videoFilterField.setText("");
        commentFilterField.setText("");
        dateFromCheck.setSelected(false);
        dateFromSpinner.setEnabled(false);
        dateToCheck.setSelected(false);
        dateToSpinner.setEnabled(false);
        rowSorter.setRowFilter(null);
        statusLabel.setText("Filters cleared");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JSpinner makeDateSpinner() {
        JSpinner spinner = new JSpinner(new SpinnerDateModel());
        spinner.setEditor(new JSpinner.DateEditor(spinner, "yyyy-MM-dd"));
        return spinner;
    }
}