import { useState } from "react";
import { api } from "./api";
import type { PlaylistSummary, Track } from "./types";

type Song = {
  title: string;
  artist: string;
  candidates: Track[];
  selected: string;
  status: string;
};
type Candidate = Track & { downloaded?: boolean };
type ImportPreferences = {
  download: boolean;
  organize: boolean;
  studio: boolean;
};
function readPreferences(): ImportPreferences {
  try {
    return {
      download: true,
      organize: false,
      studio: true,
      ...JSON.parse(
        localStorage.getItem("melokeep-ai-import-preferences") || "{}",
      ),
    };
  } catch {
    return { download: true, organize: false, studio: true };
  }
}
export function AIImport({
  sessionId,
  initial,
  playlists,
  play,
  changed,
}: {
  sessionId: string;
  initial: { title: string; artist: string }[];
  playlists: PlaylistSummary[];
  play: (track: Track) => void;
  changed: () => Promise<void>;
}) {
  const [songs, setSongs] = useState<Song[]>(() =>
    initial.map((song) => ({
      ...song,
      candidates: [],
      selected: "",
      status: "待匹配",
    })),
  );
  const [playlist, setPlaylist] = useState("");
  const [preferences, setPreferences] = useState(readPreferences);
  const { download, organize, studio } = preferences;
  function prefer(patch: Partial<ImportPreferences>) {
    setPreferences((current) => {
      const next = { ...current, ...patch };
      try {
        localStorage.setItem(
          "melokeep-ai-import-preferences",
          JSON.stringify(next),
        );
      } catch {
        /* Optional preference. */
      }
      return next;
    });
  }
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const update = (index: number, patch: Partial<Song>) =>
    setSongs((current) =>
      current.map((song, i) => (i === index ? { ...song, ...patch } : song)),
    );
  async function match(index: number, song: Song) {
    try {
      update(index, { status: "正在搜索…", candidates: [], selected: "" });
      const result = await api<{ tracks: Candidate[]; partial?: boolean }>(
        `/agent/sessions/${sessionId}/import/search`,
        "POST",
        { title: song.title, artist: song.artist },
      );
      const clean = (value: string) =>
        value.toLocaleLowerCase().replace(/[\s\p{P}\p{S}]/gu, "");
      const exact = result.tracks.filter(
        (track) =>
          clean(track.title || "") === clean(song.title) &&
          !!song.artist.trim() &&
          clean(track.artist || "") === clean(song.artist) &&
          (!studio ||
            !/现场|演唱会|live|伴奏|remix|混音/i.test(
              `${track.title} ${track.album}`,
            )),
      );
      const selected = !result.partial && exact.length === 1 ? exact[0].id : "";
      update(index, {
        candidates: result.tracks,
        selected,
        status: result.tracks.length
          ? result.partial
            ? "部分音源不可用，请选择版本"
            : selected
              ? "已找到唯一匹配项，请核对版本"
              : "请选择版本"
          : "没有匹配结果，可修改后重试",
      });
    } catch (e) {
      update(index, { status: (e as Error).message });
    }
  }
  async function matchAll() {
    setBusy(true);
    setNotice("");
    const snapshot = [...songs];
    for (let i = 0; i < snapshot.length; i++)
      if (snapshot[i].title.trim()) await match(i, snapshot[i]);
    setBusy(false);
  }
  async function execute() {
    const ids = [
      ...new Set(songs.map((song) => song.selected).filter(Boolean)),
    ];
    if (!ids.length || (!playlist && !download)) return;
    setBusy(true);
    setNotice("");
    try {
      const result = await api<{
        downloads?: {
          title?: string;
          track?: { title?: string };
          status: string;
          detail?: string;
        }[];
      }>(`/agent/sessions/${sessionId}/import/execute`, "POST", {
        track_ids: ids,
        playlist_id: playlist,
        download,
        organize,
      });
      setSongs((current) =>
        current.map((song) =>
          song.selected
            ? { ...song, selected: "", status: "已提交；可重新选择版本" }
            : song,
        ),
      );
      setNotice(
        `已处理 ${ids.length} 首。${result.downloads?.map((item) => `${item.track?.title || item.title || "曲目"}：${item.detail || item.status}`).join("；") || "已加入歌单。"}`,
      );
      await changed();
    } catch (e) {
      setNotice((e as Error).message + "。请查看执行记录后再继续。");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="ai-import" aria-label="歌单导入清单">
      <h3>检查导入清单</h3>
      <p>修改识别结果并选择匹配版本；只有选定的歌曲会被处理。</p>
      {songs.map((song, index) => (
        <div className="ai-import-row" key={index}>
          <div className="ai-import-inputs">
            <label>
              歌名
              <input
                value={song.title}
                disabled={busy}
                onChange={(e) =>
                  update(index, {
                    title: e.target.value,
                    candidates: [],
                    selected: "",
                    status: "待匹配",
                  })
                }
              />
            </label>
            <label>
              歌手
              <input
                value={song.artist}
                disabled={busy}
                onChange={(e) =>
                  update(index, {
                    artist: e.target.value,
                    candidates: [],
                    selected: "",
                    status: "待匹配",
                  })
                }
              />
            </label>
            <button
              type="button"
              className="text-button"
              disabled={busy}
              onClick={() =>
                setSongs((current) => current.filter((_, i) => i !== index))
              }
            >
              移除
            </button>
          </div>
          <div className="ai-import-inputs">
            <label>
              匹配版本
              <select
                aria-label={`${song.title}的匹配版本`}
                value={song.selected}
                disabled={busy || !song.candidates.length}
                onChange={(e) => update(index, { selected: e.target.value })}
              >
                <option value="">{song.status}</option>
                {song.candidates.map((track) => (
                  <option key={track.id} value={track.id}>
                    {track.title} · {track.artist} · {track.album || "未知专辑"}{" "}
                    · {track.source} ·{" "}
                    {track.qualities?.map((q) => q.label).join("/") ||
                      "音质待确认"}
                    {(track as Candidate).downloaded ? " · 已下载" : ""}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              className="text-button"
              disabled={busy || !song.title.trim()}
              onClick={async () => {
                setBusy(true);
                await match(index, song);
                setBusy(false);
              }}
            >
              重新匹配
            </button>
            {song.selected && (
              <button
                type="button"
                className="text-button"
                onClick={() => {
                  const track = song.candidates.find(
                    (item) => item.id === song.selected,
                  );
                  if (track) play(track);
                }}
              >
                试听
              </button>
            )}
          </div>
        </div>
      ))}
      <div className="ai-import-actions">
        <label>
          <input
            type="checkbox"
            checked={studio}
            disabled={busy}
            onChange={(e) => prefer({ studio: e.target.checked })}
          />{" "}
          自动选择时排除现场、伴奏和混音
        </label>
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() =>
            setSongs((current) => [
              ...current,
              {
                title: "",
                artist: "",
                candidates: [],
                selected: "",
                status: "待匹配",
              },
            ])
          }
        >
          添加歌曲
        </button>
        <button
          type="button"
          className="secondary"
          disabled={busy || !songs.length}
          onClick={() => void matchAll()}
        >
          匹配全部
        </button>
      </div>
      <div className="ai-import-actions">
        <label>
          加入本地歌单
          <select
            value={playlist}
            disabled={busy}
            onChange={(e) => setPlaylist(e.target.value)}
          >
            <option value="">不加入歌单</option>
            {playlists.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={download}
            disabled={busy}
            onChange={(e) => prefer({ download: e.target.checked })}
          />{" "}
          下载
        </label>
        <label>
          <input
            type="checkbox"
            checked={organize}
            disabled={busy || !download}
            onChange={(e) => prefer({ organize: e.target.checked })}
          />{" "}
          下载后整理
        </label>
        <button
          type="button"
          className="primary"
          disabled={
            busy ||
            !songs.some((song) => song.selected) ||
            (!download && !playlist)
          }
          onClick={() => void execute()}
        >
          处理已选 {songs.filter((song) => song.selected).length} 首
        </button>
      </div>
      {notice && <p role="status">{notice}</p>}
    </section>
  );
}
