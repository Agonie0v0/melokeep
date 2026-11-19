import type { ReactNode } from "react";

function safeHref(raw: string): string | null {
  try {
    const url = new URL(raw.trim());
    return url.protocol === "http:" || url.protocol === "https:"
      ? url.href
      : null;
  } catch {
    return null;
  }
}

function renderInline(text: string, prefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let cursor = 0;
  let tokenIndex = 0;
  const pattern =
    /(`[^`\n]+`|\*\*[^*\n]+?\*\*|~~[^~\n]+?~~|\*[^*\n]+?\*|\[[^\]\n]+\]\([^)\s]+\))/g;
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0;
    if (index > cursor) nodes.push(text.slice(cursor, index));
    const token = match[0];
    const key = `${prefix}-${tokenIndex++}`;
    if (token.startsWith("`") && token.endsWith("`")) {
      nodes.push(
        <code key={key} className="ai-md-code">
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith("**") && token.endsWith("**")) {
      nodes.push(
        <strong key={key}>{renderInline(token.slice(2, -2), key)}</strong>,
      );
    } else if (token.startsWith("~~") && token.endsWith("~~")) {
      nodes.push(<del key={key}>{renderInline(token.slice(2, -2), key)}</del>);
    } else if (token.startsWith("*") && token.endsWith("*")) {
      nodes.push(<em key={key}>{renderInline(token.slice(1, -1), key)}</em>);
    } else if (token.startsWith("[")) {
      const split = token.indexOf("](");
      const label = token.slice(1, split);
      const href = safeHref(token.slice(split + 2, -1));
      if (href) {
        nodes.push(
          <a
            key={key}
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="ai-md-link"
          >
            {label}
          </a>,
        );
      } else {
        nodes.push(token);
      }
    } else {
      nodes.push(token);
    }
    cursor = index + token.length;
  }
  if (cursor < text.length) nodes.push(text.slice(cursor));
  return nodes;
}

export function AIMarkdown({ content }: { content: string }) {
  const lines = content.replace(/\r\n?/g, "\n").split("\n");
  const blocks: ReactNode[] = [];
  let i = 0;
  let key = 0;

  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();
    if (!trimmed) {
      i++;
      continue;
    }

    if (trimmed.startsWith("```")) {
      const lang = trimmed.slice(3).trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith("```")) {
        codeLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++;
      blocks.push(
        <pre
          key={`b-${key++}`}
          className="ai-md-pre"
          data-lang={lang || undefined}
        >
          <code>{codeLines.join("\n")}</code>
        </pre>,
      );
      continue;
    }

    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      blocks.push(<hr key={`b-${key++}`} className="ai-md-hr" />);
      i++;
      continue;
    }

    const heading = trimmed.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      const level = heading[1].length;
      const blockKey = `b-${key++}`;
      blocks.push(
        <div
          key={blockKey}
          className={`ai-md-h ai-md-h${level}`}
          role="heading"
          aria-level={level + 2}
        >
          {renderInline(heading[2], blockKey)}
        </div>,
      );
      i++;
      continue;
    }

    if (trimmed.startsWith(">")) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith(">")) {
        quoteLines.push(lines[i].trim().replace(/^>\s?/, ""));
        i++;
      }
      const blockKey = `b-${key++}`;
      blocks.push(
        <blockquote key={blockKey} className="ai-md-quote">
          {quoteLines.map((item, idx) => (
            <p key={`${blockKey}-${idx}`}>
              {renderInline(item, `${blockKey}-${idx}`)}
            </p>
          ))}
        </blockquote>,
      );
      continue;
    }

    if (/^[-*+]\s+/.test(trimmed)) {
      const items: string[] = [];
      while (i < lines.length && /^[-*+]\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^[-*+]\s+/, ""));
        i++;
      }
      const blockKey = `b-${key++}`;
      blocks.push(
        <ul key={blockKey} className="ai-md-list">
          {items.map((item, idx) => (
            <li key={`${blockKey}-${idx}`}>
              {renderInline(item, `${blockKey}-${idx}`)}
            </li>
          ))}
        </ul>,
      );
      continue;
    }

    if (/^\d+[.)]\s+/.test(trimmed)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+[.)]\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^\d+[.)]\s+/, ""));
        i++;
      }
      const blockKey = `b-${key++}`;
      blocks.push(
        <ol key={blockKey} className="ai-md-list">
          {items.map((item, idx) => (
            <li key={`${blockKey}-${idx}`}>
              {renderInline(item, `${blockKey}-${idx}`)}
            </li>
          ))}
        </ol>,
      );
      continue;
    }

    const paraLines: string[] = [];
    while (i < lines.length) {
      const next = lines[i];
      const nextTrimmed = next.trim();
      if (
        !nextTrimmed ||
        nextTrimmed.startsWith("```") ||
        /^(#{1,4})\s+/.test(nextTrimmed) ||
        nextTrimmed.startsWith(">") ||
        /^[-*+]\s+/.test(nextTrimmed) ||
        /^\d+[.)]\s+/.test(nextTrimmed) ||
        /^(-{3,}|\*{3,}|_{3,})$/.test(nextTrimmed)
      ) {
        break;
      }
      paraLines.push(next);
      i++;
    }
    const blockKey = `b-${key++}`;
    blocks.push(
      <p key={blockKey} className="ai-md-p">
        {paraLines.map((part, idx) => (
          <span key={`${blockKey}-${idx}`}>
            {idx > 0 && <br />}
            {renderInline(part, `${blockKey}-${idx}`)}
          </span>
        ))}
      </p>,
    );
  }

  return <div className="ai-md">{blocks}</div>;
}
