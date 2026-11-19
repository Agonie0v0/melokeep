/**
 * MeloKeep Provider Plugin API v1 — 本地离线演示插件
 *
 * 特点：
 * - 无需外网连接即可运行，用于验证 Goja 沙箱执行、搜索推送、音质解析、歌词读取与刮削元数据能力
 * - 仅使用合成演示数据与开放许可（CC-BY）示例条目
 */

const manifest = {
  apiVersion: 1,
  id: "mock-local-demo",
  name: "本地演示插件 (Mock)",
  version: "1.0.0",
  description: "无需外网、开箱即跑的 MeloKeep Provider Plugin API v1 合规演示插件",
  category: "演示与调试",
  capabilities: {
    search: true,
    playlist: true,
    episodes: false,
    lyrics: true,
    metadata: true,
  },
  settings: [
    {
      key: "artist_prefix",
      label: "演示歌手前缀",
      type: "text",
      placeholder: "例如 Open Collective",
      required: false,
      defaultValue: "Open Horizon",
    },
    {
      key: "demo_audio_base",
      label: "示例媒体基址（可选）",
      type: "url",
      placeholder: "https://media.example.org/cc-demos",
      required: false,
      defaultValue: "https://media.example.org/cc-demos",
    },
  ],
};

const DEMO_CATALOG = [
  {
    sourceId: "demo-cc-001",
    title: "Solar Wind (CC-BY Demo)",
    artist: "Open Horizon Collective",
    album: "Creative Commons Sessions Vol. 1",
    albumArtist: "Open Horizon Collective",
    date: "2026-01-15",
    trackNumber: "1",
    discNumber: "1",
    duration: 184,
    cover: "https://media.example.org/cc-demos/covers/solar-wind.jpg",
    lyrics: [
      "[00:00.00]Solar Wind - Open Horizon Collective",
      "[00:05.00]Released under Creative Commons CC-BY 4.0",
      "[00:12.00]Instrumental synthwave demo track",
    ].join("\n"),
    variants: [
      {
        variantId: "flac:999",
        label: "FLAC 无损 (Demo)",
        codec: "flac",
        bitrate: 999,
        lossless: true,
        availability: "available",
        evidence: "manifest",
        previewOnly: false,
        sizeBytes: 18432000,
      },
      {
        variantId: "mp3:320",
        label: "MP3 320kbps (Demo)",
        codec: "mp3",
        bitrate: 320,
        lossless: false,
        availability: "available",
        evidence: "manifest",
        previewOnly: false,
        sizeBytes: 7372800,
      },
    ],
  },
  {
    sourceId: "demo-cc-002",
    title: "Acoustic Morning (CC0 Public Domain)",
    artist: "Field Recording Archive",
    album: "Open Air Sketches",
    albumArtist: "Field Recording Archive",
    date: "2026-02-20",
    trackNumber: "2",
    discNumber: "1",
    duration: 142,
    cover: "https://media.example.org/cc-demos/covers/acoustic-morning.jpg",
    lyrics: "[00:00.00]Acoustic Morning (Pure Instrumental)",
    variants: [
      {
        variantId: "mp3:320",
        label: "MP3 320kbps (Demo)",
        codec: "mp3",
        bitrate: 320,
        lossless: false,
        availability: "available",
        evidence: "manifest",
        previewOnly: false,
        sizeBytes: 5680000,
      },
    ],
  },
];

function getSetting(ctx, key, fallback) {
  if (ctx && ctx.settings && typeof ctx.settings[key] === "string" && ctx.settings[key].trim() !== "") {
    return ctx.settings[key].trim();
  }
  return fallback;
}

