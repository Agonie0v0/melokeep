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
