import { useEffect, useRef, useState } from "react";
import {
  ArrowLeft,
  Check,
  CheckSquare,
  Download,
  FolderInput,
  History,
  RefreshCw,
  RotateCcw,
  Trash2,
  X,
} from "lucide-react";
import { ApiError, api } from "./api";
import { ConfirmDialog } from "./components";
import {
  buildKeepExistingChoices,
  countPendingReviewItems,
  getJobProgress,
  hasReadyJobItems,
  isFinishedJobStatus as isFinishedStatus,
  isRunningJobStatus as isRunningStatus,
} from "./library-utils";
import { processingLabels, tagLabels } from "./LibrarySettings";
import { formatMetadataSourceLabel } from "./plugin-utils";
import type { LibraryConfig, LibraryJob, MetadataSource } from "./types";

const toEpochMs = (ts: number) => (ts > 1e11 ? ts : ts * 1000);

const jobStatusLabels: Record<string, string> = {
  ...processingLabels,
  scanning: "正在扫描",
  preview_ready: "预览就绪",
  applying: "正在写入",
  completed: "已完成",
  needs_attention: "需人工处理",
  running: "运行中",
};
type DeleteJobResponse = {
  deleted: boolean;
  id: string;
};
type BatchDeleteJobsResponse = {
  deleted: number;
  deleted_ids: string[];
  skipped_running?: string[];
};
type DeleteConfirmState =
  | { mode: "single"; job: LibraryJob }
  | { mode: "selected"; ids: string[] }
  | { mode: "finished" };
const actionNames: Record<string, string> = {
  scrape: "刮削标签",
  organize: "整理目录",
  both: "刮削并整理",
  edit: "编辑标签",
  extract: "文件名提取",
  clean: "清理标签",
  duplicates: "重复文件检查",
  rename: "批量重命名",
  export_lyrics: "导出歌词",
  import_lyrics: "嵌入歌词",
  export_cover: "导出专辑封面",
  import_cover: "嵌入专辑封面",
  clear: "清空内嵌标签",
  convert: "格式转换",
  split: "CUE 分轨",
  quality: "音频完整性与参数检查",
  artist_artwork: "刮削艺术家图片",
};
type Version = {
  job_id: string;
  created_at: number;
  action: string;
  status: string;
  restorable: boolean;
  before: Record<string, string>;
  after: Record<string, string>;
};

