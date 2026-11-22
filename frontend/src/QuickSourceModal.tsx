import { useCallback, useEffect, useMemo, useState } from "react";
import { Search } from "lucide-react";
import { api } from "./api";
import { Modal } from "./components";
import { SourceFilterBar } from "./SourceFilterBar";
import {
  SOURCE_FILTER_ALL,
  SOURCE_FILTER_SELECTED,
  availableSourceCategory,
  filterAvailableSources,
  isPluginSource,
  selectOnlySourceKeys,
  sourceEngineKey,
} from "./plugin-utils";
import type { AvailableSource } from "./types";

const GLOBAL_CLIENT_KEYS = ["work_dir", "search_size_per_source"];

type RawEngineConfig = Record<string, unknown>;

export interface QuickSourceModalProps {
  sources: AvailableSource[];
  fallbackSelectedKeys?: string[];
  onClose: () => void;
  onSaved: (selectedCount: number) => Promise<void> | void;
  onOpenFullSettings: () => void;
}

export function QuickSourceModal({
  sources,
  fallbackSelectedKeys = [],
  onClose,
  onSaved,
  onOpenFullSettings,
}: QuickSourceModalProps) {
  const [rawConfig, setRawConfig] = useState<RawEngineConfig | null>(null);
  const [draftSources, setDraftSources] = useState<string[]>(() =>
    selectOnlySourceKeys(fallbackSelectedKeys),
  );
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState(SOURCE_FILTER_ALL);

  const loadEngineConfig = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const raw = await api<RawEngineConfig>("/engine/config");
      const nextSources = Array.isArray(raw?.music_sources)
        ? selectOnlySourceKeys(
            raw.music_sources.filter(
              (item): item is string => typeof item === "string",
            ),
          )
        : selectOnlySourceKeys(fallbackSelectedKeys);
      setRawConfig(raw && typeof raw === "object" ? raw : {});
      setDraftSources(nextSources);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [fallbackSelectedKeys]);

  useEffect(() => {
    void loadEngineConfig();
  }, [loadEngineConfig]);

  const availableCategories = useMemo(
    () => new Set(sources.map((source) => availableSourceCategory(source))),
    [sources],
  );
  const effectiveCategory =
    category === SOURCE_FILTER_ALL ||
    category === SOURCE_FILTER_SELECTED ||
    availableCategories.has(category)
      ? category
      : SOURCE_FILTER_ALL;

  useEffect(() => {
    if (effectiveCategory !== category) {
      setCategory(SOURCE_FILTER_ALL);
    }
  }, [effectiveCategory, category]);

  const filteredSources = useMemo(
    () =>
      filterAvailableSources(sources, {
        query,
        category: effectiveCategory,
        selectedKeys: draftSources,
      }),
    [sources, query, effectiveCategory, draftSources],
  );

  const selectedSet = useMemo(() => new Set(draftSources), [draftSources]);

  async function handleSave() {
    if (!rawConfig || !draftSources.length || saving) return;
    setSaving(true);
    setError("");
    try {
      const rawGlobal =
        rawConfig.global_client_cfg &&
        typeof rawConfig.global_client_cfg === "object" &&
        !Array.isArray(rawConfig.global_client_cfg)
          ? (rawConfig.global_client_cfg as Record<string, unknown>)
          : {};
      // Drop read-only `sources` snapshot so backend honours updated `music_sources`.
      const restConfig: RawEngineConfig = { ...rawConfig };
      delete restConfig.sources;
      const nextKeys = selectOnlySourceKeys(draftSources);
      const payload = {
        ...restConfig,
        music_sources: nextKeys,
        global_client_cfg: Object.fromEntries(
          Object.entries(rawGlobal).filter(([key]) =>
            GLOBAL_CLIENT_KEYS.includes(key),
          ),
        ),
      };
      await api<{ source_errors?: Record<string, string> }>(
        "/engine/config",
        "PUT",
        payload,
      );
      await onSaved(nextKeys.length);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title="快速切换搜索音源"
      className="quick-source-modal"
      close={saving ? undefined : onClose}
    >
      {loading ? (
        <p className="quick-source-status" role="status">
          正在读取音源配置…
        </p>
      ) : !rawConfig ? (
        <div className="source-filter-empty" role="alert">
          <div>
            <strong>读取音源配置失败</strong>
            <p>{error || "请检查服务连接后重试。"}</p>
          </div>
          <button
            type="button"
            className="secondary"
            onClick={() => void loadEngineConfig()}
          >
            重新读取
          </button>
        </div>
      ) : (
        <>
          <SourceFilterBar
            sources={sources}
            selectedKeys={draftSources}
            query={query}
            onQueryChange={setQuery}
            category={effectiveCategory}
            onCategoryChange={setCategory}
            filteredSources={filteredSources}
            onSelectKeys={setDraftSources}
            selectedLabel="已启用"
            disabled={saving}
          />
          {filteredSources.length ? (
            <div className="quick-source-grid">
              {filteredSources.map((source) => {
                const key = sourceEngineKey(source);
                const checked = selectedSet.has(key);
                const pluginOrigin = isPluginSource(source);
                const categoryLabel = availableSourceCategory(source);
                return (
                  <label
                    key={source.id}
                    className={`quick-source-item${checked ? " is-selected" : ""}${source.init_error ? " has-error" : ""}`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      disabled={saving}
                      onChange={(e) =>
                        setDraftSources((prev) =>
                          e.target.checked
                            ? selectOnlySourceKeys([...prev, key])
                            : prev.filter((item) => item !== key),
                        )
                      }
                    />
                    <span className="quick-source-item-body">
                      <span className="quick-source-item-head">
                        <strong>{source.label}</strong>
                        <span
                          className={`origin-badge ${pluginOrigin ? "is-plugin" : "is-builtin"}`}
                        >
                          {pluginOrigin ? "自定义插件" : "内置开放源"}
                        </span>
                        <span className="origin-badge">{categoryLabel}</span>
                      </span>
                      {source.init_error && (
                        <small
                          className="quick-source-item-error"
                          role="status"
                        >
                          暂不可用：{source.init_error}
                        </small>
                      )}
                    </span>
                  </label>
                );
              })}
            </div>
          ) : (
            <div className="source-filter-empty" role="status">
              <Search aria-hidden="true" />
              <div>
                <strong>没有符合筛选条件的音源</strong>
                <p>可以清空搜索词或切换分类，查看全部可用音源。</p>
              </div>
              <button
                type="button"
                className="secondary"
                onClick={() => {
                  setQuery("");
                  setCategory(SOURCE_FILTER_ALL);
                }}
              >
                重置筛选
              </button>
            </div>
          )}
          {!draftSources.length && (
            <p className="quick-source-hint" role="status">
              请至少选择一个搜索音源后再保存。
            </p>
          )}
          {error && (
            <p className="quick-source-error" role="alert">
              {error}
            </p>
          )}
        </>
      )}

      <div className="quick-source-footer">
        <button
          type="button"
          className="text-button quick-source-full-link"
          disabled={saving}
          onClick={onOpenFullSettings}
        >
          完整设置（账号 / 代理 / 插件）
        </button>
        <div className="quick-source-footer-actions">
          <button
            type="button"
            className="secondary"
            disabled={saving}
            onClick={onClose}
          >
            取消
          </button>
          <button
            type="button"
            className="primary"
            disabled={saving || loading || !rawConfig || !draftSources.length}
            onClick={() => void handleSave()}
          >
            {saving ? "正在保存…" : "保存生效"}
          </button>
        </div>
      </div>
    </Modal>
  );
}
