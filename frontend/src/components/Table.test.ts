import { describe, it, expect } from 'vitest'
import { withNamespaceColumn, type Column } from './Table'

const BASE: Column[] = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'age', label: 'Age' },
]

describe('withNamespaceColumn', () => {
  it('returns the columns unchanged when not showing the namespace column', () => {
    expect(withNamespaceColumn(BASE, false)).toEqual(BASE)
  })

  it('inserts a Namespace column right after the first column', () => {
    const result = withNamespaceColumn(BASE, true)
    expect(result.map((c) => c.key)).toEqual(['name', 'namespace', 'status', 'age'])
    expect(result[1].label).toBe('Namespace')
  })
})
