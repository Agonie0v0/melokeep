import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_MIN_MATCH_SCORE,
  MIN_MATCH_SCORE_MAX,
  MIN_MATCH_SCORE_MIN,
  buildMetadataSourcesSummary,
  describeMinMatchScore,
  partitionMetadataSources,
  pickBuiltinMetadataSourceIds,
  pickRecommendedMetadataSourceIds,
  sanitizeMinMatchScore,
} from "../src/library-utils.ts";
import {
  DOCKER_SOCK_COMPOSE_LINE,
  DOCKER_SOCK_RUN_FLAG,
  formatSelfUpdateState,
  getSelfUpdateActionMode,
} from "../src/update-utils.ts";
import {
  applySystemUpdate,
  checkSystemHealth,
  fetchSystemUpdateStatus,
} from "../src/api.ts";

test("sanitizeMinMatchScore clamps to 50..100 and falls back cleanly", () => {
  assert.equal(MIN_MATCH_SCORE_MIN, 50);
  assert.equal(MIN_MATCH_SCORE_MAX, 100);
  assert.equal(DEFAULT_MIN_MATCH_SCORE, 85);

  assert.equal(sanitizeMinMatchScore(88), 88);
  assert.equal(sanitizeMinMatchScore("76.4"), 76);
  assert.equal(sanitizeMinMatchScore(10), 50);
  assert.equal(sanitizeMinMatchScore(140), 100);
  assert.equal(sanitizeMinMatchScore(undefined), 85);
  assert.equal(sanitizeMinMatchScore(null), 85);
  assert.equal(sanitizeMinMatchScore(true), 85);
  assert.equal(sanitizeMinMatchScore(""), 85);
  assert.equal(sanitizeMinMatchScore(NaN, 78), 78);
});

test("describeMinMatchScore returns strict / balanced / loose guidance", () => {
  assert.match(describeMinMatchScore(92), /严格/);
  assert.match(describeMinMatchScore(85), /严格/);
  assert.match(describeMinMatchScore(78), /均衡/);
  assert.match(describeMinMatchScore(75), /均衡/);
  assert.match(describeMinMatchScore(60), /宽松/);
});

test("metadata source selection, partitioning, and summary helpers work for builtin and plugin sources", () => {
  const sources = [
    {
      id: "itunes",
      label: "iTunes",
      builtin: true,
      recommended: true,
      recommend_reason: "封面与曲目准确",
      fields: ["title", "artist", "cover"],
    },
    {
      id: "musicbrainz",
      label: "MusicBrainz",
      builtin: true,
      recommended: false,
      fields: ["artist", "album"],
    },
    {
      id: "discogs",
      label: "Discogs",
      builtin: true,
      recommended: false,
      fields: ["album"],
    },
    {
      id: "custom_lrc",
      label: "Custom LRC Plugin",
      builtin: false,
      recommended: true,
      recommend_reason: "高覆盖中文歌词",
      fields: ["lyrics"],
    },
  ];

  assert.deepEqual(pickRecommendedMetadataSourceIds(sources), [
    "itunes",
    "custom_lrc",
  ]);
  assert.deepEqual(
    pickRecommendedMetadataSourceIds([
      { id: "itunes", label: "iTunes" },
      { id: "musicbrainz", label: "MusicBrainz" },
      { id: "discogs", label: "Discogs" },
      { id: "lrclib", label: "LRCLIB" },
    ]),
    ["itunes", "musicbrainz", "lrclib"],
  );
  assert.deepEqual(pickBuiltinMetadataSourceIds(sources), [
    "itunes",
    "musicbrainz",
    "discogs",
  ]);

  const allGroups = partitionMetadataSources(sources);
  assert.equal(allGroups.builtin.length, 3);
  assert.equal(allGroups.plugins.length, 1);

  const filtered = partitionMetadataSources(sources, "中文歌词");
  assert.equal(filtered.builtin.length, 0);
  assert.deepEqual(
    filtered.plugins.map((item) => item.id),
    ["custom_lrc"],
  );

  const summary = buildMetadataSourcesSummary(
    ["itunes", "custom_lrc"],
    sources,
  );
  assert.match(summary, /已选 2 \/ 4 个/);
  assert.match(summary, /iTunes/);
  assert.match(summary, /Custom LRC Plugin/);

  const emptySummary = buildMetadataSourcesSummary([], sources);
  assert.match(emptySummary, /已选 0 \/ 4 个/);
  assert.match(emptySummary, /未选择/);
});

