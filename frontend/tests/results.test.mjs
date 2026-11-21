import test from "node:test";
import assert from "node:assert/strict";
import {
  formatFileSizeBytes,
  formatFriendlyQuality,
  resolveQualitySizeText,
  resolveTrackDisplaySize,
  selectResults,
} from "../src/results.ts";

const make = (id, codec, lossless, bitrate, size, ready = true) => ({
  id,
  title: id,
  artist: id,
  source: "AudiusMusicClient",
  duration: 100,
  size_bytes: size,
  qualities: codec ? [{ codec, lossless, bitrate }] : [],
  playback: { state: ready ? "ready" : "download_only" },
});
const tracks = [
  make("mp3-128", "mp3", false, 128, 4),
  make("unknown", "", false, null, null),
  make("flac", "flac", true, null, 40, false),
  make("mp3-320", "mp3", false, 320, 10),
];
const ids = (quality, ready, sort) =>
  selectResults(tracks, "all", quality, ready, sort).map((t) => t.id);

test("quality and playable filters combine independently", () => {
  assert.deepEqual(ids("lossless", false, "relevance"), ["flac"]);
  assert.deepEqual(ids("lossless", true, "relevance"), []);
  assert.deepEqual(ids("mp3", true, "relevance"), ["mp3-128", "mp3-320"]);
  assert.deepEqual(ids("unknown", false, "relevance"), ["unknown"]);
  assert.equal(tracks.length, 4);
});

test("size and quality sort in both directions, unknowns last", () => {
  assert.deepEqual(ids("all", false, "size-desc"), [
    "flac",
    "mp3-320",
    "mp3-128",
    "unknown",
  ]);
  assert.deepEqual(ids("all", false, "size-asc"), [
    "mp3-128",
    "mp3-320",
    "flac",
    "unknown",
  ]);
  assert.deepEqual(ids("all", false, "quality-desc"), [
    "flac",
    "mp3-320",
    "mp3-128",
    "unknown",
  ]);
  assert.deepEqual(ids("all", false, "quality-asc"), [
    "mp3-128",
    "mp3-320",
    "flac",
    "unknown",
  ]);
  assert.deepEqual(
    tracks.map((t) => t.id),
    ["mp3-128", "unknown", "flac", "mp3-320"],
  );
});

test("library formats drive quality filters and sorting without download versions", () => {
  const library = (id, codec, bitrate = null, source = "navidrome") => ({
    ...make(id, "", false, null, null),
    source,
    playback: { state: "ready", codec, bitrate },
  });
  const songs = [
    library("mp3", " MP3 ", 320),
    library("unknown", null, 1000),
    library("flac", "FLAC", 800),
    library("aac", "aac", 256),
    library("local-alac", "alac", null, "local"),
    library("missing-format", "  "),
  ];
  const selected = (quality, sort = "relevance") =>
    selectResults(songs, "all", quality, false, sort).map((t) => t.id);
  assert.deepEqual(selected("lossless"), ["flac", "local-alac"]);
  assert.deepEqual(selected("mp3"), ["mp3"]);
  assert.deepEqual(selected("lossy"), ["mp3", "aac"]);
  assert.deepEqual(selected("unknown"), ["unknown", "missing-format"]);
  assert.deepEqual(selected("all", "quality-desc"), [
    "flac",
    "local-alac",
    "mp3",
    "aac",
    "unknown",
    "missing-format",
  ]);
  assert.deepEqual(selected("all", "quality-asc"), [
    "aac",
    "mp3",
    "local-alac",
    "flac",
    "unknown",
    "missing-format",
  ]);
  assert.deepEqual(
    selectResults(songs, "navidrome", "lossless", true, "relevance").map(
      (t) => t.id,
    ),
    ["flac"],
  );
  assert.ok(songs.every((t) => t.qualities.length === 0));
});

test("search preview codecs do not establish download quality", () => {
  const candidate = {
    ...make("candidate", "", false, null, null),
    playback: {
      state: "ready",
      codec: "mp3",
      bitrate: 128,
      preview_only: true,
    },
  };
  assert.deepEqual(
    selectResults([candidate], "all", "mp3", false, "relevance"),
    [],
  );
  assert.deepEqual(
    selectResults([candidate], "all", "unknown", false, "relevance"),
    [candidate],
  );
});

test("formatFileSizeBytes formats B, KB, MB, and GB cleanly", () => {
  assert.equal(formatFileSizeBytes(0), "0 B");
  assert.equal(formatFileSizeBytes(512), "512 B");
  assert.equal(formatFileSizeBytes(512 * 1024), "512 KB");
  assert.equal(formatFileSizeBytes(31_457_280), "30.0 MB");
  assert.equal(formatFileSizeBytes(1_320_702_444), "1.23 GB");
});