function search(ctx, opts) {
  const keyword = ((opts && opts.keyword) || "").trim().toLowerCase();
  const limit = opts && opts.limit > 0 ? opts.limit : 10;
  const artistPrefix = getSetting(ctx, "artist_prefix", "");

  if (ctx && typeof ctx.log === "function") {
    ctx.log("info", "mock-local-demo searching keyword=" + keyword);
  }

  let count = 0;
  for (let i = 0; i < DEMO_CATALOG.length && count < limit; i++) {
    const item = DEMO_CATALOG[i];
    const haystack = (item.title + " " + item.artist + " " + item.album).toLowerCase();
    if (keyword === "" || keyword === "demo" || haystack.indexOf(keyword) !== -1) {
      ctx.emit({
        sourceId: item.sourceId,
        title: item.title,
        artist: artistPrefix && i === 0 ? artistPrefix : item.artist,
        album: item.album,
        duration: item.duration,
        cover: item.cover,
        variants: item.variants,
      });
      count++;
    }
  }

  return {
    count: count,
    detail: "mock-local-demo returned " + count + " offline demo tracks",
  };
}

function resolve(ctx, track, policy) {
  const sid = track && track.sourceId ? String(track.sourceId) : "";
  const item = DEMO_CATALOG.find(function (entry) {
    return entry.sourceId === sid;
  }) || DEMO_CATALOG[0];

  const base = getSetting(ctx, "demo_audio_base", "https://media.example.org/cc-demos").replace(/\/+$/, "");
  const preferredId = policy && policy.preferredVariantId ? String(policy.preferredVariantId) : "";

  const variants = item.variants.slice();
  if (preferredId) {
    variants.sort(function (a, b) {
      return (a.variantId === preferredId ? -1 : 0) - (b.variantId === preferredId ? -1 : 0);
    });
  }

  return variants.map(function (v) {
    const ext = v.codec === "flac" ? "flac" : "mp3";
    return {
      variantId: v.variantId,
      label: v.label,
      codec: v.codec,
      bitrate: v.bitrate,
      lossless: v.lossless,
      previewOnly: false,
      protocol: "http",
      url: base + "/" + item.sourceId + "." + ext,
      headers: {},
      sizeBytes: v.sizeBytes,
      duration: item.duration,
      lyrics: item.lyrics,
    };
  });
}

function playlist(ctx, locator) {
  if (ctx && typeof ctx.log === "function") {
    ctx.log("info", "mock-local-demo loading playlist locator=" + String(locator || "default"));
  }
  for (let i = 0; i < DEMO_CATALOG.length; i++) {
    const item = DEMO_CATALOG[i];
    ctx.emit({
      sourceId: item.sourceId,
      title: item.title,
      artist: item.artist,
      album: item.album,
      duration: item.duration,
      cover: item.cover,
      playlistIndex: i + 1,
      variants: item.variants,
    });
  }
  return {
    id: String(locator || "demo-playlist"),
    name: "MeloKeep CC-BY Demo Playlist",
    count: DEMO_CATALOG.length,
    detail: "离线演示歌单",
    issues: [],
  };
}

function lyrics(ctx, track) {
  const sid = track && track.sourceId ? String(track.sourceId) : "";
  const item = DEMO_CATALOG.find(function (entry) {
    return entry.sourceId === sid;
  });
  return item ? item.lyrics : DEMO_CATALOG[0].lyrics;
}

function metadata(ctx, opts) {
  const query = ((opts && (opts.query || opts.title)) || "").trim().toLowerCase();
  const limit = opts && opts.limit > 0 ? opts.limit : 5;
  const results = [];

  for (let i = 0; i < DEMO_CATALOG.length && results.length < limit; i++) {
    const item = DEMO_CATALOG[i];
    const haystack = (item.title + " " + item.artist + " " + item.album).toLowerCase();
    if (query === "" || haystack.indexOf(query) !== -1) {
      results.push({
        sourceId: item.sourceId,
        title: item.title,
        artist: item.artist,
        album: item.album,
        albumArtist: item.albumArtist,
        date: item.date,
        trackNumber: item.trackNumber,
        discNumber: item.discNumber,
        duration: item.duration,
        coverUrl: item.cover,
        lyrics: item.lyrics,
      });
    }
  }
  return results;
}

module.exports = {
  manifest: manifest,
  search: search,
  resolve: resolve,
  playlist: playlist,
  lyrics: lyrics,
  metadata: metadata,
};
