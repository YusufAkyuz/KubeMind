/**
 * Renders **bold** and `code` spans within a single line of text. Kept separate
 * from renderLiteMarkdown so plain strings (e.g. a one-line narrative) can use
 * just the inline pass without the block-level (bullets/headings/fences) one.
 */
export function renderInline(text: string) {
  const segments = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).filter(Boolean)
  return segments.map((seg, i) => {
    if (seg.startsWith('**') && seg.endsWith('**')) {
      return <strong key={i} className="font-semibold text-gray-900 dark:text-slate-100">{seg.slice(2, -2)}</strong>
    }
    if (seg.startsWith('`') && seg.endsWith('`')) {
      return <code key={i} className="rounded bg-gray-100 dark:bg-slate-700 px-1 py-0.5 font-mono text-[0.85em]">{seg.slice(1, -1)}</code>
    }
    return seg
  })
}

/**
 * Minimal markdown-ish renderer: fenced code blocks, bullet lines, headings,
 * paragraphs, and inline bold/code spans. Deliberately no markdown
 * library — model output here is simple prose + bullets + the occasional
 * YAML/shell snippet.
 */
export function renderLiteMarkdown(text: string) {
  const parts = text.split('```')
  return parts.map((part, i) =>
    i % 2 === 1 ? (
      <pre
        key={i}
        className="my-2 rounded-md bg-gray-900 text-gray-100 text-xs font-mono p-3 overflow-x-auto whitespace-pre-wrap"
      >
        {part.replace(/^[a-z]*\n/, '')}
      </pre>
    ) : (
      <div key={i} className="space-y-1.5">
        {part.split('\n').map((line, j) => {
          const trimmed = line.trim()
          if (!trimmed) return null
          if (/^[-*•]\s/.test(trimmed)) {
            return (
              <p key={j} className="text-sm text-gray-700 dark:text-slate-300 leading-relaxed pl-4 relative">
                <span className="absolute left-1 text-gray-400 dark:text-slate-500">•</span>
                {renderInline(trimmed.replace(/^[-*•]\s/, ''))}
              </p>
            )
          }
          if (/^#{1,4}\s/.test(trimmed)) {
            return (
              <p key={j} className="text-sm font-semibold text-gray-900 dark:text-slate-100 pt-1">
                {renderInline(trimmed.replace(/^#{1,4}\s/, ''))}
              </p>
            )
          }
          return (
            <p key={j} className="text-sm text-gray-700 dark:text-slate-300 leading-relaxed">
              {renderInline(trimmed)}
            </p>
          )
        })}
      </div>
    ),
  )
}