export function LibraryJobs({
  focus,
  changed,
  onStartDirectory,
}: {
  focus: string;
  changed: () => void;
  onStartDirectory?: () => void;
}) {
  const [jobs, setJobs] = useState<LibraryJob[]>([]);
  const [selected, setSelected] = useState(focus);
  const [detail, setDetail] = useState<LibraryJob | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState("");
  const [loading, setLoading] = useState(true);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [filter, setFilter] = useState("all");
  const [queries, setQueries] = useState<Record<string, string>>({});
  const [versions, setVersions] = useState<Record<string, Version[]>>({});
  const [metadataSources, setMetadataSources] = useState<MetadataSource[]>([]);
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [confirmDelete, setConfirmDelete] = useState<DeleteConfirmState | null>(
    null,
  );
  const previousStatus = useRef("");
  const actionStripRef = useRef<HTMLDivElement>(null);
  const runningPollRef = useRef(false);
  const scrollActionStripError = () => {
    requestAnimationFrame(() =>
      actionStripRef.current?.scrollIntoView?.({
        block: "nearest",
        behavior: "smooth",
      }),
    );
  };
  useEffect(() => {
    let active = true;
    void api<LibraryConfig>("/library/settings")
      .then((cfg) => {
        if (active && Array.isArray(cfg?.metadata_sources)) {
          setMetadataSources(cfg.metadata_sources);
        }
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (focus) setSelected(focus);
  }, [focus]);
  useEffect(() => {
    let stopped = false;
    let timer: number | undefined;
    runningPollRef.current = false;
    async function load() {
      try {
        const result = await api<{ jobs: LibraryJob[]; has_more: boolean }>(
          `/library/jobs?offset=${offset}&size=30&status=${filter}`,
        );
        if (stopped) return;
        setJobs(result.jobs);
        setHasMore(result.has_more);
        if (selected) {
          const value = await api<LibraryJob>(`/library/jobs/${selected}`);
          if (stopped) return;
          if (
            previousStatus.current !== `${value.id}:${value.status}` &&
            ["complete", "partial", "needs_review", "restored"].includes(
              value.status,
            )
          )
            changed();
          previousStatus.current = `${value.id}:${value.status}`;
          setDetail(value);
          if (isRunningStatus(value.status)) {
            runningPollRef.current = true;
            timer = window.setTimeout(() => void load(), 1600);
          } else {
            runningPollRef.current = false;
          }
        } else if (result.jobs.some((job) => isRunningStatus(job.status))) {
          runningPollRef.current = true;
          timer = window.setTimeout(() => void load(), 2000);
        } else {
          runningPollRef.current = false;
        }
      } catch (e) {
        if (stopped) return;
        setError((e as Error).message);
        if (e instanceof ApiError && (e.status === 401 || e.status === 404)) {
          runningPollRef.current = false;
        } else if (runningPollRef.current) {
          timer = window.setTimeout(() => void load(), 2500);
        }
      } finally {
        if (!stopped) setLoading(false);
      }
    }
    setLoading(true);
    setError("");
    void load();
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [selected, offset, refresh, filter]);
  async function action(name: string) {
    if (!detail) return;
    setBusy(name);
    setError("");
    setNotice("");
    try {
      const result = await api<LibraryJob>(
        `/library/jobs/${detail.id}/${name}`,
        "POST",
        name === "apply" ? {} : undefined,
      );
      if (name === "retry") {
        setSelected(result.id);
      } else {
        setDetail(result);
        setJobs((prev) => prev.map((j) => (j.id === result.id ? result : j)));
      }
      if (name === "apply") {
        if (result.status === "applying") {
          setNotice("已提交后台写入，正在归档处理…");
        } else if (result.status === "complete") {
          setNotice("归档完成");
        } else if (result.status === "partial") {
          setNotice("部分条目已完成归档");
        }
      }
      setRefresh((value) => value + 1);
    } catch (e) {
      setError((e as Error).message);
      scrollActionStripError();
    } finally {
      setBusy("");
    }
  }
  async function choose(item: string, candidate: string) {
    if (!detail || !candidate) return;
    setBusy("preview");
    setError("");
    try {
      const updated = await api<LibraryJob>(
        `/library/jobs/${detail.id}/preview`,
        "POST",
        {
          choices: { [item]: candidate },
        },
      );
      setDetail(updated);
      setJobs((prev) => prev.map((j) => (j.id === updated.id ? updated : j)));
    } catch (e) {
      setError((e as Error).message);
      scrollActionStripError();
    } finally {
      setBusy("");
    }
  }
  async function keepAllPendingExisting() {
    if (!detail) return;
    const choices = buildKeepExistingChoices(detail.items);
    const count = Object.keys(choices).length;
    if (!count) return;
    setBusy("keep-existing");
    setError("");
    setNotice("");
    try {
      const updated = await api<LibraryJob>(
        `/library/jobs/${detail.id}/preview`,
        "POST",
        { choices },
      );
      setDetail(updated);
      setJobs((prev) => prev.map((j) => (j.id === updated.id ? updated : j)));
      setNotice(`已为 ${count} 首待确认曲目保留现有标签`);
    } catch (e) {
      setError((e as Error).message);
      scrollActionStripError();
    } finally {
      setBusy("");
    }
  }
  async function executeDelete() {
    if (!confirmDelete) return;
    setBusy("delete");
    setError("");
    setNotice("");
    try {
      if (confirmDelete.mode === "single") {
        const targetId = confirmDelete.job.id;
        await api<DeleteJobResponse>(
          `/library/jobs/${encodeURIComponent(targetId)}`,
          "DELETE",
        );
        setJobs((prev) => prev.filter((item) => item.id !== targetId));
        setSelectedIds((prev) => prev.filter((id) => id !== targetId));
        if (selected === targetId) {
          setSelected("");
          setDetail(null);
        }
        setNotice(
          "已删除整理记录（仅移除整理记录与预览历史，不会删除磁盘原音频文件）。",
        );
      } else if (confirmDelete.mode === "selected") {
        const ids = confirmDelete.ids;
        const result = await api<BatchDeleteJobsResponse>(
          "/library/jobs/batch-delete",
          "POST",
          { ids, mode: "" },
        );
        const deletedSet = new Set(result.deleted_ids || []);
        setJobs((prev) => prev.filter((item) => !deletedSet.has(item.id)));
        setSelectedIds((prev) => prev.filter((id) => !deletedSet.has(id)));
        if (deletedSet.size > 0) {
          setSelectMode(false);
        }
        const skippedCount = result.skipped_running?.length || 0;
        setNotice(
          skippedCount > 0
            ? `已删除 ${result.deleted} 条整理记录，跳过 ${skippedCount} 条运行中任务（未删除磁盘原音频）。`
            : `已删除 ${result.deleted} 条整理记录（仅移除记录，未删除磁盘原音频）。`,
        );
      } else {
        const result = await api<BatchDeleteJobsResponse>(
          "/library/jobs/batch-delete",
          "POST",
          { ids: [], mode: "finished" },
        );
        const deletedSet = new Set(result.deleted_ids || []);
        setJobs((prev) => prev.filter((item) => !deletedSet.has(item.id)));
        setSelectedIds((prev) => prev.filter((id) => !deletedSet.has(id)));
        setNotice(
          result.deleted > 0
            ? `已清理 ${result.deleted} 条已结束整理记录（仅移除记录，未删除磁盘原音频）。`
            : "暂无可清理的已结束整理记录。",
        );
      }
      setConfirmDelete(null);
      setRefresh((value) => value + 1);
    } catch (e) {
      setError((e as Error).message);
      setConfirmDelete(null);
    } finally {
      setBusy("");
    }
  }
  const visibleJobs = jobs.filter(
    (job) =>
      filter === "all" ||
      (filter === "active"
        ? isRunningStatus(job.status)
        : filter === "failed"
          ? ["failed", "partial", "paused"].includes(job.status)
          : filter === "ready"
            ? ["ready", "preview_ready"].includes(job.status)
            : job.status === filter),
  );
  const deletableVisibleJobs = visibleJobs.filter(
    (job) => !isRunningStatus(job.status),
  );
  const selectedDeletableIds = selectedIds.filter((id) =>
    deletableVisibleJobs.some((job) => job.id === id),
  );
  const allVisibleSelected =
    deletableVisibleJobs.length > 0 &&
    deletableVisibleJobs.every((job) => selectedDeletableIds.includes(job.id));
  const canClearFinished =
    jobs.some((job) => isFinishedStatus(job.status)) ||
    hasMore ||
    filter !== "all";
  const detailProgress = detail ? getJobProgress(detail) : null;
  const pendingReviewCount = detail ? countPendingReviewItems(detail.items) : 0;
  const hasReadyItems = detail ? hasReadyJobItems(detail.items) : false;
  return (
    <section className="view library-jobs">
      <div className="jobs-top-bar">
        {selected ? (
          <button
            className="playlist-back jobs-back-btn"
            onClick={() => {
              setSelected("");
              setDetail(null);
            }}
          >
            <ArrowLeft />
            全部任务
          </button>
        ) : (
          <div className="library-job-filters" aria-label="筛选整理任务">
            {[
              ["all", "全部"],
              ["needs_review", "待确认"],
              ["ready", "待应用"],
              ["active", "处理中"],
              ["failed", "失败 / 部分完成"],
            ].map(([id, label]) => (
              <button
                key={id}
                type="button"
                className={`job-filter-tab ${filter === id ? "active" : ""}`}
                aria-pressed={filter === id}
                onClick={() => {
                  setFilter(id);
                  setOffset(0);
                  setSelectedIds([]);
                }}
              >
                {label}
              </button>
            ))}
          </div>
        )}
        <div className="library-heading-actions">
          {!selected && (
            <>
              {onStartDirectory && (
                <button
                  type="button"
                  className="secondary"
                  disabled={loading || !!busy}
                  onClick={onStartDirectory}
                >
                  <FolderInput />
                  发起目录整理
                </button>
              )}
              <button
                type="button"
                className="secondary"
                disabled={loading || !!busy || !canClearFinished}
                title={
                  canClearFinished
                    ? "清理已结束的整理记录（不删除磁盘原音频）"
                    : "暂无已结束的整理记录"
                }
                onClick={() => {
                  setError("");
                  setNotice("");
                  setConfirmDelete({ mode: "finished" });
                }}
              >
                <Trash2 />
                清理已完成
              </button>
              <button
                type="button"
                className="secondary"
                aria-pressed={selectMode}
                disabled={
                  loading ||
                  !!busy ||
                  (!selectMode && deletableVisibleJobs.length === 0)
                }
                title={
                  !selectMode && deletableVisibleJobs.length === 0
                    ? "当前列表暂无可删除记录"
                    : "多选并批量删除整理记录"
                }
                onClick={() => {
                  setSelectMode((prev) => !prev);
                  setSelectedIds([]);
                }}
              >
                <CheckSquare />
                {selectMode ? "退出多选" : "多选管理"}
              </button>
            </>
          )}
          <button
            type="button"
            className="secondary"
            disabled={loading || !!busy}
            onClick={() => setRefresh((value) => value + 1)}
          >
            <RefreshCw />
            刷新任务
          </button>
        </div>
      </div>
      {error && !(selected && detail) && (
        <p role="alert" className="library-error">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="library-inline-status">
          {notice}
        </p>
      )}
      {loading && !detail && <p role="status">正在读取整理任务…</p>}
      {selected && detail ? (
        <>
          <div className="processing-heading">
            <div>
              <h3>{actionNames[detail.action] || detail.action}</h3>
              <p>
                {detail.detail}
                {" · "}
                {new Date(toEpochMs(detail.created_at)).toLocaleString(
                  "zh-CN",
                )}{" "}
                · {detail.total} 首
              </p>
            </div>
            <span
              className={`processing-status status-dot-label is-${detail.status}`}
              role="status"
            >
              <i className="status-dot" aria-hidden="true" />
              {jobStatusLabels[detail.status] || detail.status}
            </span>
          </div>
          {detailProgress?.running && (
            <div
              className="processing-progress-card"
              role="status"
              aria-live="polite"
            >
              <div className="processing-progress-meta">
                <span>
                  {jobStatusLabels[detail.status] || detail.status}
                  {detail.detail ? ` · ${detail.detail}` : ""}
                </span>
                <strong>
                  已处理 {detailProgress.processed} / {detailProgress.total} 首
                  {detailProgress.total > 0
                    ? ` (${detailProgress.percent}%)`
                    : ""}
                </strong>
              </div>
              <div
                className={`processing-progress-track ${detailProgress.indeterminate ? "is-indeterminate" : ""}`}
                role="progressbar"
                aria-label="任务处理进度"
                aria-valuemin={0}
                aria-valuemax={Math.max(1, detailProgress.total)}
                aria-valuenow={detailProgress.processed}
              >
                <span
                  className="processing-progress-fill"
                  style={{
                    width: `${detailProgress.indeterminate ? 35 : detailProgress.percent}%`,
                  }}
                />
              </div>
            </div>
          )}
          <div className="processing-action-strip" ref={actionStripRef}>
            <div className="processing-action-meta">
              {(detail.status === "ready" ||
                detail.status === "preview_ready") && (
                <span className="processing-status-hint" role="status">
                  预览就绪，确认后写入磁盘
                </span>
              )}
              {detail.status === "needs_review" && (
                <span className="processing-status-hint">
                  请展开对应条目选定候选后应用
                </span>
              )}
              {detail.settings && (
                <span className="processing-rules">
                  来源：
                  {detail.settings.sources
                    .map((id) => formatMetadataSourceLabel(id, metadataSources))
                    .join("、") || "无"}{" "}
                  ·{" "}
                  {detail.settings.overwrite
                    ? "覆盖未锁定标签"
                    : "仅补缺失标签"}
                </span>
              )}
            </div>
            <div className="management-toolbar processing-toolbar-actions">
              {pendingReviewCount > 0 &&
                !isRunningStatus(detail.status) &&
                !["duplicates", "quality"].includes(detail.action) && (
                  <button
                    type="button"
                    className="secondary"
                    disabled={!!busy}
                    onClick={() => void keepAllPendingExisting()}
                  >
                    <CheckSquare />
                    {busy === "keep-existing"
                      ? "正在更新…"
                      : "全部待确认项保留现有标签"}
                  </button>
                )}
              {["ready", "preview_ready", "needs_review", "partial"].includes(
                detail.status,
              ) &&
                !["duplicates", "quality"].includes(detail.action) && (
                  <>
                    <button
                      className="primary"
                      disabled={!!busy || !hasReadyItems}
                      onClick={() => void action("apply")}
                    >
                      <Check />
                      {busy === "apply"
                        ? "正在写入…"
                        : detail.status === "ready" ||
                            detail.status === "preview_ready"
                          ? "立即写入并整理"
                          : "应用已确认的更改"}
                    </button>
                    {!hasReadyItems && (
                      <span className="processing-apply-hint" role="status">
                        请先在下方选择候选结果或点击‘保留现有标签’
                      </span>
                    )}
                  </>
                )}
              {["failed", "partial", "paused"].includes(detail.status) && (
                <button
                  className="secondary"
                  disabled={!!busy}
                  onClick={() => void action("retry")}
                >
                  <RefreshCw />
                  重新检查未完成文件
                </button>
              )}
              {isRunningStatus(detail.status) &&
                detail.status !== "restoring" && (
                  <button
                    className="secondary"
                    disabled={!!busy}
                    onClick={() => void action("cancel")}
                  >
                    <X />
                    取消后续处理
                  </button>
                )}
              <a
                className="secondary"
                href={`/api/library/jobs/${detail.id}/report`}
                download
              >
                <Download />
                导出处理报告
              </a>
              <button
                type="button"
                className="secondary"
                disabled={!!busy || isRunningStatus(detail.status)}
                title={
                  isRunningStatus(detail.status)
                    ? "任务运行中，请先取消后再删除"
                    : "仅删除整理记录，不删除磁盘原音频"
                }
                onClick={() => {
                  setError("");
                  setNotice("");
                  setConfirmDelete({ mode: "single", job: detail });
                }}
              >
                <Trash2 />
                {isRunningStatus(detail.status) ? "运行中不可删除" : "删除记录"}
              </button>
              {!isRunningStatus(detail.status) &&
                detail.items?.some(
                  (item) => item.operation_id && item.status === "complete",
                ) && (
                  <details className="restore-confirm">
                    <summary>
                      <RotateCcw />
                      恢复原文件
                    </summary>
                    <p>恢复本次修改前的音频标签、文件名和目录。</p>
                    <button
                      className="secondary"
                      disabled={!!busy}
                      onClick={() => void action("restore")}
                    >
                      确认恢复本次修改
                    </button>
                  </details>
                )}
            </div>
            {error && (
              <p role="alert" className="library-error processing-strip-error">
                {error}
              </p>
            )}
          </div>
          {detail.warnings?.map((warning, index) => (
            <p key={index} className="library-warning" role="status">
              {warning}
            </p>
          ))}
          {detail.duplicates && (
            <div className="duplicate-groups">
              {detail.duplicates.length ? (
                detail.duplicates.map((group, index) => (
                  <div className="processing-item" key={index}>
                    <h4>相同文件组 {index + 1}</h4>
                    <ul>
                      {group.map((item) => (
                        <li key={item.id}>
                          <strong>{item.name}</strong>
                          <p className="library-path">{item.path}</p>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))
              ) : (
                <p className="empty">
                  本次检查未发现重复候选。不同检查方式的判断范围不同，可切换方式进一步检查。
                </p>
              )}
            </div>
          )}
          {detail.action !== "duplicates" && (
            <div className="processing-items">
              {detail.items?.map((item) => {
                const chosenCandidate = item.candidates.find(
                  (c) => c.id === item.chosen,
                );
                const changes = Object.entries(item.after).filter(
                  ([key, value]) => value !== item.before[key],
                );
                return (
                  <details
                    key={item.id}
                    className="processing-item"
                    open={
                      item.status === "needs_review" ||
                      item.status === "failed" ||
                      detail.total === 1
                    }
                  >
                    <summary>
                      <span>
                        <strong>{item.name}</strong>
                        <small>
                          {item.after.artist ||
                            item.before.artist ||
                            "未知歌手"}{" "}
                          ·{" "}
                          {item.after.album || item.before.album || "未知专辑"}
                        </small>
                      </span>
                      <span
                        className={`processing-status status-dot-label is-${item.status}`}
                      >
                        <i className="status-dot" aria-hidden="true" />
                        {jobStatusLabels[item.status] || item.status}
                      </span>
                    </summary>
                    <div className="processing-item-body">
                      {["scrape", "both"].includes(detail.action) &&
                        [
                          "ready",
                          "preview_ready",
                          "needs_review",
                          "failed",
                        ].includes(item.status) &&
                        !isRunningStatus(detail.status) && (
                          <form
                            className="candidate-search"
                            onSubmit={async (e) => {
                              e.preventDefault();
                              setBusy("search");
                              setError("");
                              try {
                                const updated = await api<LibraryJob>(
                                  `/library/jobs/${detail.id}/search`,
                                  "POST",
                                  {
                                    item: item.id,
                                    query: (
                                      queries[item.id] ??
                                      `${item.before.title} ${item.before.artist}`
                                    ).trim(),
                                  },
                                );
                                setDetail(updated);
                                setJobs((prev) =>
                                  prev.map((j) =>
                                    j.id === updated.id ? updated : j,
                                  ),
                                );
                              } catch (e) {
                                setError((e as Error).message);
                                scrollActionStripError();
                              } finally {
                                setBusy("");
                              }
                            }}
                          >
                            <label>
                              搜索歌曲资料
                              <input
                                maxLength={200}
                                required
                                value={
                                  queries[item.id] ??
                                  `${item.before.title} ${item.before.artist}`.trim()
                                }
                                onChange={(e) =>
                                  setQueries({
                                    ...queries,
                                    [item.id]: e.target.value,
                                  })
                                }
                                placeholder="歌名 歌手"
                              />
                            </label>
                            <button className="secondary" disabled={!!busy}>
                              重新搜索候选
                            </button>
                          </form>
                        )}
                      {!!item.candidates.length && (
                        <label className="candidate-select">
                          匹配结果
                          <select
                            value={item.chosen || ""}
                            disabled={
                              !!busy ||
                              isRunningStatus(detail.status) ||
                              ["complete", "restored", "skipped"].includes(
                                item.status,
                              )
                            }
                            onChange={(e) =>
                              void choose(item.id, e.target.value)
                            }
                          >
                            <option value="">请选择歌曲版本</option>
                            <option value="existing">保留现有标签</option>
                            {item.candidates.map((candidate) => (
                              <option value={candidate.id} key={candidate.id}>
                                {formatMetadataSourceLabel(
                                  candidate.source,
                                  metadataSources,
                                )}
                                ：{candidate.title} / {candidate.artist} /{" "}
                                {candidate.album || "未知专辑"}
                                {` · ${candidate.score} 分`}
                                {candidate.date ? ` · ${candidate.date}` : ""}
                                {candidate.conflicts.length
                                  ? `（${candidate.conflicts.join("、")}）`
                                  : ""}
                              </option>
                            ))}
                          </select>
                        </label>
                      )}
                      {!item.candidates.length &&
                        item.status === "needs_review" && (
                          <button
                            className="secondary"
                            disabled={!!busy}
                            onClick={() => void choose(item.id, "existing")}
                          >
                            保留现有标签并继续
                          </button>
                        )}
                      {item.chosen && item.chosen !== "existing" && (
                        <p className="setting-help">
                          匹配{" "}
                          {
                            item.candidates.find((c) => c.id === item.chosen)
                              ?.score
                          }{" "}
                          分 ·{" "}
                          {item.candidates
                            .find((c) => c.id === item.chosen)
                            ?.reasons.join("、")}
                        </p>
                      )}
                      {chosenCandidate?.field_sources && (
                        <p className="setting-help">
                          补充资料来源：
                          {Object.entries(chosenCandidate.field_sources)
                            .map(
                              ([field, source]) =>
                                `${tagLabels[field] || { lyrics: "歌词", cover_url: "封面" }[field] || field}（${formatMetadataSourceLabel(source, metadataSources)}）`,
                            )
                            .join("、")}
                        </p>
                      )}
                      {changes.length ? (
                        <div className="music-table-wrap">
                          <table className="tag-diff">
                            <thead>
                              <tr>
                                <th>标签</th>
                                <th>修改前</th>
                                <th>修改后</th>
                              </tr>
                            </thead>
                            <tbody>
                              {changes.map(([key, value]) => (
                                <tr key={key}>
                                  <th>{tagLabels[key] || key}</th>
                                  <td data-label="修改前">
                                    {item.before[key] || "空"}
                                  </td>
                                  <td data-label="修改后">{value || "清空"}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      ) : (
                        <p className="setting-help">
                          文字标签无变化。歌词与封面将按任务规则补全。
                        </p>
                      )}
                      <div className="processing-paths">
                        <div>
                          <small>原文件</small>
                          <p className="library-path">{item.original_path}</p>
                        </div>
                        <div>
                          <small>整理后</small>
                          <p className="library-path">{item.target}</p>
                        </div>
                      </div>
                      {item.artwork && (
                        <p className="setting-help">
                          艺术家：{item.artwork.artist} · 来源：
                          {item.artwork.source}
                        </p>
                      )}
                      {item.quality && (
                        <div className="library-checks">
                          {Object.entries(item.quality).map(([key, value]) => (
                            <span key={key}>
                              {(
                                {
                                  duration: "时长（秒）",
                                  bitrate: "码率（bps）",
                                  sample_rate: "采样率（Hz）",
                                  channels: "声道",
                                  bits_per_sample: "位深",
                                } as Record<string, string>
                              )[key] || key}
                              ：{value ?? "未知"}
                            </span>
                          ))}
                          <span>完整解码通过</span>
                        </div>
                      )}
                      {item.outputs && (
                        <div>
                          <h4>输出文件（原件保留）</h4>
                          {item.outputs.map((output) => (
                            <p className="library-path" key={output.path}>
                              {output.path}
                              {output.start != null
                                ? ` · ${output.start.toFixed(2)}s – ${output.end?.toFixed(2)}s`
                                : ""}
                            </p>
                          ))}
                        </div>
                      )}
                      {item.locks.length > 0 && (
                        <p className="setting-help">
                          手动锁定：
                          {item.locks
                            .map((key) => tagLabels[key] || key)
                            .join("、")}
                          ，自动刮削会保留这些字段。
                        </p>
                      )}
                      {item.warnings.map((warning, index) => (
                        <p key={index} className="library-warning">
                          {warning}
                        </p>
                      ))}
                      {item.error && (
                        <p role="alert" className="library-error">
                          {item.error}
                        </p>
                      )}
                      <a
                        className="secondary"
                        href={`/api/files/content?path=${encodeURIComponent(item.path)}`}
                        download
                      >
                        下载当前文件
                      </a>
                      <button
                        className="secondary"
                        disabled={!!busy}
                        onClick={async () => {
                          setBusy("versions");
                          setError("");
                          try {
                            const result = await api<{ versions: Version[] }>(
                              `/library/versions?path=${encodeURIComponent(item.path)}`,
                            );
                            setVersions({
                              ...versions,
                              [item.id]: result.versions,
                            });
                          } catch (e) {
                            setError((e as Error).message);
                          } finally {
                            setBusy("");
                          }
                        }}
                      >
                        查看此曲标签版本
                      </button>
                      {versions[item.id] && (
                        <div className="processing-versions">
                          {versions[item.id].length ? (
                            versions[item.id].map((version) => (
                              <details key={version.job_id}>
                                <summary>
                                  {new Date(
                                    toEpochMs(version.created_at),
                                  ).toLocaleString("zh-CN")}{" "}
                                  ·{" "}
                                  {actionNames[version.action] ||
                                    version.action}{" "}
                                  ·{" "}
                                  {processingLabels[version.status] ||
                                    version.status}
                                </summary>
                                {Object.entries(version.after)
                                  .filter(
                                    ([key, value]) =>
                                      version.before[key] !== value,
                                  )
                                  .map(([key, value]) => (
                                    <p key={key}>
                                      {tagLabels[key] || key}：
                                      {version.before[key] || "空"} →{" "}
                                      {value || "空"}
                                    </p>
                                  ))}
                                {version.restorable && (
                                  <button
                                    className="secondary"
                                    disabled={!!busy}
                                    onClick={async () => {
                                      setBusy("restore");
                                      setError("");
                                      try {
                                        await api(
                                          `/library/jobs/${version.job_id}/restore`,
                                          "POST",
                                          { items: [item.id] },
                                        );
                                        setVersions({
                                          ...versions,
                                          [item.id]: [],
                                        });
                                        setSelected(version.job_id);
                                        setDetail(null);
                                        setRefresh((v) => v + 1);
                                      } catch (e) {
                                        setError((e as Error).message);
                                      } finally {
                                        setBusy("");
                                      }
                                    }}
                                  >
                                    恢复此曲到本次修改前
                                  </button>
                                )}
                              </details>
                            ))
                          ) : (
                            <p>暂无可显示的修改版本。</p>
                          )}
                        </div>
                      )}
                    </div>
                  </details>
                );
              })}
            </div>
          )}
        </>
      ) : (
        !loading && (
          <>
            {selectMode && (
              <div
                className="management-toolbar"
                role="region"
                aria-label="批量清理整理记录"
              >
                <span className="management-help">
                  已选 {selectedDeletableIds.length} 条记录（不删磁盘音频）
                </span>
                <button
                  type="button"
                  className="secondary"
                  disabled={!!busy || deletableVisibleJobs.length === 0}
                  onClick={() =>
                    setSelectedIds(
                      allVisibleSelected
                        ? []
                        : deletableVisibleJobs.map((job) => job.id),
                    )
                  }
                >
                  {allVisibleSelected
                    ? "取消全选"
                    : `全选当前 (${deletableVisibleJobs.length})`}
                </button>
                <button
                  type="button"
                  className="primary danger"
                  disabled={!!busy || selectedDeletableIds.length === 0}
                  onClick={() => {
                    setError("");
                    setNotice("");
                    setConfirmDelete({
                      mode: "selected",
                      ids: selectedDeletableIds,
                    });
                  }}
                >
                  <Trash2 />
                  删除选中
                  {selectedDeletableIds.length > 0
                    ? ` (${selectedDeletableIds.length})`
                    : ""}
                </button>
              </div>
            )}
            {visibleJobs.length ? (
              <div className="processing-list">
                {visibleJobs.map((job) => {
                  const running = isRunningStatus(job.status);
                  const checked = selectedDeletableIds.includes(job.id);
                  const title = actionNames[job.action] || job.action;
                  return (
                    <div className="processing-list-row" key={job.id}>
                      {selectMode && (
                        <input
                          type="checkbox"
                          aria-label={`选择整理记录 ${title}`}
                          checked={checked}
                          disabled={running || !!busy}
                          title={
                            running
                              ? "任务运行中，请先取消后再删除"
                              : "选择该整理记录"
                          }
                          onChange={(e) => {
                            if (running) return;
                            setSelectedIds((prev) =>
                              e.target.checked
                                ? prev.includes(job.id)
                                  ? prev
                                  : [...prev, job.id]
                                : prev.filter((id) => id !== job.id),
                            );
                          }}
                        />
                      )}
                      <button
                        type="button"
                        className="processing-row-trigger"
                        onClick={() => {
                          if (selectMode) {
                            if (running) return;
                            setSelectedIds((prev) =>
                              prev.includes(job.id)
                                ? prev.filter((id) => id !== job.id)
                                : [...prev, job.id],
                            );
                            return;
                          }
                          setDetail(null);
                          setSelected(job.id);
                        }}
                      >
                        <History className="processing-row-icon" />
                        <span className="processing-row-copy">
                          <strong>{title}</strong>
                          <small>
                            {job.detail} ·{" "}
                            {new Date(toEpochMs(job.created_at)).toLocaleString(
                              "zh-CN",
                            )}{" "}
                            ·{" "}
                            {running
                              ? `已处理 ${job.processed} / ${job.total} 首`
                              : `${job.total} 首`}
                          </small>
                          {running &&
                            (() => {
                              const rowProgress = getJobProgress(job);
                              return (
                                <div
                                  className={`processing-progress-track processing-row-progress ${rowProgress.indeterminate ? "is-indeterminate" : ""}`}
                                >
                                  <span
                                    className="processing-progress-fill"
                                    style={{
                                      width: `${rowProgress.indeterminate ? 35 : rowProgress.percent}%`,
                                    }}
                                  />
                                </div>
                              );
                            })()}
                        </span>
                        <span
                          className={`processing-status status-dot-label is-${job.status}`}
                        >
                          <i className="status-dot" aria-hidden="true" />
                          {jobStatusLabels[job.status] || job.status}
                        </span>
                      </button>
                      <button
                        type="button"
                        className="secondary processing-row-delete"
                        disabled={running || !!busy}
                        title={
                          running
                            ? "任务运行中，请先取消后再删除"
                            : "仅移除整理记录，不删除磁盘原音频"
                        }
                        aria-label={
                          running
                            ? `${title} 运行中不可删除`
                            : `删除记录 ${title}`
                        }
                        onClick={() => {
                          setError("");
                          setNotice("");
                          setConfirmDelete({ mode: "single", job });
                        }}
                      >
                        <Trash2 />
                        {running ? "运行中" : "删除记录"}
                      </button>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="library-empty">
                <History />
                <h3>
                  {filter !== "all" ? "没有符合条件的任务" : "还没有整理任务"}
                </h3>
                <p>
                  在「本地音乐」中选择歌曲，或点击「发起目录整理」即可开始。
                </p>
              </div>
            )}
            {(offset > 0 || hasMore) && (
              <div className="library-pagination">
                <button
                  type="button"
                  className="secondary"
                  disabled={!offset}
                  onClick={() => {
                    setOffset(Math.max(0, offset - 30));
                    setSelectedIds([]);
                  }}
                >
                  上一页
                </button>
                <span>第 {Math.floor(offset / 30) + 1} 页</span>
                <button
                  type="button"
                  className="secondary"
                  disabled={!hasMore}
                  onClick={() => {
                    setOffset(offset + 30);
                    setSelectedIds([]);
                  }}
                >
                  下一页
                </button>
              </div>
            )}
          </>
        )
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={
            confirmDelete.mode === "single"
              ? "删除整理记录"
              : confirmDelete.mode === "selected"
                ? "批量删除整理记录"
                : "清理已完成整理记录"
          }
          description={
            confirmDelete.mode === "single"
              ? `确定要删除这条「${actionNames[confirmDelete.job.action] || confirmDelete.job.action}」整理记录吗？`
              : confirmDelete.mode === "selected"
                ? `确定要删除已选中的 ${confirmDelete.ids.length} 条整理记录吗？运行中任务会自动跳过。`
                : "确定要清理所有已结束的整理记录（已完成、部分完成、失败、已取消、已回滚）吗？"
          }
          detail="仅移除整理记录与预览历史，不会删除磁盘上的任何音乐文件。"
          confirmLabel={
            confirmDelete.mode === "finished" ? "确认清理" : "确认删除"
          }
          busyLabel="正在移除…"
          busy={busy === "delete"}
          tone="danger"
          onCancel={() => setConfirmDelete(null)}
          onConfirm={() => void executeDelete()}
        />
      )}
    </section>
  );
}
