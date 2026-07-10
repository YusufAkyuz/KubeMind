/**
 * Line-based diff using the Longest Common Subsequence (LCS) algorithm.
 * Produces a list of diff lines suitable for rendering a unified diff view.
 */

export type DiffLineType = 'same' | 'add' | 'remove'

export interface DiffLine {
  type: DiffLineType
  content: string
  /** Line number in the old text (1-indexed), null for added lines. */
  oldLineNo: number | null
  /** Line number in the new text (1-indexed), null for removed lines. */
  newLineNo: number | null
}

/**
 * Computes a line-level diff between two text strings.
 * Uses the classic LCS dynamic programming approach — O(m×n) time/space,
 * fine for YAML manifests (typically < 500 lines).
 */
export function diffLines(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n')
  const newLines = newText.split('\n')

  const m = oldLines.length
  const n = newLines.length

  // Build LCS table
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0))
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (oldLines[i - 1] === newLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1])
      }
    }
  }

  // Backtrack to produce diff
  const result: DiffLine[] = []
  let i = m
  let j = n

  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      result.push({ type: 'same', content: oldLines[i - 1], oldLineNo: i, newLineNo: j })
      i--
      j--
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      result.push({ type: 'add', content: newLines[j - 1], oldLineNo: null, newLineNo: j })
      j--
    } else {
      result.push({ type: 'remove', content: oldLines[i - 1], oldLineNo: i, newLineNo: null })
      i--
    }
  }

  result.reverse()
  return result
}

/** Counts the number of changed lines (adds + removes) in a diff. */
export function countChanges(diff: DiffLine[]): number {
  return diff.filter((l) => l.type !== 'same').length
}
