"""Controlled Mutagen NDJSON v1 helper for MeloKeep.

Strictly single-task per process: reads/writes only explicit file paths provided
via stdin NDJSON and never touches the main database or formal music library.
"""
import base64
import json
import struct
import sys
import threading
from pathlib import Path

import mutagen
from mutagen.asf import ASF, ASFByteArrayAttribute
from mutagen.flac import FLAC, Picture
from mutagen.id3 import (
    APIC,
    COMM,
    ID3,
    TALB,
    TBPM,
    TCOM,
    TCON,
    TCOP,
    TDRC,
    TEXT,
    TIT1,
    TIT2,
    TLAN,
    TPE1,
    TPE2,
    TPOS,
    TPUB,
    TRCK,
    TSRC,
    USLT,
)
from mutagen.mp4 import MP4, MP4Cover, MP4FreeForm

TAG_FIELDS = (
    "title",
    "artist",
    "album",
    "album_artist",
    "date",
    "track_number",
    "disc_number",
    "genre",
    "composer",
    "comment",
    "lyricist",
    "language",
    "copyright",
    "publisher",
    "grouping",
    "bpm",
    "isrc",
)
ID3_FIELDS = (
    "TIT2", "TPE1", "TALB", "TPE2", "TDRC", "TRCK", "TPOS", "TCON", "TCOM",
    "COMM", "TEXT", "TLAN", "TCOP", "TPUB", "TIT1", "TBPM", "TSRC",
)
MP4_FIELDS = (
    "\xa9nam", "\xa9ART", "\xa9alb", "aART", "\xa9day", "trkn", "disk", "\xa9gen",
    "\xa9wrt", "\xa9cmt", "----:com.apple.iTunes:LYRICIST",
    "----:com.apple.iTunes:LANGUAGE", "cprt", "----:com.apple.iTunes:LABEL",
    "\xa9grp", "tmpo", "----:com.apple.iTunes:ISRC",
)
ASF_FIELDS = (
    "Title", "Author", "WM/AlbumTitle", "WM/AlbumArtist", "WM/Year",
    "WM/TrackNumber", "WM/PartOfSet", "WM/Genre", "WM/Composer", "Description",
    "WM/Writer", "WM/Language", "Copyright", "WM/Publisher",
    "WM/ContentGroupDescription", "WM/BeatsPerMinute", "WM/ISRC",
)
VORBIS_FIELDS = (
    "title", "artist", "album", "albumartist", "date", "tracknumber",
    "discnumber", "genre", "composer", "comment", "lyricist", "language",
    "copyright", "organization", "grouping", "bpm", "isrc",
)
MAX_BATCH_FILES = 200

_emit_lock = threading.Lock()


def emit(obj: dict) -> None:
    line = json.dumps(obj, ensure_ascii=False) + "\n"
    with _emit_lock:
        sys.stdout.write(line)
        sys.stdout.flush()


def field_keys(audio):
    if isinstance(audio.tags, ID3):
        return ID3_FIELDS
    if isinstance(audio, MP4):
        return MP4_FIELDS
    if isinstance(audio, ASF):
        return ASF_FIELDS
    return VORBIS_FIELDS


def text_tag(tags, key: str) -> str:
    value = (tags or {}).get(key, "")
    if not value and isinstance(tags, ID3):
        frames = tags.getall(key)
        value = frames[0] if frames else ""
    if hasattr(value, "text"):
        value = value.text
    if isinstance(value, (list, tuple)):
        return " / ".join(
            item.decode("utf-8", errors="replace") if isinstance(item, bytes) else str(item)
            for item in value
        )
    return str(value)


def audio_has_cover(audio) -> bool:
    if not audio:
        return False
    if isinstance(audio.tags, ID3):
        return bool(audio.tags.getall("APIC"))
    if isinstance(audio, MP4) and audio.tags:
        return bool(audio.tags.get("covr"))
    if getattr(audio, "pictures", None):
        return True
    if isinstance(audio, ASF) and audio.tags:
        return bool(audio.tags.get("WM/Picture"))
    if audio.tags and audio.tags.get("metadata_block_picture"):
        return True
    return False


