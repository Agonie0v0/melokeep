import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_MIN_MATCH_SCORE,
  MIN_MATCH_SCORE_MAX,
  MIN_MATCH_SCORE_MIN,
  buildMetadataSourcesSummary,
  describeMinMatchScore,
  inferToastTone,
  partitionMetadataSources,
  pickBuiltinMetadataSourceIds,
  pickBuiltinRecommendedMetadataSourceIds,
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
    "custom_lrc",
    "itunes",
  ]);
  assert.deepEqual(pickBuiltinRecommendedMetadataSourceIds(sources), [
    "itunes",
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

test("pickRecommendedMetadataSourceIds prioritizes up to 3 plugins and skips musicbrainz when title+album are covered", () => {
  const catalogWithStructuredPlugins = [
    {
      id: "itunes",
      label: "iTunes",
      builtin: true,
      recommended: true,
      fields: ["title", "artist", "album", "cover"],
    },
    {
      id: "musicbrainz",
      label: "MusicBrainz",
      builtin: true,
      recommended: true,
      fields: ["title", "artist", "album"],
    },
    {
      id: "lrclib",
      label: "LRCLIB",
      builtin: true,
      recommended: true,
      fields: ["lyrics"],
    },
    {
      id: "plugin_full_1",
      label: "Full Plugin 1",
      builtin: false,
      recommended: true,
      fields: ["title", "artist", "album", "cover", "lyrics"],
    },
    {
      id: "plugin_full_2",
      label: "Full Plugin 2",
      builtin: false,
      recommended: true,
      fields: ["title", "album", "lyrics"],
    },
    {
      id: "plugin_full_3",
      label: "Full Plugin 3",
      builtin: false,
      recommended: true,
      fields: ["title", "album"],
    },
    {
      id: "plugin_full_4",
      label: "Full Plugin 4",
      builtin: false,
      recommended: true,
      fields: ["title", "album", "lyrics"],
    },
    {
      id: "plugin_optional",
      label: "Optional Plugin",
      builtin: false,
      recommended: false,
      fields: ["lyrics"],
    },
  ];

  assert.deepEqual(
    pickRecommendedMetadataSourceIds(catalogWithStructuredPlugins),
    ["plugin_full_1", "plugin_full_2", "plugin_full_3", "itunes", "lrclib"],
  );
  assert.deepEqual(
    pickRecommendedMetadataSourceIds(catalogWithStructuredPlugins, 2),
    ["plugin_full_1", "plugin_full_2", "itunes", "lrclib"],
  );
  assert.deepEqual(
    pickBuiltinRecommendedMetadataSourceIds(catalogWithStructuredPlugins),
    ["itunes", "musicbrainz", "lrclib"],
  );

  const lyricsOnlyPluginCatalog = [
    {
      id: "itunes",
      label: "iTunes",
      builtin: true,
      recommended: true,
      fields: ["title", "artist"],
    },
    {
      id: "musicbrainz",
      label: "MusicBrainz",
      builtin: true,
      recommended: true,
      fields: ["title", "album"],
    },
    {
      id: "lrclib",
      label: "LRCLIB",
      builtin: true,
      recommended: true,
      fields: ["lyrics"],
    },
    {
      id: "custom_lrc",
      label: "Custom LRC Plugin",
      builtin: false,
      recommended: true,
      fields: ["lyrics"],
    },
  ];
  assert.deepEqual(pickRecommendedMetadataSourceIds(lyricsOnlyPluginCatalog), [
    "custom_lrc",
    "itunes",
    "musicbrainz",
    "lrclib",
  ]);

  assert.deepEqual(
    pickBuiltinRecommendedMetadataSourceIds([
      { id: "lrclib", label: "LRCLIB", builtin: true },
      { id: "itunes", label: "iTunes", builtin: true },
      {
        id: "plugin_full_1",
        label: "Full Plugin 1",
        builtin: false,
        recommended: true,
      },
    ]),
    ["itunes", "lrclib"],
  );
  assert.deepEqual(pickBuiltinRecommendedMetadataSourceIds(undefined), [
    "itunes",
    "musicbrainz",
    "lrclib",
  ]);
  assert.deepEqual(pickRecommendedMetadataSourceIds(undefined), [
    "itunes",
    "musicbrainz",
    "lrclib",
  ]);
});

test("inferToastTone classifies error, warning, success, and info including composite notices", () => {
  assert.equal(inferToastTone("部分文件删除失败：demo.flac"), "error");
  assert.equal(inferToastTone("搜索连接已断开，请重新搜索"), "error");
  assert.equal(inferToastTone("请求超时，请稍后重试"), "error");
  assert.equal(inferToastTone("凭证无效或已过期"), "error");
  assert.equal(inferToastTone("无法播放该曲目"), "error");
  assert.equal(inferToastTone("处理出现异常，请重试"), "error");
  assert.equal(inferToastTone("接口返回错误"), "error");

  assert.equal(
    inferToastTone(
      "已刷新音乐库：共扫描 10 首，新增/更新 0 首，移除 0 条失效记录（异常目录：/mnt/offline）",
    ),
    "warning",
  );
  assert.equal(inferToastTone("已自动跳过 3 首付费/需登录曲目"), "warning");
  assert.equal(inferToastTone("请先同步歌单，再移除歌曲"), "warning");
  assert.equal(inferToastTone("请输入新歌单名称"), "warning");
  assert.equal(inferToastTone("正在确认可下载音质…"), "warning");
  assert.equal(inferToastTone("当前暂不可执行此操作"), "warning");
  assert.equal(inferToastTone("警告：存在未保存修改"), "warning");

  assert.equal(inferToastTone("已加入 5 首下载任务"), "success");
  assert.equal(inferToastTone("音乐文件已删除"), "success");
  assert.equal(inferToastTone("下载记录已移除，已下载文件保留"), "success");
  assert.equal(inferToastTone("已刷新音乐库"), "success");
  assert.equal(inferToastTone("操作已完成"), "success");
  assert.equal(inferToastTone("已保存设置"), "success");
  assert.equal(inferToastTone("已更新搜索音源（3 个）"), "success");
  assert.equal(inferToastTone("已关闭睡眠定时"), "success");
  assert.equal(inferToastTone("已开启睡眠定时：15 分钟后暂停播放"), "success");
  assert.equal(inferToastTone("已设置：播完本首后停止播放"), "success");
  assert.equal(inferToastTone("已勾选第 1–10 集"), "success");
  assert.equal(inferToastTone("已全选当前分集"), "success");
  assert.equal(inferToastTone("已安排下一首播放：Example Song"), "success");
  assert.equal(inferToastTone("连接成功"), "success");

  assert.equal(inferToastTone("睡眠定时已结束，已停止播放"), "info");
  assert.equal(inferToastTone("当前未启用登录认证"), "info");
  assert.equal(inferToastTone(""), "info");
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
