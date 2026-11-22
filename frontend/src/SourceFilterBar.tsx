import { useMemo, type ReactNode } from "react";
import { Search, X } from "lucide-react";
import {
  SOURCE_FILTER_ALL,
  SOURCE_FILTER_SELECTED,
  getAvailableSourceCategoryBuckets,
  selectOnlySourceKeys,
  sourceEngineKey,
  toggleSourceKeysSelection,
} from "./plugin-utils";
import type { AvailableSource } from "./types";

export interface SourceFilterBarProps {
  sources: AvailableSource[];
  selectedKeys: string[];
  query: string;
  onQueryChange: (v: string) => void;
  category: string;
  onCategoryChange: (v: string) => void;
  filteredSources: AvailableSource[];
  onSelectKeys: (next: string[]) => void;
  selectedLabel?: string;
  clearLabel?: string;
  extraActions?: ReactNode;
  disabled?: boolean;
}

export function SourceFilterBar({
  sources,
  selectedKeys,
  query,
  onQueryChange,
  category,
  onCategoryChange,
  filteredSources,
  onSelectKeys,
  selectedLabel = "已启用",
  clearLabel = "取消全选",
  extraActions,
  disabled = false,
}: SourceFilterBarProps) {
  const allKeys = useMemo(
    () => selectOnlySourceKeys(sources.map((s) => sourceEngineKey(s))),
    [sources],
  );
  const filteredKeys = useMemo(
    () => selectOnlySourceKeys(filteredSources.map((s) => sourceEngineKey(s))),
    [filteredSources],
  );
  const selectedSet = useMemo(() => new Set(selectedKeys), [selectedKeys]);
  const buckets = useMemo(
    () => getAvailableSourceCategoryBuckets(sources, selectedKeys),
    [sources, selectedKeys],
  );

  const hasQuery = query.trim().length > 0;
  const isSpecificCategory =
    category !== SOURCE_FILTER_ALL && category !== SOURCE_FILTER_SELECTED;
  const hasFilter = hasQuery || category !== SOURCE_FILTER_ALL;
  const showScopedActions =
    (isSpecificCategory || hasQuery) && filteredKeys.length > 0;
  const allFilteredSelected =
    filteredKeys.length > 0 &&
    filteredKeys.every((key) => selectedSet.has(key));

  return (
    <div className="source-filter-toolbar">
      <div className="source-filter-toolbar-top">
        <div className="plugin-search-field">
          <Search aria-hidden="true" />
          <input
            type="search"
            aria-label="搜索音源"
            placeholder="搜索音源名称、ID 或分类…"
            value={query}
            disabled={disabled}
            onChange={(e) => onQueryChange(e.target.value)}
          />
          {query && (
            <button
              type="button"
              className="plugin-search-clear"
              aria-label="清空音源搜索"
              disabled={disabled}
              onClick={() => onQueryChange("")}
            >
              <X aria-hidden="true" />
            </button>
          )}
        </div>
        <div className="source-picker-actions">
          {extraActions}
          <button
            type="button"
            className="secondary"
            disabled={disabled || !allKeys.length}
            onClick={() => onSelectKeys(allKeys)}
          >
            全选
          </button>
          <button
            type="button"
            className="secondary"
            disabled={disabled}
            onClick={() => onSelectKeys([])}
          >
            {clearLabel}
          </button>
          {showScopedActions && (
            <>
              <button
                type="button"
                className="secondary"
                disabled={disabled}
                onClick={() =>
                  onSelectKeys(
                    toggleSourceKeysSelection(selectedKeys, filteredKeys),
                  )
                }
              >
                {hasQuery
                  ? allFilteredSelected
                    ? "取消当前筛选"
                    : "勾选当前筛选"
                  : allFilteredSelected
                    ? "取消当前分类"
                    : "勾选当前分类"}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={disabled}
                onClick={() => onSelectKeys(selectOnlySourceKeys(filteredKeys))}
              >
                {hasQuery ? "仅选当前筛选" : "仅选当前分类"}
              </button>
            </>
          )}
          <span className="source-filter-count">
            已选 {selectedKeys.length} / {sources.length}
            {hasFilter ? ` · 显示 ${filteredSources.length} 个` : ""}
          </span>
        </div>
      </div>

      <div
        className="plugin-installed-categories"
        role="group"
        aria-label="按分类筛选音源"
      >
        <button
          type="button"
          className={
            category === SOURCE_FILTER_ALL
              ? "plugin-archive-chip is-active"
              : "plugin-archive-chip"
          }
          aria-pressed={category === SOURCE_FILTER_ALL}
          disabled={disabled}
          onClick={() => onCategoryChange(SOURCE_FILTER_ALL)}
        >
          全部 ({sources.length})
        </button>
        <button
          type="button"
          className={
            category === SOURCE_FILTER_SELECTED
              ? "plugin-archive-chip is-active"
              : "plugin-archive-chip"
          }
          aria-pressed={category === SOURCE_FILTER_SELECTED}
          disabled={disabled}
          onClick={() => onCategoryChange(SOURCE_FILTER_SELECTED)}
        >
          {selectedLabel} ({selectedKeys.length})
        </button>
        {buckets.map((bucket) => {
          const active = category === bucket.category;
          return (
            <button
              type="button"
              key={bucket.category}
              className={
                active ? "plugin-archive-chip is-active" : "plugin-archive-chip"
              }
              aria-pressed={active}
              title={`已选 ${bucket.selectedCount} / ${bucket.count} 个`}
              disabled={disabled}
              onClick={() => onCategoryChange(bucket.category)}
            >
              {bucket.category} (
              {bucket.selectedCount > 0
                ? `${bucket.selectedCount}/${bucket.count}`
                : bucket.count}
              )
            </button>
          );
        })}
      </div>
    </div>
  );
}
