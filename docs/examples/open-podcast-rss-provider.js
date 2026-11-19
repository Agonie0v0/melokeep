/**
 * MeloKeep Provider Plugin API v1 — 公开 Podcast RSS / Atom XML 参考插件
 *
 * 用途：
 * - 读取用户显式配置的公开 RSS 2.0 或 Atom XML 播客订阅源（feed_url）
 * - 使用 ctx.http.request 经沙箱 SSRF 公网校验抓取 XML，并提取分集音频附件（<enclosure> / <link rel="enclosure">）
 * - 支持关键词搜索筛选、分集列表展开（episodes）与音频直链解析（resolve）
 */

const manifest = {
  apiVersion: 1,
  id: "open-podcast-rss",
  name: "公开播客 RSS 订阅",
  version: "1.0.0",
  description: "通用标准 Podcast RSS / Atom XML 解析插件示例",
  category: "播客与有声",
  capabilities: {
    search: true,
    playlist: false,
    episodes: true,
    lyrics: false,
    metadata: false,
  },
  settings: [
    {
      key: "feed_url",
      label: "Podcast RSS / Atom 地址",
      type: "url",
      placeholder: "https://feeds.example.org/podcast.xml",
      required: true,
      defaultValue: "",
    },
  ],
};

function cleanText(ctx, raw) {
  if (!raw) return "";
  const withoutCdata = String(raw).replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1");
  const unescaped = ctx.html.unescape(withoutCdata);
  return ctx.html.unescape(ctx.html.stripTags(unescaped)).replace(/\s+/g, " ").trim();
}

function extractTag(block, tagName) {
  const escaped = tagName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp("<" + escaped + "(?:\\s[^>]*)?>([\\s\\S]*?)<\\/" + escaped + ">", "i");
  const m = re.exec(block);
  return m ? m[1] : "";
}

