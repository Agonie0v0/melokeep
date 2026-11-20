import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, api } from "../src/api.ts";
import {
  ARCHIVE_FILTER_ALL,
  ARCHIVE_FILTER_INVALID,
  ARCHIVE_UNCATEGORIZED,
  MAX_PLUGIN_ARCHIVE_BYTES,
  MAX_PLUGIN_BYTES,
  PLUGIN_STATUS_ALL,
  PLUGIN_STATUS_DISABLED,
  PLUGIN_STATUS_ENABLED,
  archiveEntryCategory,
  buildPluginSettingsPayload,
  filterArchiveEntries,
  filterInstalledPlugins,
  formatAllowedOrigins,
  formatFileSize,
  formatMetadataSourceLabel,
  getArchiveCategoryBuckets,
  getInstalledPluginCategories,
  getValidArchivePluginIds,
  isMaskedSecretValue,
  isPluginArchiveFile,
  isPluginSource,
  parseAllowedOrigins,
  pickPlaylistImportSource,
  pluginCapabilityBadges,
  sourceEngineKey,
  toggleArchiveCategorySelection,
  toggleArchiveIdSelection,
} from "../src/plugin-utils.ts";

test("parseAllowedOrigins splits lines/commas and deduplicates entries", () => {
  assert.deepEqual(
    parseAllowedOrigins(
      "http://192.168.1.10:8080\nhttps://nas.local:9000, http://192.168.1.10:8080\n  \n",
    ),
    ["http://192.168.1.10:8080", "https://nas.local:9000"],
  );
  assert.equal(
    formatAllowedOrigins(["http://192.168.1.10:8080", "https://nas.local"]),
    "http://192.168.1.10:8080\nhttps://nas.local",
  );
});

test("buildPluginSettingsPayload preserves masked secrets when left blank and clears when requested", () => {
  const fields = [
    { key: "api_key", label: "API Key", type: "secret" },
    { key: "token", label: "Token", type: "password" },
    { key: "endpoint", label: "Endpoint", type: "url" },
    { key: "strict", label: "Strict", type: "boolean", default_value: "false" },
  ];
  const current = {
    api_key: "[已隐藏]",
    token: "__MELOKEEP_HIDDEN__",
    endpoint: "https://api.example.org",
    strict: "true",
  };

  assert.equal(isMaskedSecretValue("[已隐藏]"), true);
  assert.equal(isMaskedSecretValue("__MELOKEEP_HIDDEN__"), true);
  assert.equal(isMaskedSecretValue("plain-key"), false);

  // Untouched secrets keep server placeholder; endpoint updated; strict remains true
  assert.deepEqual(
    buildPluginSettingsPayload(
      fields,
      {
        api_key: "",
        token: "",
        endpoint: "https://cdn.example.org",
      },
      current,
      [],
    ),
    {
      api_key: "[已隐藏]",
      token: "__MELOKEEP_HIDDEN__",
      endpoint: "https://cdn.example.org",
      strict: "true",
    },
  );

  // Overwriting api_key and clearing token
  assert.deepEqual(
    buildPluginSettingsPayload(
      fields,
      {
        api_key: "new-secret-key",
        token: "",
        endpoint: "https://cdn.example.org",
        strict: "false",
      },
      current,
      ["token"],
    ),
    {
      api_key: "new-secret-key",
      token: "",
      endpoint: "https://cdn.example.org",
      strict: "false",
    },
  );
});

test("pluginCapabilityBadges maps all 5 capabilities in stable order", () => {
  assert.deepEqual(
    pluginCapabilityBadges({
      search: true,
      playlist: true,
      episodes: true,
      lyrics: true,
      metadata: true,
    }),
    [
      { key: "search", label: "搜索" },
      { key: "playlist", label: "歌单" },
      { key: "episodes", label: "分集" },
      { key: "lyrics", label: "歌词" },
      { key: "metadata", label: "刮削" },
    ],
  );
  assert.deepEqual(pluginCapabilityBadges(null), []);
});