test("update-utils formats action mode and self-update stages accurately", () => {
  assert.equal(
    DOCKER_SOCK_COMPOSE_LINE,
    "- /var/run/docker.sock:/var/run/docker.sock",
  );
  assert.equal(
    DOCKER_SOCK_RUN_FLAG,
    "-v /var/run/docker.sock:/var/run/docker.sock",
  );
  assert.equal(getSelfUpdateActionMode(null), "hidden");
  assert.equal(
    getSelfUpdateActionMode({
      current_version: "1.0.0",
      current_commit: "abc",
      latest_version: "1.1.0",
      release_url: "",
      release_notes: "",
      published_at: "",
      has_update: true,
      checked_at: "",
      target: { mode: "docker", image: "ghcr.io/x/y:latest" },
      self_update: {
        supported: true,
        stage: "idle",
        progress: 0,
      },
    }),
    "primary",
  );
  assert.equal(
    getSelfUpdateActionMode({
      current_version: "1.1.0",
      current_commit: "abc",
      latest_version: "1.1.0",
      release_url: "",
      release_notes: "",
      published_at: "",
      has_update: false,
      checked_at: "",
      target: { mode: "docker", image: "ghcr.io/x/y:latest" },
      self_update: {
        supported: true,
        stage: "idle",
        progress: 0,
      },
    }),
    "up_to_date",
  );
  assert.equal(
    getSelfUpdateActionMode({
      current_version: "1.0.0",
      current_commit: "abc",
      latest_version: "1.1.0",
      release_url: "",
      release_notes: "",
      published_at: "",
      has_update: true,
      checked_at: "",
      target: { mode: "docker", image: "ghcr.io/x/y:latest" },
      self_update: {
        supported: false,
        reason: "未挂载 docker.sock",
        stage: "idle",
        progress: 0,
      },
    }),
    "disabled_hint",
  );

  const pulling = formatSelfUpdateState({
    supported: true,
    stage: "pulling",
    progress: 42,
    message: "Downloading layer",
  });
  assert.equal(pulling.isRunning, true);
  assert.equal(pulling.showProgress, true);
  assert.equal(pulling.progress, 42);
  assert.equal(pulling.detail, "Downloading layer");

  const restarting = formatSelfUpdateState({
    supported: true,
    stage: "restarting",
    progress: 95,
  });
  assert.equal(restarting.isRestarting, true);
  assert.equal(restarting.isRunning, true);

  const failed = formatSelfUpdateState({
    supported: true,
    stage: "failed",
    progress: 120,
    error: "pull denied",
  });
  assert.equal(failed.isFailed, true);
  assert.equal(failed.isRunning, false);
  assert.equal(failed.progress, 100);
  assert.equal(failed.detail, "pull denied");
});

test("self-update API helpers call expected endpoints and handle errors", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  try {
    globalThis.fetch = async (url, init = {}) => {
      calls.push({ url, method: init.method || "GET", cache: init.cache });
      if (url === "/api/system/update/apply" && init.method === "POST") {
        return new Response(
          JSON.stringify({
            supported: true,
            stage: "pulling",
            progress: 10,
            message: "开始拉取镜像",
          }),
          { status: 202, headers: { "Content-Type": "application/json" } },
        );
      }
      if (url === "/api/system/update/status") {
        return new Response(
          JSON.stringify({
            supported: true,
            stage: "restarting",
            progress: 95,
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }
      if (url === "/api/health") {
        return new Response(JSON.stringify({ ok: true }), { status: 200 });
      }
      return new Response("not found", { status: 404 });
    };

    const applied = await applySystemUpdate();
    assert.equal(applied.stage, "pulling");
    const status = await fetchSystemUpdateStatus();
    assert.equal(status.stage, "restarting");
    const healthy = await checkSystemHealth();
    assert.equal(healthy, true);

    assert.deepEqual(calls, [
      { url: "/api/system/update/apply", method: "POST", cache: undefined },
      { url: "/api/system/update/status", method: "GET", cache: undefined },
      { url: "/api/health", method: "GET", cache: "no-store" },
    ]);

    globalThis.fetch = async (url) => {
      if (url === "/api/system/update/apply") {
        return new Response(JSON.stringify({ detail: "更新任务已在进行中" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new TypeError("network down");
    };

    await assert.rejects(() => applySystemUpdate(), /更新任务已在进行中/);
    assert.equal(await checkSystemHealth(), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
