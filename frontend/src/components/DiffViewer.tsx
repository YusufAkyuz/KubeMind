import { useMemo } from 'react'
import { diffLines, countChanges, type DiffLine } from '../utils/yamlDiff'

interface Props {
  oldText: string
  newText: string
}

const LINE_NO_W = 'w-10 shrink-0 text-right pr-2 select-none'

function lineClass(type: DiffLine['type']): string {
  switch (type) {
    case 'add':
      return 'bg-emerald-500/15 text-emerald-200'
    case 'remove':
      return 'bg-red-500/15 text-red-300'
    default:
      return 'text-neutral-400'
  }
}

function lineNoClass(type: DiffLine['type']): string {
  switch (type) {
    case 'add':
      return 'text-emerald-600/60'
    case 'remove':
      return 'text-red-500/50'
    default:
      return 'text-neutral-600'
  }
}

function prefix(type: DiffLine['type']): string {
  switch (type) {
    case 'add': return '+'
    case 'remove': return '−'
    default: return ' '
  }
}

/**
 * Read-only diff viewer that renders a unified diff between two YAML strings.
 * Styled to match the dark editor textarea in EditYamlButton.
 */
export function DiffViewer({ oldText, newText }: Props) {
  const diff = useMemo(() => diffLines(oldText, newText), [oldText, newText])
  const changes = useMemo(() => countChanges(diff), [diff])

  if (changes === 0) {
    return (
      <div className="flex items-center justify-center h-[45vh] rounded-md border border-gray-300
                      bg-neutral-950 text-neutral-500 text-sm">
        No changes detected
      </div>
    )
  }

  return (
    <div className="h-[45vh] overflow-auto rounded-md border border-neutral-800 bg-neutral-950
                    font-mono text-xs leading-5">
      <table className="w-full border-collapse">
        <tbody>
          {diff.map((line, i) => (
            <tr key={i} className={lineClass(line.type)}>
              {/* Old line number */}
              <td className={`${LINE_NO_W} ${lineNoClass(line.type)} tabular-nums`}>
                {line.oldLineNo ?? ''}
              </td>
              {/* New line number */}
              <td className={`${LINE_NO_W} ${lineNoClass(line.type)} tabular-nums`}>
                {line.newLineNo ?? ''}
              </td>
              {/* Prefix (+/-/space) */}
              <td className={`w-5 shrink-0 text-center select-none ${
                line.type === 'add' ? 'text-emerald-400' : line.type === 'remove' ? 'text-red-400' : 'text-neutral-600'
              }`}>
                {prefix(line.type)}
              </td>
              {/* Content */}
              <td className="pr-4 whitespace-pre">
                {line.content || '\u00A0'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Returns the count of changes between two strings. */
export function useDiffCount(oldText: string, newText: string): number {
  return useMemo(() => countChanges(diffLines(oldText, newText)), [oldText, newText])
}