test("resolveQualitySizeText and resolveTrackDisplaySize resolve exact, primary, and estimated sizes", () => {
  const flacQ = {
    id: "flac",
    label: "FLAC 无损",
    codec: "flac",
    bitrate: 980,
    lossless: true,
    availability: "verified",
    evidence: "probed_audio",
    size_bytes: 33_973_862,
  };
  const mp3Q = {
    id: "mp3-320",
    label: "MP3 320",
    codec: "mp3",
    bitrate: 320,
    lossless: false,
    availability: "candidate",
    evidence: "declared",
  };
  const losslessEstQ = {
    id: "flac-est",
    label: "FLAC",
    codec: "flac",
    bitrate: null,
    lossless: true,
    availability: "candidate",
    evidence: "declared",
  };

  const multiTrack = {
    id: "t1",
    source: "TestSource",
    source_id: "1",
    title: "Song",
    artist: "Artist",
    album: "Album",
    duration: 240,
    cover: null,
    size_bytes: 33_973_862,
    qualities: [flacQ, mp3Q, losslessEstQ],
    playback: {
      state: "ready",
      url: null,
      codec: null,
      bitrate: null,
      detail: null,
      preview_only: false,
    },
  };

  assert.equal(resolveQualitySizeText(flacQ, multiTrack), "32.4 MB");
  assert.equal(resolveQualitySizeText(mp3Q, multiTrack), "约 9.2 MB");
  assert.equal(resolveQualitySizeText(losslessEstQ, multiTrack), "约 27.2 MB");
  assert.equal(resolveTrackDisplaySize(multiTrack), "32.4 MB");

  const singleQualityTrack = {
    ...multiTrack,
    size_bytes: null,
    size_label: "28.6 MB",
    qualities: [
      {
        ...mp3Q,
        size_bytes: null,
        size_label: null,
      },
    ],
  };
  assert.equal(
    resolveQualitySizeText(singleQualityTrack.qualities[0], singleQualityTrack),
    "28.6 MB",
  );

  const primarySizedTrack = {
    ...multiTrack,
    size_bytes: null,
    size_label: null,
    qualities: [{ ...flacQ, size_bytes: null, size_label: "31.8 MB" }, mp3Q],
  };
  assert.equal(resolveTrackDisplaySize(primarySizedTrack), "31.8 MB");

  const estimatedTrack = {
    ...multiTrack,
    size_bytes: null,
    size_label: null,
    qualities: [mp3Q],
  };
  assert.equal(resolveTrackDisplaySize(estimatedTrack), "约 9.2 MB");

  const unknownTrack = {
    ...multiTrack,
    duration: 0,
    size_bytes: null,
    size_label: null,
    qualities: [],
  };
  assert.equal(resolveTrackDisplaySize(unknownTrack), "—");
});

test("formatFriendlyQuality outputs friendly Chinese tiers and hides internal English tokens", () => {
  const track = {
    id: "t2",
    source: "TestSource",
    source_id: "2",
    title: "Song 2",
    artist: "Artist",
    album: "Album",
    duration: 240,
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
  };

  const cases = [
    {
      q: {
        id: "master",
        label: "Spatial Master verified",
        codec: "flac",
        bitrate: 2400,
        lossless: true,
        availability: "verified",
        evidence: "probed_audio",
        size_bytes: 52_428_800,
      },
      title: "超清母带 · FLAC",
      bitrateText: "2400 kbps",
      sizeText: "50.0 MB",
      badges: [],
    },
    {
      q: {
        id: "hires",
        label: "Hi-Res 24bit",
        codec: "flac",
        bitrate: 1600,
        lossless: true,
        availability: "full",
        evidence: "engine",
      },
      title: "高解析无损 · FLAC",
      bitrateText: "1600 kbps",
      sizeText: "约 45.8 MB",
      badges: [],
    },
    {
      q: {
        id: "sq",
        label: "probed_audio candidate",
        codec: "flac",
        bitrate: 980,
        lossless: true,
        availability: "verified",
        evidence: "probed_audio",
        size_bytes: 33_973_862,
      },
      title: "无损音质 · FLAC",
      bitrateText: "980 kbps",
      sizeText: "32.4 MB",
      badges: [],
    },
    {
      q: {
        id: "hq",
        label: "declared engine",
        codec: "mp3",
        bitrate: 320,
        lossless: false,
        availability: "candidate",
        evidence: "declared",
      },
      title: "极高音质 · MP3",
      bitrateText: "320 kbps",
      sizeText: "约 9.2 MB",
      badges: [],
    },
    {
      q: {
        id: "high",
        label: "catalog_claim",
        codec: "aac",
        bitrate: 192,
        lossless: false,
        availability: "login_required",
        evidence: "catalog_claim",
      },
      title: "较高音质 · AAC",
      bitrateText: "192 kbps",
      sizeText: "约 5.5 MB",
      badges: ["需要账号权限"],
    },
    {
      q: {
        id: "std",
        label: "resolved full probe",
        codec: "mp3",
        bitrate: 128,
        lossless: false,
        availability: "preview_only",
        evidence: "probe",
      },
      title: "标准音质 · MP3",
      bitrateText: "128 kbps",
      sizeText: "约 3.7 MB",
      badges: ["仅试听片段"],
    },
  ];

  const internalWords =
    /\b(verified|candidate|resolved|full|probed_audio|declared|engine|probe|catalog_claim)\b/i;

  for (const item of cases) {
    const out = formatFriendlyQuality(item.q, track);
    assert.equal(out.title, item.title);
    assert.equal(out.bitrateText, item.bitrateText);
    assert.equal(out.sizeText, item.sizeText);
    assert.deepEqual(out.badges, item.badges);
    assert.doesNotMatch(out.title, internalWords);
    assert.doesNotMatch(out.subtitle, internalWords);
  }
});
