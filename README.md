# YouTube Comment Manager

A desktop application for managing and deleting YouTube comments using your Google Takeout export. Built with Java Swing and the YouTube Data API v3.

---

## Features

### Comment Import
- Import comments from a **Google Takeout CSV export** (`takeout.google.com`)
- Supports both standard (video comments) and extended (video + community post comments) export formats
- RFC 4180-compliant CSV parsing handles quoted fields, embedded newlines, and escaped quotes
- Automatically detects the format and generates direct links to each video or post

### Filtering & Search
- **Channel filter** — search by channel name or ID
- **Video / Post filter** — search by video or post title or ID
- **Comment text filter** — full-text search with regex support
- **Date range filter** — narrow by a from/to date window
- All filters combine with AND logic; the status bar shows "Showing X of Y comments"

### Comment Deletion
- **Without login** — remove comments from the local view only (no API calls)
- **With login** — permanently delete comments via the YouTube API
  - Delete selected comments individually (checkbox selection)
  - Delete all comments currently visible after filtering
  - Quota check before every deletion operation
  - Confirmation dialog shows the exact count before proceeding

### Video & Channel Title Resolution
After import, the app automatically fetches video titles and channel names from YouTube and replaces raw IDs in the table. API requests are batched (50 per call) to minimise quota usage.

### Quota Tracking
- Daily quota limit: **10,000 units**
- Quota costs: video/channel lookup = 1 unit per 50 items; delete = 50 units per comment
- Remaining quota is shown in the status bar with colour coding:
  - Green — more than 1,000 units remaining
  - Orange — 200–1,000 units remaining
  - Red — fewer than 200 units remaining

### Secure Authentication
- Google OAuth 2.0 with PKCE
- Browser-based consent flow; callback is handled on `localhost:8888`
- Refresh token is stored in the **OS credential store** (Windows Credential Manager, macOS Keychain, Linux Secret Service)
- Silent re-login on subsequent starts if a valid token exists

---

## Requirements

- **Java 11** or higher
- **Gradle** (wrapper included — no separate install required)
- A **Google Cloud project** with the YouTube Data API v3 enabled
- A Google Takeout export of your YouTube comment history

---

## Setup

### 1. Obtain OAuth credentials

1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (or select an existing one).
3. Navigate to **APIs & Services → Library** and enable **YouTube Data API v3**.
4. Navigate to **APIs & Services → Credentials** and create an **OAuth 2.0 Client ID**.
   - Application type: **Desktop app**
5. Download the generated JSON file and rename/save it to:
   ```
   src/main/resources/client_secret.json
   ```

### 2. Build the application

```bash
./gradlew build
```

### 3. Run the application

```bash
./gradlew run
```

On first launch, click **Login with Google**. Your browser will open for the Google consent screen. After authorising, the refresh token is saved automatically and future launches will log in silently.

---

## Getting Your Google Takeout Export

1. Go to [takeout.google.com](https://takeout.google.com).
2. Deselect all products, then select only **YouTube and YouTube Music**.
3. Under YouTube, choose to include only **Comments**.
4. Request the export and download the archive when it is ready.
5. Locate the CSV file inside the archive (typically at `Takeout/YouTube and YouTube Music/comments/`).

---

## Usage

1. Click **Import CSV** and select your Takeout comments file.
2. The table populates with all your comments. Video and channel titles are resolved in the background.
3. Use the filter fields at the top to narrow down the list.
4. Select comments using the checkboxes, or use the filters to target a specific set.
5. Click **Delete Selected** or **Delete All Filtered** to remove comments.
   - If you are logged in, comments are deleted from YouTube.
   - If you are not logged in, they are removed from the local view only.

---

## Project Structure

```
src/main/java/edu/random/bypass/
├── YouTubeCommentSearchApp.java   Main entry point
├── controller/
│   └── AppController.java         Business logic; wires view events to model
├── model/
│   └── CommentsModel.java         Application state (comment list, auth state)
├── view/
│   └── MainView.java              Swing UI components and dialogs
├── integration/
│   └── YouTubeClient.java         YouTube Data API v3 and OAuth 2.0 client
├── service/
│   └── TakeoutImporter.java       Google Takeout CSV parser
├── dto/
│   ├── Comment.java               Comment data object
│   └── VideoInfo.java             Video metadata object
└── config/
    └── KeyringStore.java          OS keyring integration
```

---

## Running Tests

```bash
./gradlew test
```

The test suite uses JUnit 5 and Mockito and covers the model, controller, and CSV import service.

---

## Dependencies

| Dependency | Purpose |
|---|---|
| Google YouTube Data API v3 | YouTube comment and video operations |
| Google OAuth Client (PKCE) | OAuth 2.0 authentication |
| java-keyring | OS credential store integration |
| Project Lombok | Boilerplate reduction (getters, builders) |
| JUnit 5 + Mockito | Unit testing |

---

## Limitations

- The YouTube Data API has a **daily quota of 10,000 units**. Deleting comments costs 50 units each, so a single session can delete at most ~200 comments before the quota is exhausted.
- The quota resets at midnight Pacific Time.
- The API only allows deletion of comments made by the authenticated account.
