import assert from "node:assert/strict";
import { spawn, execFileSync } from "node:child_process";
import {
  mkdtemp,
  mkdir,
  writeFile,
  readdir,
  readFile,
  rm,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { chromium, expect } from "@playwright/test";

const repoRoot = path.resolve(import.meta.dirname, "..", "..");
const goDir = path.join(repoRoot, "go");
const staticDir = path.join(repoRoot, "frontend", "dist");
const goBin =
  process.env.MELOKEEP_GO_BIN ||
  "C:\\Users\\Administrator\\AppData\\Local\\Programs\\GoSDK\\go\\bin\\go.exe";
const browserExe =
  process.env.MELOKEEP_BROWSER_EXECUTABLE ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

const tmpRoot = await mkdtemp(path.join(os.tmpdir(), "melokeep-real-e2e-"));
const exeName = process.platform === "win32" ? "melokeep.exe" : "melokeep";
const exePath = path.join(tmpRoot, exeName);
const dataDir = path.join(tmpRoot, "data");
const musicRoot = path.join(tmpRoot, "music");
const subDir = path.join(musicRoot, "sub");
await mkdir(dataDir, { recursive: true });
await mkdir(subDir, { recursive: true });

function minimalWav() {
  return Buffer.from([
    0x52, 0x49, 0x46, 0x46, 52, 0, 0, 0, 0x57, 0x41, 0x56, 0x45, 0x66, 0x6d,
    0x74, 0x20, 16, 0, 0, 0, 1, 0, 1, 0, 0x44, 0xac, 0, 0, 0x88, 0x58, 0x01, 0,
    2, 0, 16, 0, 0x64, 0x61, 0x74, 0x61, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0,
  ]);
}
const wavBytes = minimalWav();
await writeFile(path.join(subDir, "Alpha.wav"), wavBytes);
await writeFile(path.join(musicRoot, "Beta.wav"), wavBytes);

execFileSync(goBin, ["build", "-o", exePath, "./cmd/melokeep"], {
  cwd: goDir,
  stdio: "inherit",
});

const port = 18771;
const base = `http://127.0.0.1:${port}`;

async function startServer(readOnly) {
  const args = [
    "--host",
    "127.0.0.1",
    "--port",
    String(port),
    "--data-dir",
    dataDir,
    "--music-root",
    musicRoot,
    "--static-dir",
    staticDir,
    "--auth-user",
    "tester",
    "--auth-pass",
    "secret123",
  ];
  if (readOnly) args.push("--read-only");
  const proc = spawn(exePath, args, { stdio: ["ignore", "pipe", "pipe"] });
  let stderr = "";
  proc.stderr.on("data", (d) => {
    stderr += d.toString();
  });
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`${base}/api/health`);
      if (r.ok) return proc;
    } catch {}
    await new Promise((r) => setTimeout(r, 150));
  }
  proc.kill();
  throw new Error(`server failed to start (readOnly=${readOnly}): ${stderr}`);
}

async function stopServer(proc) {
  if (!proc || proc.exitCode !== null) return;
  proc.kill("SIGTERM");
  for (let i = 0; i < 20 && proc.exitCode === null; i++) {
    await new Promise((r) => setTimeout(r, 100));
  }
  if (proc.exitCode === null) proc.kill("SIGKILL");
}

const browser = await chromium.launch({
  headless: true,
  executablePath: browserExe,
});