function extractAttr(tagSnippet, attrName) {
  const escaped = attrName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(escaped + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", "i");
  const m = re.exec(tagSnippet);
  return m ? (m[1] || m[2] || "") : "";
}

function parseDurationSeconds(raw) {
  const val = String(raw || "").trim();
  if (!val) return 0;
  if (/^\d+(\.\d+)?$/.test(val)) {
    return Math.round(parseFloat(val));
  }
  const parts = val.split(":").map(function (p) {
    return parseInt(p, 10);
  });
  if (parts.some(isNaN)) return 0;
  if (parts.length === 3) {
    return parts[0] * 3600 + parts[1] * 60 + parts[2];
  }
  if (parts.length === 2) {
    return parts[0] * 60 + parts[1];
  }
  return 0;
}

function guessCodec(mimeType, audioUrl) {
  const lowerMime = String(mimeType || "").toLowerCase();
  const lowerUrl = String(audioUrl || "").toLowerCase();
  if (lowerMime.indexOf("aac") !== -1 || lowerMime.indexOf("mp4") !== -1 || /\.m4a(\?|$)/.test(lowerUrl)) {
    return "m4a";
  }
  if (lowerMime.indexOf("ogg") !== -1 || lowerMime.indexOf("opus") !== -1 || /\.(ogg|opus)(\?|$)/.test(lowerUrl)) {
    return "opus";
  }
  if (lowerMime.indexOf("flac") !== -1 || /\.flac(\?|$)/.test(lowerUrl)) {
    return "flac";
  }
  return "mp3";
}

function parsePodcastFeed(ctx, xmlBody, feedUrl) {
  const channelHeader = xmlBody.split(/<(?:item|entry)\b/i)[0] || "";
  const channelTitle = cleanText(ctx, extractTag(channelHeader, "title")) || "Open Podcast";
  const channelAuthor =
    cleanText(ctx, extractTag(channelHeader, "itunes:author")) ||
    cleanText(ctx, extractTag(channelHeader, "author")) ||
    channelTitle;

  let channelCover = "";
  const itunesImgMatch = /<itunes:image\b[^>]*>/i.exec(channelHeader);
  if (itunesImgMatch) {
    channelCover = ctx.html.unescape(extractAttr(itunesImgMatch[0], "href"));
  } else {
    const imgBlock = extractTag(channelHeader, "image");
    channelCover = cleanText(ctx, extractTag(imgBlock, "url"));
  }

  const tracks = [];
  const itemRe = /<(item|entry)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  let match;
  while ((match = itemRe.exec(xmlBody)) !== null) {
    const block = match[2];
    const title = cleanText(ctx, extractTag(block, "title"));
    if (!title) continue;

    let audioUrl = "";
    let mimeType = "";
    let sizeBytes = 0;

    const encMatch = /<enclosure\b[^>]*>/i.exec(block);
    if (encMatch) {
      audioUrl = ctx.html.unescape(extractAttr(encMatch[0], "url"));
      mimeType = extractAttr(encMatch[0], "type");
      const len = parseInt(extractAttr(encMatch[0], "length"), 10);
      if (!isNaN(len) && len > 0) sizeBytes = len;
    } else {
      const linkMatch = /<link\b[^>]*rel\s*=\s*["']enclosure["'][^>]*>/i.exec(block);
      if (linkMatch) {
        audioUrl = ctx.html.unescape(extractAttr(linkMatch[0], "href"));
        mimeType = extractAttr(linkMatch[0], "type");
        const len = parseInt(extractAttr(linkMatch[0], "length"), 10);
        if (!isNaN(len) && len > 0) sizeBytes = len;
      }
    }

    if (!/^https?:\/\//i.test(audioUrl)) continue;

    const guid = cleanText(ctx, extractTag(block, "guid")) || cleanText(ctx, extractTag(block, "id")) || audioUrl;
    const author = cleanText(ctx, extractTag(block, "itunes:author")) || channelAuthor;
    const duration = parseDurationSeconds(cleanText(ctx, extractTag(block, "itunes:duration")));
    const epImgMatch = /<itunes:image\b[^>]*>/i.exec(block);
    const cover = epImgMatch ? ctx.html.unescape(extractAttr(epImgMatch[0], "href")) : channelCover;
    const codec = guessCodec(mimeType, audioUrl);

    // 将可识别的音频直链编码进 sourceId，保证跨请求无状态 resolve
    const sourceId = feedUrl + "::" + guid + "::" + audioUrl;
    const track = {
      sourceId: sourceId,
      title: title,
      artist: author,
      album: channelTitle,
      duration: duration,
      cover: cover || null,
      variants: [
        {
          variantId: codec + ":stream",
          label: codec.toUpperCase() + " Podcast",
          codec: codec,
          bitrate: null,
          lossless: codec === "flac",
          availability: "available",
          evidence: "rss_enclosure",
          previewOnly: false,
          sizeBytes: sizeBytes > 0 ? sizeBytes : null,
        },
      ],
    };
    if (sizeBytes > 0) {
      track.sizeBytes = sizeBytes;
    }
    tracks.push(track);
  }
  return tracks;
}

function fetchFeedTracks(ctx) {
  const feedUrl = ctx && ctx.settings && typeof ctx.settings.feed_url === "string"
    ? ctx.settings.feed_url.trim()
    : "";
  if (!/^https?:\/\//i.test(feedUrl)) {
    throw new Error("请在插件设置中填写有效的公开 HTTP/HTTPS Podcast RSS 地址 (feed_url)");
  }

  ctx.log("info", "Fetching podcast RSS feed: " + feedUrl);
  const resp = ctx.http.request({
    method: "GET",
    url: feedUrl,
    headers: {
      Accept: "application/rss+xml, application/atom+xml, application/xml, text/xml",
    },
    timeoutMs: 10000,
  });

  if (resp.status < 200 || resp.status >= 300) {
    throw new Error("获取 RSS 订阅失败，HTTP 状态码：" + resp.status);
  }
  return parsePodcastFeed(ctx, resp.body, feedUrl);
}

function search(ctx, opts) {
  const keyword = ((opts && opts.keyword) || "").trim().toLowerCase();
  const limit = opts && opts.limit > 0 ? opts.limit : 15;
  const allTracks = fetchFeedTracks(ctx);

  let emitted = 0;
  for (let i = 0; i < allTracks.length && emitted < limit; i++) {
    const tr = allTracks[i];
    const haystack = (tr.title + " " + tr.artist + " " + tr.album).toLowerCase();
    if (keyword === "" || haystack.indexOf(keyword) !== -1) {
      ctx.emit(tr);
      emitted++;
    }
  }

  return {
    count: emitted,
    detail: "Matched " + emitted + " episodes from RSS feed",
  };
}

function episodes(ctx, track) {
  const tracks = fetchFeedTracks(ctx);
  if (track && track.album) {
    const albumLower = String(track.album).toLowerCase();
    const filtered = tracks.filter(function (item) {
      return item.album.toLowerCase() === albumLower;
    });
    if (filtered.length > 0) return filtered;
  }
  return tracks;
}

function resolve(ctx, track) {
  const sid = track && track.sourceId ? String(track.sourceId) : "";
  const sepIdx = sid.lastIndexOf("::");
  let audioUrl = sepIdx !== -1 ? sid.slice(sepIdx + 2) : "";

  if (!/^https?:\/\//i.test(audioUrl)) {
    const tracks = fetchFeedTracks(ctx);
    const matched = tracks.find(function (t) {
      return t.sourceId === sid || (track && t.title === track.title);
    });
    if (matched) {
      const idx = matched.sourceId.lastIndexOf("::");
      audioUrl = idx !== -1 ? matched.sourceId.slice(idx + 2) : "";
    }
  }

  if (!/^https?:\/\//i.test(audioUrl)) {
    throw new Error("未在 RSS 条目中找到有效的音频 enclosure URL");
  }

  const codec = guessCodec("", audioUrl);
  return [
    {
      variantId: codec + ":stream",
      label: codec.toUpperCase() + " Podcast",
      codec: codec,
      bitrate: null,
      lossless: codec === "flac",
      previewOnly: false,
      protocol: "http",
      url: audioUrl,
      headers: {},
      duration: track && track.duration ? track.duration : 0,
    },
  ];
}

module.exports = {
  manifest: manifest,
  search: search,
  resolve: resolve,
  episodes: episodes,
};
