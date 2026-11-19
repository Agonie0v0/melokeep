import test from "node:test";
import assert from "node:assert/strict";
import {
  buildPluginSettingsPayload,
  formatAllowedOrigins,
  formatMetadataSourceLabel,
  isMaskedSecretValue,
  isPluginSource,
  parseAllowedOrigins,
  pickPlaylistImportSource,
  pluginCapabilityBadges,
  sourceEngineKey,
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
