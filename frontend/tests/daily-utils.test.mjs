import test from "node:test";
import assert from "node:assert/strict";
import {
  buildDailySeedsFromFavorites,
  isDailyItemEnqueueable,
  mergeDailyItem,
  parseDailyCalendar,
  shouldReportWebListeningHistory,
} from "../src/daily-utils.ts";

function makeTrack(overrides = {}) {
  return {
    id: "mk:test:1",
    source: "TestSource",
    source_id: "1",
    title: "Song Title",
    artist: "Artist A",
    album: "Album A",
    duration: 210,
    cover: null,
    qualities: [],
    playback: {
      state: "ready",
      url: null,
      codec: null,
      bitrate: null,
      detail: null,
      preview_only: false,
    },
    ...overrides,
  };
}

test("parseDailyCalendar parses YYYY-MM-DD in local time without UTC drift", () => {
  const info = parseDailyCalendar("2026-11-26", new Date(2026, 0, 1));
  assert.deepEqual(info, {
    day: "26",
    monthLabel: "11月",
    weekdayLabel: "周四",
    fullDate: "2026-11-26",
  });

  const singleDigit = parseDailyCalendar("2026-03-05", new Date(2026, 0, 1));
  assert.deepEqual(singleDigit, {
    day: "05",
    monthLabel: "3月",
    weekdayLabel: "周四",
    fullDate: "2026-03-05",
  });

  const fallback = new Date(2026, 4, 3); // 2026-05-03 周日
  assert.deepEqual(parseDailyCalendar("invalid-date", fallback), {
    day: "03",
    monthLabel: "5月",
    weekdayLabel: "周日",
    fullDate: "2026-05-03",
  });
});

test("isDailyItemEnqueueable excludes in-library, local/navidrome/device, blank id, and paywalled items", () => {
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack(),
      reason: "适合深夜",
      category: "discover",
      in_library: false,
    }),
    true,
  );
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack(),
      reason: "已在曲库",
      category: "familiar",
      in_library: true,
    }),
    false,
  );
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack({ source: "local" }),
      reason: "",
      category: "familiar",
      in_library: false,
    }),
    false,
  );
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack({ source: "Navidrome" }),
      reason: "",
      category: "familiar",
      in_library: false,
    }),
    false,
  );
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack({ source: "device" }),
      reason: "",
      category: "familiar",
      in_library: false,
    }),
    false,
  );
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack({ pay_required: true }),
      reason: "",
      category: "discover",
      in_library: false,
    }),
    false,
  );
  assert.equal(
    isDailyItemEnqueueable({
      track: makeTrack({ id: "   " }),
      reason: "",
      category: "discover",
      in_library: false,
    }),
    false,
  );
});

test("mergeDailyItem deduplicates by track.id and updates existing entries in place", () => {
  const first = {
    track: makeTrack({ id: "t1", title: "Old" }),
    reason: "r1",
    category: "discover",
    in_library: false,
  };
  const second = {
    track: makeTrack({ id: "t2", title: "Two" }),
    reason: "r2",
    category: "familiar",
    in_library: true,
  };
  const updatedFirst = {
    track: makeTrack({ id: "t1", title: "Updated" }),
    reason: "r1-new",
    category: "discover",
    in_library: false,
  };

  let list = mergeDailyItem([], first);
  list = mergeDailyItem(list, second);
  list = mergeDailyItem(list, updatedFirst);

  assert.equal(list.length, 2);
  assert.equal(list[0].track.title, "Updated");
  assert.equal(list[0].reason, "r1-new");
  assert.equal(list[1].track.id, "t2");
});

test("buildDailySeedsFromFavorites deduplicates case-insensitively and caps maxCount", () => {
  const seeds = buildDailySeedsFromFavorites(
    [
      makeTrack({ id: "1", title: "晴天", artist: "周杰伦", album: "叶惠美" }),
      makeTrack({ id: "2", title: "晴天", artist: "周杰伦" }),
      makeTrack({ id: "3", title: "Hello", artist: "Adele", album: "" }),
      makeTrack({ id: "4", title: "   ", artist: "Nobody" }),
    ],
    2,
  );
  assert.deepEqual(seeds, [
    { title: "晴天", artist: "周杰伦", album: "叶惠美", weight: 1.8 },
    { title: "Hello", artist: "Adele", album: undefined, weight: 1.8 },
  ]);
});

test("shouldReportWebListeningHistory checks 30s or 50% threshold safely", () => {
  assert.equal(shouldReportWebListeningHistory(0, 200), false);
  assert.equal(shouldReportWebListeningHistory(Number.NaN, 200), false);
  assert.equal(shouldReportWebListeningHistory(29.9, 200), false);
  assert.equal(shouldReportWebListeningHistory(30, 200), true);
  assert.equal(shouldReportWebListeningHistory(8, 10), false);
  assert.equal(shouldReportWebListeningHistory(6, 12), true);
  assert.equal(shouldReportWebListeningHistory(10, 22), false);
});
