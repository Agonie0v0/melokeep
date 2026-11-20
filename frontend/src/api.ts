import type { SelfUpdateStatus } from "./types";

export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

function formatApiDetail(detail: unknown): string {
  if (typeof detail === "string") return detail;
  if (Array.isArray(detail)) {
    const parts = detail
      .map((item) => {
        if (typeof item === "string") return item;
        if (item && typeof item === "object") {
          const obj = item as Record<string, unknown>;
          return String(obj.msg || obj.message || obj.detail || "");
        }
        return "";
      })
      .filter(Boolean);
    if (parts.length) return parts.join("；");
  }
  if (detail && typeof detail === "object") {
    const obj = detail as Record<string, unknown>;
    if (typeof obj.detail === "string") return obj.detail;
    if (typeof obj.message === "string") return obj.message;
    try {
      return JSON.stringify(detail);
    } catch {
      return "";
    }
  }
  return "";
}

export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const isFormData =
    typeof FormData !== "undefined" && body instanceof FormData;
  const headers: Record<string, string> = { "X-MeloKeep-Request": "1" };
  if (!isFormData) {
    headers["Content-Type"] = "application/json";
  }
  const response = await fetch(`/api${path}`, {
    method,
    credentials: "same-origin",
    headers,
    body:
      body === undefined ? undefined : isFormData ? body : JSON.stringify(body),
    signal,
  });
  const data = await response
    .json()
    .catch(() => ({ detail: "服务未返回有效数据，请检查服务状态" }));
  if (!response.ok) {
    if (response.status === 401 && typeof window !== "undefined")
      window.dispatchEvent(new Event("melokeep-login"));
    throw new ApiError(
      formatApiDetail(data.detail) || `请求失败 (${response.status})`,
      response.status,
    );
  }
  return data as T;
}

export interface NavidromeArchiveRequest {
  song_ids?: string[];
  all?: boolean;
  query?: string;
  album_id?: string;
}

export function navidromeSingleDownloadUrl(ident: string): string {
  return `/api/navidrome/download/${encodeURIComponent(ident)}`;
}

export function parseContentDispositionFilename(
  header: string | null | undefined,
  fallback = "navidrome-tracks.zip",
): string {
  if (!header) return fallback;
  const utf8Match = header.match(
    /filename\*\s*=\s*(?:UTF-8|utf-8)?''([^;\r\n]+)/i,
  );
  if (utf8Match?.[1]) {
    try {
      const decoded = decodeURIComponent(utf8Match[1].trim())
        .replace(/[/\\?%*:|"<>]/g, "_")
        .trim();
      if (decoded) return decoded;
    } catch {
      /* Ignore malformed percent-encoding and try plain filename=. */
    }
  }
  const plainMatch =
    header.match(/filename\s*=\s*"([^"\r\n]+)"/i) ||
    header.match(/filename\s*=\s*([^;\r\n]+)/i);
  if (plainMatch?.[1]) {
    const cleaned = plainMatch[1]
      .trim()
      .replace(/^["']|["']$/g, "")
      .replace(/[/\\?%*:|"<>]/g, "_")
      .trim();
    if (cleaned) return cleaned;
  }
  return fallback;
}

export function triggerBlobDownload(blob: Blob, filename: string) {
  if (typeof document === "undefined" || typeof URL === "undefined") return;
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.style.display = "none";
  document.body.appendChild(link);
  link.click();
  link.remove();
  if (typeof window !== "undefined") {
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  } else {
    URL.revokeObjectURL(url);
  }
}

export function triggerUrlDownload(url: string) {
  if (typeof document === "undefined") return;
  const link = document.createElement("a");
  link.href = url;
  link.download = "";
  link.style.display = "none";
  document.body.appendChild(link);
  link.click();
  link.remove();
}

export async function downloadNavidromeArchive(
  req: NavidromeArchiveRequest,
  fallbackFilename = "navidrome-tracks.zip",
): Promise<string> {
  const response = await fetch("/api/navidrome/downloads/archive", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "X-MeloKeep-Request": "1",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(req),
  });
  if (!response.ok) {
    if (response.status === 401 && typeof window !== "undefined") {
      window.dispatchEvent(new Event("melokeep-login"));
    }
    const data = await response
      .json()
      .catch(() => ({ detail: "打包下载失败，请检查服务状态" }));
    throw new ApiError(
      formatApiDetail((data as Record<string, unknown>)?.detail) ||
        `下载失败 (${response.status})`,
      response.status,
    );
  }
  const blob = await response.blob();
  const filename = parseContentDispositionFilename(
    response.headers.get("Content-Disposition"),
    fallbackFilename,
  );
  triggerBlobDownload(blob, filename);
  return filename;
}

export function applySystemUpdate(): Promise<SelfUpdateStatus> {
  return api<SelfUpdateStatus>("/system/update/apply", "POST");
}

export function fetchSystemUpdateStatus(): Promise<SelfUpdateStatus> {
  return api<SelfUpdateStatus>("/system/update/status");
}

export async function checkSystemHealth(): Promise<boolean> {
  try {
    const response = await fetch("/api/health", {
      method: "GET",
      cache: "no-store",
      credentials: "same-origin",
      headers: { "X-MeloKeep-Request": "1" },
    });
    if (!response.ok) return false;
    const data = (await response.json().catch(() => null)) as {
      ok?: boolean;
    } | null;
    return data?.ok === true;
  } catch {
    return false;
  }
}
