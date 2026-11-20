import test from "node:test";
import assert from "node:assert/strict";
import {
  buildKeepExistingChoices,
  countPendingReviewItems,
  extractScopeDirFromFolderKey,
  formatLibraryRefreshNotice,
  getJobProgress,
  hasReadyJobItems,
  isFinishedJobStatus,
  isRunningJobStatus,
} from "../src/library-utils.ts";

test("extractScopeDirFromFolderKey handles all, JSON tuples, multi-root paths, and :: keys", () => {
  assert.equal(extractScopeDirFromFolderKey("all"), "");
  assert.equal(extractScopeDirFromFolderKey(""), "");
  assert.equal(
    extractScopeDirFromFolderKey(JSON.stringify(["/music", "."])),
    "",
  );
  assert.equal(
    extractScopeDirFromFolderKey(JSON.stringify(["/music", "incoming/rock"])),
    "incoming/rock",
  );
  assert.equal(
    extractScopeDirFromFolderKey(JSON.stringify(["/music", "/Albums\\2026/"])),
    "Albums/2026",
  );
  assert.equal(
    extractScopeDirFromFolderKey(JSON.stringify(["/music2", "."]), {
      hasMultipleRoots: true,
    }),
    "/music2",
  );
  assert.equal(
    extractScopeDirFromFolderKey(JSON.stringify(["/music2", "Lossless/FLAC"]), {
      hasMultipleRoots: true,
    }),
    "/music2/Lossless/FLAC",
  );
  assert.equal(
    extractScopeDirFromFolderKey(JSON.stringify(["D:\\Music", "Live\\2025"]), {
      hasMultipleRoots: true,
    }),
    "D:\\Music\\Live\\2025",
  );
  assert.equal(
    extractScopeDirFromFolderKey("root-1::incoming/pop"),
    "incoming/pop",
  );
  assert.equal(extractScopeDirFromFolderKey("root-1::."), "");
});

test("getJobProgress tracks running states, clamping, and indeterminate phases", () => {
  assert.equal(isRunningJobStatus("scanning"), true);
  assert.equal(isRunningJobStatus("queued"), true);
  assert.equal(isRunningJobStatus("applying"), true);
  assert.equal(isRunningJobStatus("restoring"), true);
  assert.equal(isRunningJobStatus("preview_ready"), false);
  assert.equal(isFinishedJobStatus("complete"), true);
  assert.equal(isFinishedJobStatus("needs_review"), false);

  assert.deepEqual(
    getJobProgress({ status: "scanning", processed: 0, total: 120 }),
    {
      running: true,
      processed: 0,
      total: 120,
      percent: 0,
      indeterminate: true,
    },
  );
  assert.deepEqual(
    getJobProgress({ status: "scanning", processed: 30, total: 120 }),
    {
      running: true,
      processed: 30,
      total: 120,
      percent: 25,
      indeterminate: false,
    },
  );
  assert.deepEqual(
    getJobProgress({ status: "applying", processed: 25, total: 20 }),
    {
      running: true,
      processed: 20,
      total: 20,
      percent: 100,
      indeterminate: false,
    },
  );
  assert.deepEqual(
    getJobProgress({ status: "complete", processed: 10, total: 10 }),
    {
      running: false,
      processed: 10,
      total: 10,
      percent: 100,
      indeterminate: false,
    },
  );
});

test("buildKeepExistingChoices and item helpers only select needs_review items", () => {
  const items = [
    { id: "item-1", status: "needs_review" },
    { id: "item-2", status: "preview_ready" },
    { id: "item-3", status: "needs_review" },
    { id: "item-4", status: "complete" },
    { id: "", status: "needs_review" },
  ];
  assert.deepEqual(buildKeepExistingChoices(items), {
    "item-1": "existing",
    "item-3": "existing",
  });
  assert.equal(countPendingReviewItems(items), 3);
  assert.equal(hasReadyJobItems(items), true);
  assert.equal(
    hasReadyJobItems([{ id: "item-1", status: "needs_review" }]),
    false,
  );
  assert.deepEqual(buildKeepExistingChoices(undefined), {});
  assert.equal(hasReadyJobItems(undefined), false);
});

test("formatLibraryRefreshNotice summarizes scanned, updated, missing, and unhealthy roots", () => {
  assert.equal(
    formatLibraryRefreshNotice({
      roots_scanned: 1,
      scanned_files: 42,
      unchanged_files: 39,
      updated_or_new: 2,
      marked_missing: 1,
      unhealthy_roots: [],
    }),
    "已刷新音乐库：共扫描 42 首，新增/更新 2 首，移除 1 条失效记录",
  );
  assert.equal(
    formatLibraryRefreshNotice({
      roots_scanned: 2,
      scanned_files: 10,
      unchanged_files: 10,
      updated_or_new: 0,
      marked_missing: 0,
      unhealthy_roots: ["/mnt/offline"],
    }),
    "已刷新音乐库：共扫描 10 首，新增/更新 0 首，移除 0 条失效记录（异常目录：/mnt/offline）",
  );
  assert.equal(formatLibraryRefreshNotice(null), "已刷新音乐库");
});
