# Android feature checks

Build the Android debug app on Linux, macOS, or WSL, then run the dependency-free queue regression check.
The beta01 FFmpeg module does not support a native Windows build.

```bash
git submodule update --init --recursive --jobs 4
./gradlew :app:assembleDebug
python checks/check_queue_search.py
python .github/scripts/check-zh-coverage.py
```

The queue check compiles the real filtering function with the Kotlin compiler already
cached by the Android build. It checks Chinese text, case-insensitive title/artist/album
matching, empty queries, absent metadata, duplicate occurrences, deletion and reordering.
It also checks sorted and reversed selection IDs, and verifies that random selection
uses the same order across loaded and unloaded pages as the paging source.
It requires Python 3.11+ and the same Java used for Gradle.

Device acceptance (requires a connected Android device and a test music account):

- Select several songs, select/deselect all, cancel, and use Back to exit selection.
  With more than 100 songs, select all and verify that unloaded pages are included;
  queue and playlist additions must follow the visible sorting, including random order.
  Ordinary taps, long-press menus and swipe actions must still work outside selection.
- Add a selection to the queue in displayed order; cancel and confirm duplicate warnings.
- Add a selection to existing playlists and create a new playlist containing that selection.
  Cancel or fail a request and verify the selection remains available.
- Search a queue containing a repeated song; play and remove the second occurrence.
  Check paused playback, shuffle, an empty queue, no matches and clearing the query.
- In queue search, tap the next-play icon: the current song and progress must remain
  unchanged, and a copy of the chosen song must appear immediately after it. The
  original occurrence must remain. Tapping the row must still play immediately.
- Check selection, cancel, select-all and queue/playlist icons, including long-press
  tooltips and screen-reader labels. Select-all must visibly toggle its state.
- Check keyboard, large text and landscape layouts. Reordering is available only after
  clearing the search; active playback and offline markers remain correct.

Use a test account for write operations. These checks do not imply that device or server
integration has been exercised by the command-line regression check.
