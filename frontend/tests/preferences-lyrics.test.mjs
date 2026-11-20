import test from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_PLAYER_PREFS,
  LARGE_LYRICS_FONT_SIZE,
  LYRICS_ACTIVE_COLOR_PRESETS,
  loadInitialPlayerPrefs,
  normalizePlayerPrefs,
  parsePlayerPreferences,
  sanitizeLyricsActiveColor,
  sanitizeLyricsAlign,
  sanitizeLyricsFontSize,
  sanitizeLyricsInactiveOpacity,
  saveLocalPlayerPrefs,
  toPlayerPreferences,
} from "../src/preferences.ts";

test("player preferences defaults and lyric color presets match specification", () => {
  assert.equal(DEFAULT_PLAYER_PREFS.lyricsFontSize, 24);
  assert.equal(DEFAULT_PLAYER_PREFS.lyricsActiveColor, "");
  assert.equal(DEFAULT_PLAYER_PREFS.lyricsInactiveOpacity, 0.45);
  assert.equal(DEFAULT_PLAYER_PREFS.lyricsAlign, "center");
  assert.deepEqual(
    LYRICS_ACTIVE_COLOR_PRESETS.map((p) => p.color),
    ["", "#FFFFFF", "#F59E0B", "#38BDF8", "#34D399", "#FB7185"],
  );
});

test("lyric style sanitizers clamp, trim, and reject invalid values", () => {
  assert.equal(sanitizeLyricsFontSize(10), 14);
  assert.equal(sanitizeLyricsFontSize(40), 32);
  assert.equal(sanitizeLyricsFontSize(22.6), 23);
  assert.equal(sanitizeLyricsFontSize(""), 24);
  assert.equal(sanitizeLyricsFontSize(NaN, 20), 20);
  assert.equal(sanitizeLyricsFontSize(true), 24);

  assert.equal(sanitizeLyricsActiveColor(""), "");
  assert.equal(sanitizeLyricsActiveColor("   "), "");
  assert.equal(sanitizeLyricsActiveColor("  #38bdf8  "), "#38bdf8");
  assert.equal(sanitizeLyricsActiveColor("red", "#FFFFFF"), "#FFFFFF");
  assert.equal(sanitizeLyricsActiveColor("#123", ""), "");
  assert.equal(sanitizeLyricsActiveColor("javascript:1", ""), "");

  assert.equal(sanitizeLyricsInactiveOpacity(0.05), 0.2);
  assert.equal(sanitizeLyricsInactiveOpacity(1.2), 0.9);
  assert.equal(sanitizeLyricsInactiveOpacity(0.300000004), 0.3);
  assert.equal(sanitizeLyricsInactiveOpacity("", 0.5), 0.5);
  assert.equal(sanitizeLyricsInactiveOpacity(null, 0.45), 0.45);

  assert.equal(sanitizeLyricsAlign("left"), "left");
  assert.equal(sanitizeLyricsAlign("center"), "center");
  assert.equal(sanitizeLyricsAlign("right"), "center");
  assert.equal(sanitizeLyricsAlign(undefined, "left"), "left");
});

test("parsePlayerPreferences and toPlayerPreferences handle DTO round-trip and legacy large_lyrics", () => {
  const legacyOnly = parsePlayerPreferences({
    volume: 0.8,
    large_lyrics: true,
  });
  assert.equal(legacyOnly.volume, 0.8);
  assert.equal(legacyOnly.largeLyrics, true);
  assert.equal(legacyOnly.lyricsFontSize, LARGE_LYRICS_FONT_SIZE);

  const customized = parsePlayerPreferences({
    volume: 0.5,
    shuffle: true,
    repeat: false,
    follow_lyrics: false,
    lyrics_font_size: 30,
    lyrics_active_color: " #F59E0B ",
    lyrics_inactive_opacity: 0.72,
    lyrics_align: "left",
  });
  assert.deepEqual(customized, {
    volume: 0.5,
    shuffle: true,
    repeat: false,
    follow: false,
    largeLyrics: true,
    lyricsFontSize: 30,
    lyricsActiveColor: "#F59E0B",
    lyricsInactiveOpacity: 0.72,
    lyricsAlign: "left",
  });

  assert.deepEqual(toPlayerPreferences(customized), {
    volume: 0.5,
    shuffle: true,
    repeat: false,
    follow_lyrics: false,
    large_lyrics: true,
    lyrics_font_size: 30,
    lyrics_active_color: "#F59E0B",
    lyrics_inactive_opacity: 0.72,
    lyrics_align: "left",
  });

  // Explicit empty color from server clears previously selected custom color.
  const resetFromServer = parsePlayerPreferences(
    {
      lyrics_font_size: 24,
      lyrics_active_color: "",
      lyrics_inactive_opacity: 0.45,
      lyrics_align: "center",
    },
    customized,
  );
  assert.equal(resetFromServer.lyricsActiveColor, "");
  assert.equal(resetFromServer.lyricsFontSize, 24);
  assert.equal(resetFromServer.largeLyrics, false);
  assert.equal(resetFromServer.lyricsAlign, "center");

  // Invalid server fields preserve fallback instead of throwing.
  const invalidFallback = parsePlayerPreferences(
    {
      lyrics_font_size: "bad",
      lyrics_active_color: "not-hex",
      lyrics_inactive_opacity: -Infinity,
      lyrics_align: "justify",
    },
    customized,
  );
  assert.equal(invalidFallback.lyricsFontSize, 30);
  assert.equal(invalidFallback.lyricsActiveColor, "#F59E0B");
  assert.equal(invalidFallback.lyricsInactiveOpacity, 0.72);
  assert.equal(invalidFallback.lyricsAlign, "left");
});

test("localStorage player preferences sanitize legacy and corrupted payloads", () => {
  const prevStorage = globalThis.localStorage;
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  try {
    store.set(
      "melokeep-player",
      JSON.stringify({ volume: 0.9, largeLyrics: true }),
    );
    const loadedLegacy = loadInitialPlayerPrefs();
    assert.equal(loadedLegacy.largeLyrics, true);
    assert.equal(loadedLegacy.lyricsFontSize, 28);
    assert.equal(loadedLegacy.lyricsAlign, "center");

    saveLocalPlayerPrefs(
      normalizePlayerPrefs({
        lyricsFontSize: 99,
        lyricsActiveColor: "#34D399",
        lyricsInactiveOpacity: 0.1,
        lyricsAlign: "left",
      }),
    );
    const reloaded = loadInitialPlayerPrefs();
    assert.equal(reloaded.lyricsFontSize, 32);
    assert.equal(reloaded.largeLyrics, true);
    assert.equal(reloaded.lyricsActiveColor, "#34D399");
    assert.equal(reloaded.lyricsInactiveOpacity, 0.2);
    assert.equal(reloaded.lyricsAlign, "left");
  } finally {
    globalThis.localStorage = prevStorage;
  }
});