test("pickPlaylistImportSource chooses first playlist-capable source and preserves valid choice", () => {
  const sources = [
    {
      id: "AudiusMusicClient",
      label: "Audius",
      origin: "builtin",
      capability: { tier: "open", note: "", search: true, playlist: false },
    },
    {
      id: "JamendoMusicClient",
      label: "Jamendo",
      origin: "builtin",
      capability: { tier: "partial", note: "", search: true, playlist: true },
    },
    {
      id: "mock-open-source",
      label: "Mock Open Source",
      origin: "plugin",
      plugin_id: "mock-open-source",
      capability: { tier: "plugin", note: "", search: true, playlist: true },
    },
  ];
  assert.equal(pickPlaylistImportSource(sources, ""), "JamendoMusicClient");
  assert.equal(
    pickPlaylistImportSource(sources, "mock-open-source"),
    "mock-open-source",
  );
  assert.equal(
    pickPlaylistImportSource(sources, "AudiusMusicClient"),
    "JamendoMusicClient",
  );
  assert.equal(pickPlaylistImportSource([sources[0]], ""), "");
  assert.equal(isPluginSource(sources[0]), false);
  assert.equal(isPluginSource(sources[2]), true);
  assert.equal(
    sourceEngineKey({ id: "audius", engine_name: "AudiusMusicClient" }),
    "AudiusMusicClient",
  );
});

test("formatMetadataSourceLabel prefers dynamic metadata_sources label with compliant fallback", () => {
  const dynamic = [
    { id: "musicbrainz", label: "MusicBrainz" },
    { id: "lrclib", label: "LRCLIB 歌词库" },
    { id: "custom-meta", label: "自定义元数据插件" },
  ];
  assert.equal(formatMetadataSourceLabel("lrclib", dynamic), "LRCLIB 歌词库");
  assert.equal(
    formatMetadataSourceLabel("custom-meta", dynamic),
    "自定义元数据插件",
  );
  assert.equal(formatMetadataSourceLabel("itunes", []), "iTunes");
  assert.equal(formatMetadataSourceLabel("acoustid", []), "AcoustID");
  assert.equal(
    formatMetadataSourceLabel("unknown_source", []),
    "unknown_source",
  );
});

test("isPluginArchiveFile and formatFileSize identify zip archives and format sizes", () => {
  assert.equal(MAX_PLUGIN_BYTES, 512 * 1024);
  assert.equal(MAX_PLUGIN_ARCHIVE_BYTES, 16 * 1024 * 1024);

  assert.equal(
    isPluginArchiveFile({ name: "melokeep-plugins.zip", type: "" }),
    true,
  );
  assert.equal(isPluginArchiveFile({ name: "PLUGINS.ZIP", type: "" }), true);
  assert.equal(
    isPluginArchiveFile({ name: "bundle", type: "application/zip" }),
    true,
  );
  assert.equal(
    isPluginArchiveFile({
      name: "bundle",
      type: "application/x-zip-compressed",
    }),
    true,
  );
  assert.equal(
    isPluginArchiveFile({ name: "qq.js", type: "text/javascript" }),
    false,
  );
  assert.equal(isPluginArchiveFile({ name: "qq.js", type: "" }), false);

  assert.equal(formatFileSize(512), "512 B");
  assert.equal(formatFileSize(1024), "1.0 KiB");
  assert.equal(formatFileSize(512 * 1024), "512.0 KiB");
  assert.equal(formatFileSize(16 * 1024 * 1024), "16.00 MiB");
});

