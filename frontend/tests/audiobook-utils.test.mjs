import test from "node:test";
import assert from "node:assert/strict";
import {
  buildBatchDownloadPayloads,
  chunkArray,
  formatEpisodeBadge,
  getEpisodeNumber,
  getFreeEpisodeBounds,
  isCollectionTrack,
  isPayRequiredTrack,
  selectEpisodeRange,
  shouldUseBatchDownloadModal,
  splitDownloadableTracks,
} from "../src/audiobook-utils.ts";

const makeTrack = (id, overrides = {}) => ({
  id,
  source: "open-podcast",
  source_id: `sid-${id}`,
  title: `第${id}集`,
  artist: "主播",
  album: "测试有声书",
  duration: 300,
  cover: null,
  qualities: [],
  playback: {
    state: "download_only",
    url: null,
    codec: null,
    bitrate: null,
    detail: null,
    preview_only: false,
  },
  ...overrides,
});

test("isCollectionTrack and formatEpisodeBadge handle positive, -1, zero, and undefined episodes", () => {
  assert.equal(isCollectionTrack(makeTrack("a", { episodes: 313 })), true);
  assert.equal(isCollectionTrack(makeTrack("b", { episodes: -1 })), true);
  assert.equal(isCollectionTrack(makeTrack("c", { episodes: 0 })), false);
  assert.equal(isCollectionTrack(makeTrack("d", { episodes: -2 })), false);
  assert.equal(isCollectionTrack(makeTrack("e")), false);
  assert.equal(
    isCollectionTrack(makeTrack("f", { episodes: Number.NaN })),
    false,
  );

  assert.equal(formatEpisodeBadge(313), "整部 · 313 集");
  assert.equal(formatEpisodeBadge(-1), "整部有声书");
  assert.equal(formatEpisodeBadge(0), "整部有声书");
  assert.equal(formatEpisodeBadge(undefined), "整部有声书");
});

test("getEpisodeNumber and selectEpisodeRange handle playlist_index, fallback index, reversed range, and boundaries", () => {
  const episodes = [
    makeTrack("ep-1", { playlist_index: 1 }),
    makeTrack("ep-2", { playlist_index: 2 }),
    makeTrack("ep-3"),
    makeTrack("ep-4", { playlist_index: 0 }),
    makeTrack("ep-10", { playlist_index: 10 }),
  ];

  assert.equal(getEpisodeNumber(episodes[0], 0), 1);
  assert.equal(getEpisodeNumber(episodes[2], 2), 3);
  assert.equal(getEpisodeNumber(episodes[3], 3), 4);
  assert.equal(getEpisodeNumber(episodes[4], 4), 10);

  // Normal inclusive range
  assert.deepEqual(
    selectEpisodeRange(episodes, 2, 4).map((t) => t.id),
    ["ep-2", "ep-3", "ep-4"],
  );
  // Reversed range [4, 2]
  assert.deepEqual(
    selectEpisodeRange(episodes, 4, 2).map((t) => t.id),
    ["ep-2", "ep-3", "ep-4"],
  );
  // Boundary single episode & out-of-bounds end
  assert.deepEqual(
    selectEpisodeRange(episodes, 10, 10).map((t) => t.id),
    ["ep-10"],
  );
  assert.deepEqual(
    selectEpisodeRange(episodes, 4, 99).map((t) => t.id),
    ["ep-4", "ep-10"],
  );
  assert.deepEqual(selectEpisodeRange([], 1, 10), []);
});

test("shouldUseBatchDownloadModal decides based on audiobook context or > 10 items", () => {
  const ten = Array.from({ length: 10 }, (_, i) => makeTrack(String(i + 1)));
  const eleven = [...ten, makeTrack("11")];

  assert.equal(shouldUseBatchDownloadModal([], false), false);
  assert.equal(shouldUseBatchDownloadModal(ten, false), false);
  assert.equal(shouldUseBatchDownloadModal(ten), false);
  assert.equal(shouldUseBatchDownloadModal(eleven, false), true);
  assert.equal(shouldUseBatchDownloadModal([ten[0]], true), true);
  assert.equal(shouldUseBatchDownloadModal([], true), true);
});

