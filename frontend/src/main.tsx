import { Component, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./style.css";
import "./styles/themes.css";
import "./styles/motion.css";
import "./styles/redesign.css";

class AppErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  render() {
    if (this.state.error) {
      return (
        <main style={{ maxWidth: 560, margin: "64px auto", padding: 24 }}>
          <h2>页面渲染出现异常</h2>
          <p style={{ color: "var(--muted)", overflowWrap: "anywhere" }}>
            {this.state.error.message || String(this.state.error)}
          </p>
          <div style={{ display: "flex", gap: 12, marginTop: 16 }}>
            <button
              className="primary"
              onClick={() => this.setState({ error: null })}
            >
              尝试恢复页面
            </button>
            <button
              className="secondary"
              onClick={() => window.location.reload()}
            >
              刷新浏览器
            </button>
          </div>
        </main>
      );
    }
    return this.props.children;
  }
}
// Move existing preferences once so clearing a new preference cannot revive it.
try {
  if (localStorage.getItem("melokeep-naming-version") !== "1") {
    for (const prefix of ["melodock-"]) {
      for (const key of Object.keys(localStorage)) {
        if (!key.startsWith(prefix)) continue;
        const next = "melokeep-" + key.slice(prefix.length);
        if (localStorage.getItem(next) === null)
          localStorage.setItem(next, localStorage.getItem(key)!);
        localStorage.removeItem(key);
      }
    }
    localStorage.setItem("melokeep-naming-version", "1");
  }
} catch {
  // Playback remains available when browser storage is blocked.
}
createRoot(document.getElementById("root")!).render(
  <AppErrorBoundary>
    <App />
  </AppErrorBoundary>,
);