def read_one(path: Path) -> dict:
    audio = mutagen.File(str(path))
    if audio is None:
        raise ValueError("unsupported or unreadable audio file")
    out = {k: "" for k in TAG_FIELDS}
    tagged = []
    for field, key in zip(TAG_FIELDS, field_keys(audio)):
        val = text_tag(audio.tags, key)
        if isinstance(audio, MP4) and field in {"track_number", "disc_number"} and audio.tags and audio.tags.get(key):
            num, total = audio.tags[key][0]
            val = f"{num}/{total}" if total else str(num)
        out[field] = val
        if val:
            tagged.append(field)
    lyrics = ""
    if isinstance(audio.tags, ID3):
        for frame in audio.tags.getall("SYLT"):
            if getattr(frame, "format", None) == 2 and getattr(frame, "type", None) == 1 and frame.text:
                lyrics = "\n".join(
                    f"[{ts // 60000:02}:{(ts // 1000) % 60:02}.{ts % 1000:03}]{txt}"
                    for txt, ts in frame.text
                )
                break
        if not lyrics:
            uslt = audio.tags.getall("USLT")
            if uslt:
                lyrics = str(uslt[0].text or "")
    elif audio.tags:
        lkey = "\xa9lyr" if isinstance(audio, MP4) else "WM/Lyrics" if isinstance(audio, ASF) else "lyrics"
        lyrics = text_tag(audio.tags, lkey)
    info = getattr(audio, "info", None)
    return {
        "path": str(path),
        "tags": out,
        "tagged_fields": tagged,
        "lyrics": lyrics,
        "has_cover": audio_has_cover(audio),
        "editable": hasattr(audio, "add_tags"),
        "duration": float(getattr(info, "length", 0) or 0),
        "bitrate": getattr(info, "bitrate", None),
        "sample_rate": getattr(info, "sample_rate", None),
        "channels": getattr(info, "channels", None),
        "bits_per_sample": getattr(info, "bits_per_sample", None),
    }


def write_one(
    stage_path: Path,
    changes: dict,
    lyrics: str | None,
    cover_path: Path | None,
    cover_mime: str,
    clear_cover: bool = False,
    clear_all: bool = False,
) -> dict:
    audio = mutagen.File(str(stage_path))
    if audio is None or not hasattr(audio, "add_tags"):
        raise ValueError("unsupported container for writing")
    if audio.tags is None:
        audio.add_tags()
    if clear_all:
        audio.tags.clear()
        if isinstance(audio, FLAC):
            audio.clear_pictures()
    frames = (
        TIT2, TPE1, TALB, TPE2, TDRC, TRCK, TPOS, TCON, TCOM,
        COMM, TEXT, TLAN, TCOP, TPUB, TIT1, TBPM, TSRC,
    )
    for field, key, frame in zip(TAG_FIELDS, field_keys(audio), frames):
        if field not in changes:
            continue
        value = str(changes[field] or "").strip()
        if isinstance(audio.tags, ID3):
            audio.tags.delall(key)
            if value:
                extra = {"lang": "eng", "desc": ""} if field == "comment" else {}
                audio.tags.add(frame(encoding=3, text=[value], **extra))
        elif isinstance(audio, MP4) and field in {"track_number", "disc_number"} and value:
            nums = value.split("/", 1)
            if not all(n.isdigit() and 0 <= int(n) <= 65535 for n in nums):
                raise ValueError("track_number and disc_number must be integers or num/total")
            audio.tags[key] = [(int(nums[0]), int(nums[1]) if len(nums) > 1 else 0)]
        elif isinstance(audio, MP4) and key.startswith("----:") and value:
            audio.tags[key] = [MP4FreeForm(value.encode("utf-8"))]
        elif isinstance(audio, MP4) and field == "bpm" and value:
            if not value.isdigit() or not 0 <= int(value) <= 65535:
                raise ValueError("bpm must be an integer between 0 and 65535")
            audio.tags[key] = [int(value)]
        elif value:
            audio.tags[key] = [value]
        elif key in audio.tags:
            del audio.tags[key]
    if lyrics is not None:
        if isinstance(audio.tags, ID3):
            audio.tags.delall("USLT")
            audio.tags.delall("SYLT")
            if lyrics:
                audio.tags.add(USLT(encoding=3, lang="eng", desc="", text=lyrics))
        else:
            lkey = "\xa9lyr" if isinstance(audio, MP4) else "WM/Lyrics" if isinstance(audio, ASF) else "lyrics"
            if lyrics:
                audio.tags[lkey] = [lyrics]
            elif lkey in audio.tags:
                del audio.tags[lkey]
    if clear_cover:
        if isinstance(audio.tags, ID3):
            audio.tags.delall("APIC")
        elif isinstance(audio, FLAC):
            audio.clear_pictures()
        else:
            for k in ("covr", "metadata_block_picture", "WM/Picture", "coverart"):
                if k in audio.tags:
                    del audio.tags[k]
    elif cover_path is not None:
        content = cover_path.read_bytes()
        mime = cover_mime or "image/jpeg"
        if isinstance(audio.tags, ID3):
            audio.tags.delall("APIC")
            audio.tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=content))
        elif isinstance(audio, MP4):
            fmt = MP4Cover.FORMAT_PNG if mime == "image/png" else MP4Cover.FORMAT_JPEG
            audio.tags["covr"] = [MP4Cover(content, imageformat=fmt)]
        else:
            pic = Picture()
            pic.type, pic.mime, pic.data = 3, mime, content
            if isinstance(audio, FLAC):
                audio.clear_pictures()
                audio.add_picture(pic)
            elif isinstance(audio, ASF):
                audio.tags["WM/Picture"] = [
                    ASFByteArrayAttribute(
                        struct.pack("<BI", 3, len(content))
                        + mime.encode("utf-16-le")
                        + b"\0\0\0\0"
                        + content
                    )
                ]
            else:
                audio.tags["metadata_block_picture"] = [base64.b64encode(pic.write()).decode("ascii")]
    audio.save()
    if mutagen.File(str(stage_path)) is None:
        raise ValueError("audio file became unreadable after saving tags")
    return read_one(stage_path)


