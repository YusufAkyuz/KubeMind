/**
 * Minimal markdown-ish renderer: fenced code blocks, bullet lines, headings,
 * paragraphs. Deliberately no markdown library — model output here is simple
 * prose + bullets + the occasional YAML/shell snippet.
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
              <p key={j} className="text-sm text-gray-700 leading-relaxed pl-4 relative">
                <span className="absolute left-1 text-gray-400">•</span>
                {trimmed.replace(/^[-*•]\s/, '')}
              </p>
            )
          }
          if (/^#{1,4}\s/.test(trimmed)) {
            return (
              <p key={j} className="text-sm font-semibold text-gray-900 pt-1">
                {trimmed.replace(/^#{1,4}\s/, '')}
              </p>
            )
          }
          return (
            <p key={j} className="text-sm text-gray-700 leading-relaxed">
              {trimmed}
            </p>
          )
        })}
      </div>
    ),
  )
}
