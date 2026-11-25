import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, deleteDownloadJob, downloadDeletePath } from "../src/api.ts";
import {
  countDeletableDownloadFiles,
  formatDownloadRemovalError,
  formatDownloadRemovalNotice,
  getDownloadProcessingAction,
  hasDeletableDownloadFile,
  removeDownloadJobsBatch,
} from "../src/download-utils.ts";
import { inferToastTone } from "../src/library-utils.ts";

test("hasDeletableDownloadFile and countDeletableDownloadFiles detect non-blank file paths", () => {
  assert.equal(hasDeletableDownloadFile({ file: null }), false);
  assert.equal(hasDeletableDownloadFile({ file: "" }), false);
  assert.equal(hasDeletableDownloadFile({ file: "   " }), false);
  assert.equal(
    hasDeletableDownloadFile({ file: "Artist A/sample.flac" }),
    true,
  );

  assert.equal(
    countDeletableDownloadFiles([
      { file: null },
      { file: "  " },
      { file: "Artist A/sample.flac" },
      { file: "Artist B/sample-b.mp3" },
    ]),
    2,
  );
});

test("downloadDeletePath and deleteDownloadJob append delete_file=1 only when requested and backed by a file", async () => {
  assert.equal(downloadDeletePath("job/1"), "/downloads/job%2F1");
  assert.equal(
    downloadDeletePath("job-1", { deleteFile: true, hasFile: false }),
    "/downloads/job-1",
  );
  assert.equal(
    downloadDeletePath("job-1", { deleteFile: false, hasFile: true }),
    "/downloads/job-1",
  );
  assert.equal(
    downloadDeletePath("job-1", { deleteFile: true, hasFile: true }),
    "/downloads/job-1?delete_file=1",
  );

  const prevFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, method: init?.method });
    if (String(url).includes("job-err")) {
      return new Response(
        JSON.stringify({ detail: "缺少删除本地音乐文件的权限" }),
        { status: 403, headers: { "Content-Type": "application/json" } },
      );
    }
    return new Response(
      JSON.stringify({
        removed: true,
        file_deleted: String(url).includes("delete_file=1"),
        track_ids: String(url).includes("delete_file=1")
          ? ["local-track-1"]
          : [],
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  };

  try {
    const keepRes = await deleteDownloadJob("job-1", {
      deleteFile: false,
      hasFile: true,
    });
    assert.deepEqual(keepRes, {
      removed: true,
      file_deleted: false,
      track_ids: [],
    });

    const delRes = await deleteDownloadJob("job-2", {
      deleteFile: true,
      hasFile: true,
    });
    assert.deepEqual(delRes, {
      removed: true,
      file_deleted: true,
      track_ids: ["local-track-1"],
    });

    const noFileRes = await deleteDownloadJob("job-3", {
      deleteFile: true,
      hasFile: false,
    });
    assert.deepEqual(noFileRes, {
      removed: true,
      file_deleted: false,
      track_ids: [],
    });

    await assert.rejects(
      () => deleteDownloadJob("job-err", { deleteFile: true, hasFile: true }),
      (err) =>
        err instanceof ApiError &&
        err.status === 403 &&
        err.message.includes("缺少删除本地音乐文件的权限"),
    );

    assert.deepEqual(calls, [
      { url: "/api/downloads/job-1", method: "DELETE" },
      { url: "/api/downloads/job-2?delete_file=1", method: "DELETE" },
      { url: "/api/downloads/job-3", method: "DELETE" },
      { url: "/api/downloads/job-err?delete_file=1", method: "DELETE" },
    ]);
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test("formatDownloadRemovalNotice covers all three branches and resolves to success toast tone", () => {
  const kept = formatDownloadRemovalNotice(2, false, 0);
  assert.equal(kept, "下载记录已移除，已下载文件保留");
  assert.equal(inferToastTone(kept), "success");

  const deleted = formatDownloadRemovalNotice(3, true, 2);
  assert.equal(deleted, "已移除 3 条下载记录，并同步删除 2 个本地音频文件");
  assert.equal(inferToastTone(deleted), "success");

  const missing = formatDownloadRemovalNotice(2, true, 0);
  assert.equal(missing, "已移除 2 条下载记录（未发现可删除的本地文件）");
  assert.equal(inferToastTone(missing), "success");
});

test("removeDownloadJobsBatch aggregates deleted files, deduplicates track_ids, and tolerates partial failures", async () => {
  const targets = [
    { id: "job-1", file: "Artist A/Track A.flac" },
    { id: "job-2", file: null },
    { id: "job-3", file: "Artist B/Track B.flac" },
    { id: "job-4", file: "Artist C/Track C.flac" },
  ];
  const calls = [];
  const removedCallbacks = [];

  const summary = await removeDownloadJobsBatch(
    targets,
    true,
    async (job, shouldDeleteFile) => {
      calls.push({ id: job.id, shouldDeleteFile });
      if (job.id === "job-3") {
        throw new Error("缺少删除本地音乐文件的权限");
      }
      if (job.id === "job-1") {
        return {
          removed: true,
          file_deleted: true,
          track_ids: ["local-1", "local-2"],
        };
      }
      if (job.id === "job-2") {
        return { removed: true, file_deleted: false, track_ids: [] };
      }
      return {
        removed: true,
        file_deleted: true,
        track_ids: ["local-2", "local-3", "  "],
      };
    },
    (jobId) => removedCallbacks.push(jobId),
  );

  assert.deepEqual(calls, [
    { id: "job-1", shouldDeleteFile: true },
    { id: "job-2", shouldDeleteFile: false },
    { id: "job-3", shouldDeleteFile: true },
    { id: "job-4", shouldDeleteFile: true },
  ]);
  assert.deepEqual(summary.removedIds, ["job-1", "job-2", "job-4"]);
  assert.deepEqual(removedCallbacks, ["job-1", "job-2", "job-4"]);
  assert.equal(summary.removedCount, 3);
  assert.equal(summary.deletedFileCount, 2);
  assert.deepEqual([...summary.deletedTrackIds].sort(), [
    "local-1",
    "local-2",
    "local-3",
  ]);
  assert.deepEqual([...summary.deletedFilePaths].sort(), [
    "Artist A/Track A.flac",
    "Artist C/Track C.flac",
  ]);
  assert.deepEqual(summary.errors, ["缺少删除本地音乐文件的权限"]);
  assert.equal(
    formatDownloadRemovalError(summary.errors),
    "缺少删除本地音乐文件的权限",
  );
  assert.equal(
    formatDownloadRemovalError(["错误一", "错误二"]),
    "错误一（共 2 项失败）",
  );
});

test("getDownloadProcessingAction maps actionable statuses and ignores blank or non-actionable states", () => {
  assert.equal(getDownloadProcessingAction(), null);
  assert.equal(getDownloadProcessingAction(null), null);
  assert.equal(
    getDownloadProcessingAction({
      id: "",
      status: "needs_review",
      detail: "待确认",
    }),
    null,
  );
  assert.equal(
    getDownloadProcessingAction({
      id: "   ",
      status: "needs_review",
      detail: "待确认",
    }),
    null,
  );

  assert.deepEqual(
    getDownloadProcessingAction({
      id: "job-review",
      status: "needs_review",
      detail: "存在待确认曲目",
    }),
    { jobId: "job-review", label: "前往确认", emphasis: "review" },
  );

  for (const status of ["ready", "preview_ready"]) {
    assert.deepEqual(
      getDownloadProcessingAction({
        id: `job-${status}`,
        status,
        detail: "可应用",
      }),
      { jobId: `job-${status}`, label: "前往应用", emphasis: "apply" },
    );
  }

  for (const status of ["needs_attention", "failed", "partial"]) {
    assert.deepEqual(
      getDownloadProcessingAction({
        id: `job-${status}`,
        status,
        detail: "需查看",
      }),
      { jobId: `job-${status}`, label: "查看处理", emphasis: "inspect" },
    );
  }

  for (const status of [
    "queued",
    "scanning",
    "identifying",
    "tagging",
    "organizing",
    "indexing",
    "applying",
    "complete",
    "paused",
    "cancelled",
    "restoring",
    "restored",
    "",
    "unknown",
  ]) {
    assert.equal(
      getDownloadProcessingAction({
        id: "job-other",
        status,
        detail: "",
      }),
      null,
    );
  }
});