def main() -> int:
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace" if stream is sys.stderr else "strict")

    emit({
        "v": 1,
        "type": "hello",
        "helper": "tagger",
        "implementation": "mutagen-v1",
        "capabilities": ["read_tags", "batch_read_tags", "write_tags"],
    })

    line = sys.stdin.readline()
    if not line:
        return 1
    try:
        req = json.loads(line)
    except json.JSONDecodeError as exc:
        emit({
            "v": 1,
            "type": "error",
            "id": "",
            "seq": 1,
            "error": {"code": "invalid_input", "message": f"invalid JSON: {exc}", "retryable": False},
        })
        return 1

    task_id = str(req.get("id") or "")
    if req.get("v") != 1 or req.get("type") != "run" or not task_id:
        emit({
            "v": 1,
            "type": "error",
            "id": task_id,
            "seq": 1,
            "error": {"code": "invalid_input", "message": "invalid run envelope", "retryable": False},
        })
        return 1

    cancel_event = threading.Event()

    def watch_cancel() -> None:
        while not cancel_event.is_set():
            nxt = sys.stdin.readline()
            if not nxt:
                return
            try:
                msg = json.loads(nxt)
            except Exception:
                continue
            if msg.get("v") == 1 and msg.get("type") == "cancel" and msg.get("id") == task_id:
                cancel_event.set()
                return

    watcher = threading.Thread(target=watch_cancel, daemon=True)
    watcher.start()

    op = req.get("operation")
    inp = req.get("input") or {}
    seq = 0

    def next_seq() -> int:
        nonlocal seq
        seq += 1
        return seq

    try:
        if op == "read_tags":
            path = Path(str(inp.get("path") or ""))
            if not path.is_file():
                raise ValueError("audio file not found")
            emit({"v": 1, "type": "result", "id": task_id, "seq": next_seq(), "data": read_one(path)})
            return 0

        if op == "batch_read_tags":
            paths = inp.get("paths")
            if not isinstance(paths, list):
                raise ValueError("paths must be a list")
            if len(paths) > MAX_BATCH_FILES:
                emit({
                    "v": 1,
                    "type": "error",
                    "id": task_id,
                    "seq": next_seq(),
                    "error": {
                        "code": "invalid_input",
                        "message": f"batch_read_tags supports at most {MAX_BATCH_FILES} files",
                        "retryable": False,
                    },
                })
                return 1
            items = []
            for raw_path in paths:
                if cancel_event.is_set():
                    emit({
                        "v": 1,
                        "type": "error",
                        "id": task_id,
                        "seq": next_seq(),
                        "error": {"code": "cancelled", "message": "tagger task cancelled", "retryable": False},
                    })
                    return 1
                try:
                    items.append({"path": str(raw_path), "ok": True, "data": read_one(Path(str(raw_path)))})
                except Exception as exc:
                    items.append({"path": str(raw_path), "ok": False, "error": str(exc)})
            emit({"v": 1, "type": "result", "id": task_id, "seq": next_seq(), "data": {"items": items}})
            return 0

        if op == "write_tags":
            stage_raw = str(inp.get("stage_path") or "")
            if not stage_raw:
                raise ValueError("stage_path is required")
            stage_path = Path(stage_raw)
            if stage_path.is_symlink():
                raise ValueError("stage_path cannot be a symlink")
            cover = inp.get("cover")
            cpath = Path(str(cover["path"])) if isinstance(cover, dict) and cover.get("path") else None
            if cpath is not None and cpath.is_symlink():
                raise ValueError("cover path cannot be a symlink")
            cmime = str(cover.get("mime") or "image/jpeg") if isinstance(cover, dict) else ""
            res = write_one(
                stage_path,
                inp.get("tags") or {},
                inp.get("lyrics"),
                cpath,
                cmime,
                clear_cover=bool(inp.get("clear_cover", False)),
                clear_all=bool(inp.get("clear_all", False)),
            )
            emit({"v": 1, "type": "result", "id": task_id, "seq": next_seq(), "data": res})
            return 0

        emit({
            "v": 1,
            "type": "error",
            "id": task_id,
            "seq": next_seq(),
            "error": {"code": "unsupported", "message": f"unsupported operation: {op}", "retryable": False},
        })
        return 1
    except Exception as exc:
        emit({
            "v": 1,
            "type": "error",
            "id": task_id,
            "seq": next_seq(),
            "error": {"code": "internal", "message": str(exc), "retryable": False},
        })
        return 1
    finally:
        cancel_event.set()


if __name__ == "__main__":
    raise SystemExit(main())