test("archive helpers extract valid IDs, group categories, filter and toggle selection", () => {
  const entries = [
    {
      entry_path: "b1/qq.js",
      ok: true,
      manifest: {
        api_version: 1,
        id: "qq",
        name: "QQ 音乐",
        version: "1.0.0",
        category: "华语平台",
        capabilities: { search: true, playlist: true },
      },
    },
    {
      entry_path: "b1/netease.js",
      ok: true,
      manifest: {
        api_version: 1,
        id: "netease",
        name: "网易云音乐",
        version: "1.0.0",
        category: "华语平台",
        capabilities: { search: true, playlist: true },
      },
    },
    {
      entry_path: "b2/custom.js",
      ok: true,
      manifest: {
        api_version: 1,
        id: "custom-no-cat",
        name: "无分类插件",
        version: "1.0.0",
        capabilities: { search: true, playlist: false },
      },
    },
    {
      entry_path: "b4/spotify.js",
      ok: true,
      manifest: {
        api_version: 1,
        id: "spotify",
        name: "Spotify",
        version: "1.0.0",
        category: "国际流媒体",
        capabilities: { search: true, playlist: true },
      },
    },
    {
      entry_path: "broken/bad.js",
      ok: false,
      error: "SyntaxError: unexpected token",
    },
  ];

  assert.equal(archiveEntryCategory(entries[0]), "华语平台");
  assert.equal(archiveEntryCategory(entries[2]), ARCHIVE_UNCATEGORIZED);
  assert.equal(archiveEntryCategory(entries[4]), "");

  assert.deepEqual(getValidArchivePluginIds(entries), [
    "qq",
    "netease",
    "custom-no-cat",
    "spotify",
  ]);

  const buckets = getArchiveCategoryBuckets(entries);
  assert.deepEqual(buckets, [
    { category: "华语平台", count: 2, ids: ["qq", "netease"] },
    { category: "国际流媒体", count: 1, ids: ["spotify"] },
    { category: ARCHIVE_UNCATEGORIZED, count: 1, ids: ["custom-no-cat"] },
  ]);

  assert.equal(filterArchiveEntries(entries, ARCHIVE_FILTER_ALL).length, 5);
  assert.deepEqual(
    filterArchiveEntries(entries, "华语平台").map((e) => e.entry_path),
    ["b1/qq.js", "b1/netease.js"],
  );
  assert.deepEqual(
    filterArchiveEntries(entries, ARCHIVE_UNCATEGORIZED).map(
      (e) => e.entry_path,
    ),
    ["b2/custom.js"],
  );
  assert.deepEqual(
    filterArchiveEntries(entries, ARCHIVE_FILTER_INVALID).map(
      (e) => e.entry_path,
    ),
    ["broken/bad.js"],
  );

  // Toggle individual ID
  let selected = ["qq", "spotify"];
  selected = toggleArchiveIdSelection(selected, "netease");
  assert.deepEqual(selected, ["qq", "spotify", "netease"]);
  selected = toggleArchiveIdSelection(selected, "qq");
  assert.deepEqual(selected, ["spotify", "netease"]);

  // Toggle category: partially selected -> select all in category while keeping others
  selected = toggleArchiveCategorySelection(selected, ["qq", "netease"]);
  assert.deepEqual(selected, ["spotify", "netease", "qq"]);

  // Toggle category: all selected -> remove category while keeping others
  selected = toggleArchiveCategorySelection(selected, ["qq", "netease"]);
  assert.deepEqual(selected, ["spotify"]);
});

