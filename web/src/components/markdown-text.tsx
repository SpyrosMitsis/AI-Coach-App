"use client";

import React from "react";

// Dependency-free markdown renderer matching the Android app's Markdown.kt:
// headers (#–####), bullets, numbered lists, **bold**, *italic*, _italic_,
// `inline code`. Enough for coach replies without pulling in react-markdown.

const INLINE = /(\*\*([^*]+)\*\*)|(\*([^*\s][^*]*)\*)|(_([^_\s][^_]*)_)|(`([^`]+)`)/g;

function inlineNodes(line: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of line.matchAll(INLINE)) {
    const idx = m.index ?? 0;
    if (idx > last) out.push(line.slice(last, idx));
    if (m[2] != null) out.push(<strong key={key++}>{m[2]}</strong>);
    else if (m[4] != null) out.push(<em key={key++}>{m[4]}</em>);
    else if (m[6] != null) out.push(<em key={key++}>{m[6]}</em>);
    else if (m[8] != null) {
      out.push(
        <code key={key++} className="rounded bg-secondary/70 px-1 py-0.5 font-mono text-[0.85em]">
          {m[8]}
        </code>,
      );
    }
    last = idx + m[0].length;
  }
  if (last < line.length) out.push(line.slice(last));
  return out;
}

const HEADER = /^(#{1,4})\s+(.*)$/;
const BULLET = /^[-*•]\s+(.*)$/;
const NUMBERED = /^(\d+)[.)]\s+(.*)$/;

export function MarkdownText({ text, className }: { text: string; className?: string }) {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const blocks: React.ReactNode[] = [];
  let blankRun = 0;
  lines.forEach((raw, i) => {
    const line = raw.trimEnd();
    if (!line.trim()) {
      // Collapse runs of blank lines into a single gap.
      if (blankRun === 0 && blocks.length > 0) blocks.push(<div key={`b${i}`} className="h-2" />);
      blankRun++;
      return;
    }
    blankRun = 0;
    const h = line.match(HEADER);
    if (h) {
      blocks.push(
        <p key={i} className="text-[1.05em] font-bold">{inlineNodes(h[2])}</p>,
      );
      return;
    }
    const b = line.trim().match(BULLET);
    if (b) {
      blocks.push(
        <p key={i} className="pl-3 -indent-3">
          <span className="text-muted-foreground">•&nbsp;&nbsp;</span>
          {inlineNodes(b[1])}
        </p>,
      );
      return;
    }
    const n = line.trim().match(NUMBERED);
    if (n) {
      blocks.push(
        <p key={i} className="pl-4 -indent-4">
          <span className="text-muted-foreground">{n[1]}.&nbsp;&nbsp;</span>
          {inlineNodes(n[2])}
        </p>,
      );
      return;
    }
    blocks.push(<p key={i}>{inlineNodes(line)}</p>);
  });
  return <div className={className ?? "space-y-0.5"}>{blocks}</div>;
}
