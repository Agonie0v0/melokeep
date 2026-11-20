import test from "node:test";
import assert from "node:assert/strict";
import {
  ApiError,
  downloadNavidromeArchive,
  navidromeSingleDownloadUrl,
  parseContentDispositionFilename,
} from "../src/api.ts";

test("navidromeSingleDownloadUrl encodes track identifiers safely", () => {
  assert.equal(
    navidromeSingleDownloadUrl("song/123?a=1"),
    "/api/navidrome/download/song%2F123%3Fa%3D1",
  );
});

test("parseContentDispositionFilename prefers UTF-8 filename* and falls back safely", () => {
  assert.equal(
    parseContentDispositionFilename(
      `attachment; filename="fallback.zip"; filename*=UTF-8''%E5%91%A8%E6%9D%B0%E4%BC%A6-%E8%8C%83%E7%89%B9%E8%A5%BF.zip`,
    ),
    "周杰伦-范特西.zip",
  );
  assert.equal(
    parseContentDispositionFilename(
      `attachment; filename="navidrome-tracks.zip"`,
    ),
    "navidrome-tracks.zip",
  );
  assert.equal(
    parseContentDispositionFilename(`attachment; filename="../bad/name.zip"`),
    ".._bad_name.zip",
  );
  assert.equal(
    parseContentDispositionFilename(null, "custom.zip"),
    "custom.zip",
  );
});

test("downloadNavidromeArchive posts archive request, extracts filename, and handles errors", async () => {
  const prevFetch = globalThis.fetch;
  const prevDoc = globalThis.document;
  const prevWin = globalThis.window;
  const prevCreate = URL.createObjectURL;
  const prevRevoke = URL.revokeObjectURL;

  const calls = [];
  const createdLinks = [];
  const revokedUrls = [];

  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    const parsed = JSON.parse(init.body);
    if (parsed.all && !parsed.query) {
      return new Response(
        JSON.stringify({
          detail: "单次打包下载最多支持 500 首，请分批或按专辑下载",
        }),
        { status: 400, headers: { "Content-Type": "application/json" } },
      );
    }
    return new Response(new Uint8Array([80, 75, 3, 4]), {
      status: 200,
      headers: {
        "Content-Type": "application/zip",
        "Content-Disposition": `attachment; filename="navidrome-tracks.zip"; filename*=UTF-8''%E4%B8%93%E8%BE%91%E6%89%93%E5%8C%85.zip`,
      },
    });
  };
  globalThis.document = {
    body: {
      appendChild: (node) => createdLinks.push(node),
    },
    createElement: () => ({
      href: "",
      download: "",
      style: {},
      clicked: false,
      removed: false,
      click() {
        this.clicked = true;
      },
      remove() {
        this.removed = true;
      },
    }),
  };
  globalThis.window = {
    setTimeout: (fn) => {
      fn();
      return 1;
    },
    dispatchEvent: () => true,
  };
  URL.createObjectURL = () => "blob:mock-archive-1";
  URL.revokeObjectURL = (u) => revokedUrls.push(u);

  try {
    const filename = await downloadNavidromeArchive({
      song_ids: ["s1", "s2"],
    });
    assert.equal(filename, "专辑打包.zip");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/navidrome/downloads/archive");
    assert.equal(calls[0].init.method, "POST");
    assert.equal(calls[0].init.credentials, "same-origin");
    assert.equal(calls[0].init.headers["X-MeloKeep-Request"], "1");
    assert.equal(calls[0].init.headers["Content-Type"], "application/json");
    assert.deepEqual(JSON.parse(calls[0].init.body), {
      song_ids: ["s1", "s2"],
    });
    assert.equal(createdLinks.length, 1);
    assert.equal(createdLinks[0].download, "专辑打包.zip");
    assert.equal(createdLinks[0].clicked, true);
    assert.equal(createdLinks[0].removed, true);
    assert.deepEqual(revokedUrls, ["blob:mock-archive-1"]);

    await assert.rejects(
      () => downloadNavidromeArchive({ all: true, query: "" }),
      (err) =>
        err instanceof ApiError &&
        err.status === 400 &&
        err.message.includes("500 首"),
    );
  } finally {
    globalThis.fetch = prevFetch;
    globalThis.document = prevDoc;
    globalThis.window = prevWin;
    URL.createObjectURL = prevCreate;
    URL.revokeObjectURL = prevRevoke;
  }
});