test("api sends FormData without manual Content-Type while keeping CSRF header and error handling", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    if (url === "/api/plugins/inspect-archive") {
      return new Response(
        JSON.stringify({
          total: 2,
          valid_count: 2,
          invalid_count: 0,
          entries: [],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }
    return new Response(JSON.stringify({ detail: "压缩包格式无效" }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });
  };

  try {
    const form = new FormData();
    form.append("file", new Blob(["zip-bytes"]), "melokeep-plugins.zip");
    const res = await api("/plugins/inspect-archive", "POST", form);
    assert.equal(res.total, 2);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.credentials, "same-origin");
    assert.equal(calls[0].init.headers["X-MeloKeep-Request"], "1");
    assert.equal("Content-Type" in calls[0].init.headers, false);
    assert.equal(calls[0].init.body, form);

    await assert.rejects(
      () => api("/plugins/install-archive", "POST", { enabled: true }),
      (err) => {
        assert.equal(err instanceof ApiError, true);
        assert.equal(err.status, 400);
        assert.equal(err.message, "压缩包格式无效");
        return true;
      },
    );
    assert.equal(calls[1].init.headers["Content-Type"], "application/json");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("installed plugin helpers extract categories and filter by query, status, and category", () => {
  const plugins = [
    {
      id: "qq",
      enabled: true,
      manifest: {
        api_version: 1,
        id: "qq",
        name: "QQ 音乐",
        version: "1.0.0",
        description: "官方协议及无损母带解析",
        category: "华语平台",
        capabilities: { search: true, playlist: true },
      },
      settings: {},
    },
    {
      id: "netease",
      enabled: false,
      manifest: {
        api_version: 1,
        id: "netease",
        name: "网易云音乐",
        version: "1.0.0",
        description: "CloudMusic EAPI 原生解析",
        category: "华语平台",
        capabilities: { search: true, playlist: true },
      },
      settings: {},
    },
    {
      id: "spotify",
      enabled: true,
      manifest: {
        api_version: 1,
        id: "spotify",
        name: "Spotify",
        version: "1.0.0",
        description: "Official catalog and previews",
        category: "国际流媒体",
        capabilities: { search: true, playlist: true },
      },
      settings: {},
    },
    {
      id: "custom-rss",
      enabled: false,
      manifest: {
        api_version: 1,
        id: "custom-rss",
        name: "自托管播客",
        version: "0.2.0",
        capabilities: { search: true, playlist: false },
      },
      settings: {},
    },
  ];

  assert.deepEqual(getInstalledPluginCategories([]), []);
  assert.deepEqual(getInstalledPluginCategories(plugins), [
    "华语平台",
    "国际流媒体",
    ARCHIVE_UNCATEGORIZED,
  ]);

  assert.equal(
    filterInstalledPlugins(plugins, "", PLUGIN_STATUS_ALL, ARCHIVE_FILTER_ALL)
      .length,
    4,
  );
  assert.deepEqual(
    filterInstalledPlugins(
      plugins,
      "",
      PLUGIN_STATUS_ENABLED,
      ARCHIVE_FILTER_ALL,
    ).map((p) => p.id),
    ["qq", "spotify"],
  );
  assert.deepEqual(
    filterInstalledPlugins(
      plugins,
      "",
      PLUGIN_STATUS_DISABLED,
      ARCHIVE_FILTER_ALL,
    ).map((p) => p.id),
    ["netease", "custom-rss"],
  );
  assert.deepEqual(
    filterInstalledPlugins(plugins, "", PLUGIN_STATUS_ALL, "华语平台").map(
      (p) => p.id,
    ),
    ["qq", "netease"],
  );
  assert.deepEqual(
    filterInstalledPlugins(
      plugins,
      "",
      PLUGIN_STATUS_ALL,
      ARCHIVE_UNCATEGORIZED,
    ).map((p) => p.id),
    ["custom-rss"],
  );

  // Case-insensitive match across ID, name, and description
  assert.deepEqual(
    filterInstalledPlugins(
      plugins,
      "EAPI",
      PLUGIN_STATUS_ALL,
      ARCHIVE_FILTER_ALL,
    ).map((p) => p.id),
    ["netease"],
  );
  assert.deepEqual(
    filterInstalledPlugins(
      plugins,
      "QQ",
      PLUGIN_STATUS_ENABLED,
      "华语平台",
    ).map((p) => p.id),
    ["qq"],
  );
  assert.deepEqual(
    filterInstalledPlugins(
      plugins,
      "not-found",
      PLUGIN_STATUS_ALL,
      ARCHIVE_FILTER_ALL,
    ),
    [],
  );
});
