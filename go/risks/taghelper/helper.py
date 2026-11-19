import json
import os
import signal
import struct
import sys
from pathlib import Path

import mutagen
from mutagen.asf import ASF, ASFByteArrayAttribute
from mutagen.flac import FLAC, Picture
from mutagen.id3 import (
    APIC, COMM, ID3, TALB, TBPM, TCOM, TCON, TCOP, TDRC, TEXT, TIT1, TIT2, TLAN,
    TPE1, TPE2, TPOS, TPUB, TRCK, TSRC, USLT,
)
from mutagen.mp4 import MP4, MP4Cover, MP4FreeForm

TAG_FIELDS = (
    'title', 'artist', 'album', 'album_artist', 'date', 'track_number', 'disc_number',
    'genre', 'composer', 'comment', 'lyricist', 'language', 'copyright', 'publisher',
    'grouping', 'bpm', 'isrc',
)
ID3_FIELDS = ('TIT2', 'TPE1', 'TALB', 'TPE2', 'TDRC', 'TRCK', 'TPOS', 'TCON', 'TCOM', 'COMM', 'TEXT', 'TLAN', 'TCOP', 'TPUB', 'TIT1', 'TBPM', 'TSRC')
MP4_FIELDS = ('\xa9nam', '\xa9ART', '\xa9alb', 'aART', '\xa9day', 'trkn', 'disk', '\xa9gen', '\xa9wrt', '\xa9cmt',
              '----:com.apple.iTunes:LYRICIST', '----:com.apple.iTunes:LANGUAGE', 'cprt', '----:com.apple.iTunes:LABEL',
              '\xa9grp', 'tmpo', '----:com.apple.iTunes:ISRC')
ASF_FIELDS = ('Title', 'Author', 'WM/AlbumTitle', 'WM/AlbumArtist', 'WM/Year', 'WM/TrackNumber', 'WM/PartOfSet', 'WM/Genre', 'WM/Composer', 'Description',
              'WM/Writer', 'WM/Language', 'Copyright', 'WM/Publisher', 'WM/ContentGroupDescription', 'WM/BeatsPerMinute', 'WM/ISRC')
VORBIS_FIELDS = ('title', 'artist', 'album', 'albumartist', 'date', 'tracknumber', 'discnumber', 'genre', 'composer', 'comment',
                 'lyricist', 'language', 'copyright', 'organization', 'grouping', 'bpm', 'isrc')


def emit(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + '\n')
    sys.stdout.flush()


def field_keys(audio):
    return ID3_FIELDS if isinstance(audio.tags, ID3) else MP4_FIELDS if isinstance(audio, MP4) else ASF_FIELDS if isinstance(audio, ASF) else VORBIS_FIELDS


def text_tag(tags, key):
    value = (tags or {}).get(key, '')
    if not value and isinstance(tags, ID3):
        frames = tags.getall(key)
        value = frames[0] if frames else ''
    if hasattr(value, 'text'):
        value = value.text
    if isinstance(value, (list, tuple)):
        return ' / '.join(item.decode('utf-8', errors='replace') if isinstance(item, bytes) else str(item) for item in value)
    return str(value)


def read_one(path: Path):
    audio = mutagen.File(str(path))
    if audio is None:
        raise ValueError('unsupported or unreadable audio file')
    out = {k: '' for k in TAG_FIELDS}
    for field, key in zip(TAG_FIELDS, field_keys(audio)):
        val = text_tag(audio.tags, key)
        if isinstance(audio, MP4) and field in {'track_number', 'disc_number'} and audio.tags and audio.tags.get(key):
            num, total = audio.tags[key][0]
            val = f'{num}/{total}' if total else str(num)
        out[field] = val
    lyrics = ''
    if isinstance(audio.tags, ID3):
        uslt = audio.tags.getall('USLT')
        if uslt:
            lyrics = str(uslt[0].text or '')
    elif audio.tags:
        lkey = '\xa9lyr' if isinstance(audio, MP4) else 'WM/Lyrics' if isinstance(audio, ASF) else 'lyrics'
        lyrics = text_tag(audio.tags, lkey)
    has_cover = False
    if isinstance(audio.tags, ID3):
        has_cover = bool(audio.tags.getall('APIC'))
    elif isinstance(audio, MP4) and audio.tags:
        has_cover = bool(audio.tags.get('covr'))
    elif getattr(audio, 'pictures', None):
        has_cover = True
    elif isinstance(audio, ASF) and audio.tags:
        has_cover = bool(audio.tags.get('WM/Picture'))
    elif audio.tags and audio.tags.get('metadata_block_picture'):
        has_cover = True
    return {
        'path': str(path),
        'tags': out,
        'lyrics': lyrics,
        'has_cover': has_cover,
        'duration': float(audio.info.length),
    }