let srv1;
try {
  srv1 = await startServer(false);
  const health = await (await fetch(`${base}/api/health`)).json();
  assert.equal(health.ok, true);
  assert.ok(health.sources >= 7);
  assert.equal(health.engine, "melokeep-native");

  const unauthCfg = await fetch(`${base}/api/config`);
  assert.equal(unauthCfg.status, 401);

  const page = await browser.newPage({
    viewport: { width: 1440, height: 960 },
  });
  const pageErrors = [];
  const server5xx = [];
  page.on("pageerror", (e) => pageErrors.push(e.message));
  page.on("response", (res) => {
    if (res.status() >= 500) server5xx.push(`${res.status()} ${res.url()}`);
  });

  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByLabel("用户名", { exact: true }).fill("tester");
  await page.getByLabel("密码", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page
    .getByRole("button", { name: "音乐库", exact: true })
    .waitFor({ timeout: 10000 });

  await page.getByRole("button", { name: "音乐库", exact: true }).click();
  await page.getByRole("button", { name: "刷新音乐库", exact: true }).click();
  await page.getByText("Alpha").first().waitFor({ timeout: 10000 });
  await page.getByText("Beta").first().waitFor({ timeout: 10000 });

  const cookies = await page.context().cookies();
  const sessCookie = cookies.find((c) => c.name === "melokeep_session");
  assert.ok(
    sessCookie && sessCookie.httpOnly,
    "expected HttpOnly melokeep_session cookie",
  );

  const filesResp = await page.evaluate(async () => {
    const r = await fetch("/api/library/files", { credentials: "same-origin" });
    return { status: r.status, body: await r.json() };
  });
  assert.equal(filesResp.status, 200);
  assert.equal(filesResp.body.items.length, 2);
  const alphaItem = filesResp.body.items.find((x) =>
    x.name.includes("Alpha.wav"),
  );
  assert.ok(alphaItem, "Alpha.wav indexed");

  const rangeCheck = await page.evaluate(
    async (p) => {
      const r = await fetch(p, {
        credentials: "same-origin",
        headers: { Range: "bytes=0-15" },
      });
      const buf = new Uint8Array(await r.arrayBuffer());
      return {
        status: r.status,
        ct: r.headers.get("content-type"),
        cr: r.headers.get("content-range"),
        len: buf.length,
        magic: String.fromCharCode(...buf.slice(0, 4)),
      };
    },
    alphaItem.track?.playback?.url ||
      `/api/files/audio?path=${encodeURIComponent(alphaItem.path)}`,
  );
  assert.equal(rangeCheck.status, 206);
  assert.equal(rangeCheck.ct, "audio/wav");
  assert.equal(rangeCheck.len, 16);
  assert.equal(rangeCheck.magic, "RIFF");

  const plCheck = await page.evaluate(async () => {
    const cr = await fetch("/api/playlists", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-MeloKeep-Request": "1",
      },
      body: JSON.stringify({ name: "Real E2E Playlist", track_ids: [] }),
    });
    const created = await cr.json();
    const list = await (
      await fetch("/api/playlists", { credentials: "same-origin" })
    ).json();
    return { crStatus: cr.status, createdId: created.id, count: list.length };
  });
  assert.equal(plCheck.crStatus, 200);
  assert.ok(plCheck.createdId && plCheck.count >= 1);

  const agentCfg = await page.evaluate(async () => {
    const r = await fetch("/api/agent/settings", {
      credentials: "same-origin",
    });
    return { status: r.status };
  });
  assert.equal(agentCfg.status, 200);

  await page.getByRole("button", { name: "切换主题", exact: true }).click();
  await page.getByRole("button", { name: /流金黑胶/ }).click();
  await page.keyboard.press("Escape");
  await expect
    .poll(() =>
      page.evaluate(async () => {
        const res = await fetch("/api/preferences", {
          credentials: "same-origin",
        });
        const data = await res.json();
        return data.appearance?.dark_preset;
      }),
    )
    .toBe("vinyl-gold");

  const ctx2 = await browser.newContext({
    viewport: { width: 1440, height: 960 },
  });
  try {
    const page2 = await ctx2.newPage();
    await page2.goto(base, { waitUntil: "networkidle" });
    await page2.getByLabel("用户名", { exact: true }).fill("tester");
    await page2.getByLabel("密码", { exact: true }).fill("secret123");
    await page2.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page2.locator("html")).toHaveAttribute(
      "data-preset",
      "vinyl-gold",
    );
    await page2.close();
  } finally {
    await ctx2.close();
  }

  assert.deepEqual(pageErrors, [], `unexpected pageerrors: ${pageErrors}`);
  assert.deepEqual(server5xx, [], `unexpected 5xx: ${server5xx}`);
  await page.close();
} finally {
  await stopServer(srv1);
}

const beforeRoEntries = await readdir(musicRoot);
let srv2;
try {
  srv2 = await startServer(true);
  const page = await browser.newPage({
    viewport: { width: 1440, height: 960 },
  });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(e.message));

  await page.goto(base, { waitUntil: "networkidle" });
  await page.getByLabel("用户名", { exact: true }).fill("tester");
  await page.getByLabel("密码", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page
    .getByRole("button", { name: "音乐库", exact: true })
    .waitFor({ timeout: 10000 });
  await page.getByRole("button", { name: "音乐库", exact: true }).click();
  await page.getByText("Alpha").first().waitFor({ timeout: 10000 });

  const roBlock = await page.evaluate(async () => {
    const r1 = await fetch("/api/downloads", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-MeloKeep-Request": "1",
      },
      body: JSON.stringify([{ track_id: "x" }]),
    });
    const r2 = await fetch("/api/config", {
      method: "PUT",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-MeloKeep-Request": "1",
      },
      body: JSON.stringify({}),
    });
    const r3 = await fetch("/api/preferences", {
      method: "PUT",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-MeloKeep-Request": "1",
      },
      body: JSON.stringify({ appearance: { mode: "dark" } }),
    });
    return { dl: r1.status, cfg: r2.status, prefs: r3.status };
  });
  assert.equal(roBlock.dl, 403);
  assert.equal(roBlock.cfg, 403);
  assert.equal(roBlock.prefs, 403);

  const rootEntries = await readdir(musicRoot);
  assert.deepEqual(rootEntries.sort(), beforeRoEntries.sort());
  assert.equal(
    rootEntries.some((n) => n.startsWith(".melokeep-probe-")),
    false,
  );
  const subEntries = await readdir(subDir);
  assert.deepEqual(subEntries, ["Alpha.wav"]);
  assert.deepEqual(await readFile(path.join(subDir, "Alpha.wav")), wavBytes);
  assert.deepEqual(pageErrors, []);
  await page.close();
} finally {
  await stopServer(srv2);
  await browser.close();
  await rm(tmpRoot, { recursive: true, force: true }).catch(() => {});
}

console.log(
  "REAL SERVER + REAL EDGE E2E PASSED (auth, local index, 206 Range audio, playlists, AI settings, read-only guard, zero /api/* mocks).",
);
