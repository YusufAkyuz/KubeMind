import { describe, it, expect } from 'vitest'
import { diffLines, countChanges } from './yamlDiff'

describe('diffLines', () => {
  it('returns all same when texts are identical', () => {
    const text = 'line1\nline2\nline3'
    const diff = diffLines(text, text)
    expect(diff).toHaveLength(3)
    expect(diff.every((l) => l.type === 'same')).toBe(true)
  })

  it('detects added lines', () => {
    const diff = diffLines('a\nb', 'a\nb\nc')
    expect(diff).toHaveLength(3)
    expect(diff[2]).toEqual({ type: 'add', content: 'c', oldLineNo: null, newLineNo: 3 })
  })

  it('detects removed lines', () => {
    const diff = diffLines('a\nb\nc', 'a\nc')
    const removed = diff.filter((l) => l.type === 'remove')
    expect(removed).toHaveLength(1)
    expect(removed[0].content).toBe('b')
  })

  it('detects changed lines as remove + add', () => {
    const diff = diffLines('replicas: 1', 'replicas: 3')
    expect(diff).toHaveLength(2)
    expect(diff[0]).toMatchObject({ type: 'remove', content: 'replicas: 1' })
    expect(diff[1]).toMatchObject({ type: 'add', content: 'replicas: 3' })
  })

  it('handles empty old text', () => {
    const diff = diffLines('', 'new line')
    expect(diff.filter((l) => l.type === 'add')).toHaveLength(1)
  })

  it('handles empty new text', () => {
    const diff = diffLines('old line', '')
    expect(diff.filter((l) => l.type === 'remove')).toHaveLength(1)
  })
})

describe('countChanges', () => {
  it('returns 0 for identical texts', () => {
    expect(countChanges(diffLines('same', 'same'))).toBe(0)
  })

  it('counts adds and removes', () => {
    expect(countChanges(diffLines('a\nb', 'a\nc'))).toBe(2) // 1 remove + 1 add
  })
})