test("buildBatchDownloadPayloads generates audiobook and plain music payloads and chunkArray splits by 200", () => {
  const episodes = [
    makeTrack("t1", { playlist_index: 101, album: "专辑A" }),
    makeTrack("t2", { album: "专辑A" }),
  ];

  assert.deepEqual(
    buildBatchDownloadPayloads(episodes, {
      preferredQuality: "standard",
      audiobookLayout: true,
      skipAutoOrganize: true,
      bookTitle: "示例有声书",
      totalEpisodes: 313,
    }),
    [
      {
        track_id: "t1",
        quality_id: "",
        preferred_quality: "standard",
        skip_auto_organize: true,
        layout: "audiobook",
        book_title: "示例有声书",
        chapter_index: 101,
        chapter_total: 313,
      },
      {
        track_id: "t2",
        quality_id: "",
        preferred_quality: "standard",
        skip_auto_organize: true,
        layout: "audiobook",
        book_title: "示例有声书",
        chapter_index: 2,
        chapter_total: 313,
      },
    ],
  );

  // Fallback bookTitle and totalEpisodes, plus non-audiobook layout
  assert.deepEqual(
    buildBatchDownloadPayloads([episodes[0]], {
      preferredQuality: "highest",
      audiobookLayout: true,
      skipAutoOrganize: false,
    }),
    [
      {
        track_id: "t1",
        quality_id: "",
        preferred_quality: "highest",
        skip_auto_organize: false,
        layout: "audiobook",
        book_title: "专辑A",
        chapter_index: 101,
        chapter_total: 1,
      },
    ],
  );

  assert.deepEqual(
    buildBatchDownloadPayloads(episodes, {
      preferredQuality: "highest",
      audiobookLayout: false,
      skipAutoOrganize: false,
    }),
    [
      {
        track_id: "t1",
        quality_id: "",
        preferred_quality: "highest",
        skip_auto_organize: false,
      },
      {
        track_id: "t2",
        quality_id: "",
        preferred_quality: "highest",
        skip_auto_organize: false,
      },
    ],
  );

  // 450 items -> chunks of 200, 200, 50
  const many = Array.from({ length: 450 }, (_, i) => i + 1);
  const chunks = chunkArray(many);
  assert.equal(chunks.length, 3);
  assert.equal(chunks[0].length, 200);
  assert.equal(chunks[1].length, 200);
  assert.equal(chunks[2].length, 50);
  assert.equal(chunks[2][49], 450);
  assert.deepEqual(chunkArray([]), []);
});

test("isPayRequiredTrack, splitDownloadableTracks, and getFreeEpisodeBounds filter paid episodes accurately", () => {
  const free1 = makeTrack("ep-1", {
    playlist_index: 1,
    title: "示例免费章节 1",
  });
  const free2 = makeTrack("ep-2", {
    playlist_index: 2,
    pay_required: false,
    title: "示例免费章节 2",
  });
  const free84 = makeTrack("ep-84", {
    playlist_index: 84,
    pay_required: false,
    title: "示例免费章节 84",
  });
  const paid85 = makeTrack("ep-85", {
    playlist_index: 85,
    pay_required: true,
    title: "示例付费章节 85",
  });
  const paid86 = makeTrack("ep-86", {
    playlist_index: 86,
    pay_required: true,
    title: "示例付费章节 86",
  });

  assert.equal(isPayRequiredTrack(free1), false);
  assert.equal(isPayRequiredTrack(free2), false);
  assert.equal(isPayRequiredTrack(paid85), true);

  const mixed = [free1, paid85, free2, free84, paid86];
  const split = splitDownloadableTracks(mixed);
  assert.deepEqual(
    split.downloadable.map((t) => t.id),
    ["ep-1", "ep-2", "ep-84"],
  );
  assert.deepEqual(
    split.payBlocked.map((t) => t.id),
    ["ep-85", "ep-86"],
  );

  assert.deepEqual(getFreeEpisodeBounds(mixed), {
    start: 1,
    end: 84,
    freeCount: 3,
    paidCount: 2,
  });

  // Fallback index when playlist_index is missing
  const noPlaylistIdx = [
    makeTrack("a", { pay_required: true, title: "示例付费章节 A" }),
    makeTrack("b", { pay_required: false, title: "示例免费章节 B" }),
    makeTrack("c", { title: "示例免费章节 C" }),
  ];
  assert.deepEqual(getFreeEpisodeBounds(noPlaylistIdx), {
    start: 2,
    end: 3,
    freeCount: 2,
    paidCount: 1,
  });

  // All paid episodes
  assert.deepEqual(getFreeEpisodeBounds([paid85, paid86]), {
    start: 1,
    end: 1,
    freeCount: 0,
    paidCount: 2,
  });

  // Empty array
  assert.deepEqual(getFreeEpisodeBounds([]), {
    start: 1,
    end: 1,
    freeCount: 0,
    paidCount: 0,
  });
});