def write_one(stage_path: Path, changes: dict, lyrics: str | None, cover_path: Path | None, cover_mime: str):
    audio = mutagen.File(str(stage_path))
    if audio is None or not hasattr(audio, 'add_tags'):
        raise ValueError('unsupported container for writing')
    if audio.tags is None:
        audio.add_tags()
    frames = (TIT2, TPE1, TALB, TPE2, TDRC, TRCK, TPOS, TCON, TCOM, COMM, TEXT, TLAN, TCOP, TPUB, TIT1, TBPM, TSRC)
    for field, key, frame in zip(TAG_FIELDS, field_keys(audio), frames):
        if field not in changes:
            continue
        value = str(changes[field] or '').strip()
        if isinstance(audio.tags, ID3):
            audio.tags.delall(key)
            if value:
                audio.tags.add(frame(encoding=3, text=[value], **({'lang': 'eng', 'desc': ''} if field == 'comment' else {})))
        elif isinstance(audio, MP4) and field in {'track_number', 'disc_number'} and value:
            nums = value.split('/', 1)
            audio.tags[key] = [(int(nums[0]), int(nums[1]) if len(nums) > 1 else 0)]
        elif isinstance(audio, MP4) and key.startswith('----:') and value:
            audio.tags[key] = [MP4FreeForm(value.encode('utf-8'))]
        elif isinstance(audio, MP4) and field == 'bpm' and value:
            audio.tags[key] = [int(value)]
        elif value:
            audio.tags[key] = [value]
        elif key in audio.tags:
            del audio.tags[key]
    if lyrics is not None:
        if isinstance(audio.tags, ID3):
            audio.tags.delall('USLT')
            if lyrics:
                audio.tags.add(USLT(encoding=3, lang='eng', desc='', text=lyrics))
        else:
            audio.tags['\xa9lyr' if isinstance(audio, MP4) else 'WM/Lyrics' if isinstance(audio, ASF) else 'lyrics'] = [lyrics]
    if cover_path is not None:
        content = cover_path.read_bytes()
        if isinstance(audio.tags, ID3):
            audio.tags.delall('APIC')
            audio.tags.add(APIC(encoding=3, mime=cover_mime, type=3, desc='Cover', data=content))
        elif isinstance(audio, MP4):
            audio.tags['covr'] = [MP4Cover(content, imageformat=MP4Cover.FORMAT_PNG if cover_mime == 'image/png' else MP4Cover.FORMAT_JPEG)]
        else:
            pic = Picture()
            pic.type, pic.mime, pic.data = 3, cover_mime, content
            if isinstance(audio, FLAC):
                audio.clear_pictures()
                audio.add_picture(pic)
            elif isinstance(audio, ASF):
                audio.tags['WM/Picture'] = [ASFByteArrayAttribute(
                    struct.pack('<BI', 3, len(content)) + cover_mime.encode('utf-16-le') + b'\0\0\0\0' + content)]
            else:
                import base64
                audio.tags['metadata_block_picture'] = [base64.b64encode(pic.write()).decode('ascii')]
    audio.save()
    return read_one(stage_path)


def main():
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace' if stream is sys.stderr else 'strict')
    emit({'v': 1, 'type': 'hello', 'helper': 'tagger', 'implementation': 'mutagen-risk', 'capabilities': ['batch_read_tags', 'write_tags']})
    line = sys.stdin.readline()
    if not line:
        return 1
    req = json.loads(line)
    if req.get('v') != 1 or req.get('type') != 'run':
        emit({'v': 1, 'type': 'error', 'id': req.get('id', ''), 'seq': 1, 'error': {'code': 'invalid_request', 'message': 'bad envelope'}})
        return 1
    task_id = req['id']
    op = req['operation']
    inp = req['input']
    try:
        if op == 'batch_read_tags':
            results = []
            for p in inp['paths']:
                try:
                    results.append({'path': p, 'ok': True, 'data': read_one(Path(p))})
                except Exception as exc:
                    results.append({'path': p, 'ok': False, 'error': str(exc)})
            emit({'v': 1, 'type': 'result', 'id': task_id, 'seq': 1, 'data': {'items': results}})
        elif op == 'write_tags':
            cover = inp.get('cover')
            cpath = Path(cover['path']) if cover else None
            cmime = cover.get('mime', 'image/png') if cover else ''
            res = write_one(Path(inp['stage_path']), inp.get('tags', {}), inp.get('lyrics'), cpath, cmime)
            emit({'v': 1, 'type': 'result', 'id': task_id, 'seq': 1, 'data': res})
        else:
            emit({'v': 1, 'type': 'error', 'id': task_id, 'seq': 1, 'error': {'code': 'unsupported', 'message': f'unknown op {op}'}})
            return 1
    except Exception as exc:
        emit({'v': 1, 'type': 'error', 'id': task_id, 'seq': 1, 'error': {'code': 'internal', 'message': str(exc)}})
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
