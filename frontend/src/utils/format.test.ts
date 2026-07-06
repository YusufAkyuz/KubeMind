import { describe, it, expect, vi, afterEach } from 'vitest'
import { formatAge } from './format'

describe('formatAge', () => {
  afterEach(() => vi.useRealTimers())

  it('returns an em-dash for null/undefined', () => {
    expect(formatAge(null)).toBe('—')
    expect(formatAge(undefined)).toBe('—')
  })

  it('returns an em-dash for a timestamp in the future', () => {
    vi.useFakeTimers().setSystemTime(new Date('2026-01-01T00:00:00Z'))
    expect(formatAge('2026-01-01T00:00:05Z')).toBe('—')
  })

  it('formats seconds', () => {
    vi.useFakeTimers().setSystemTime(new Date('2026-01-01T00:00:30Z'))
    expect(formatAge('2026-01-01T00:00:00Z')).toBe('30s')
  })

  it('formats minutes', () => {
    vi.useFakeTimers().setSystemTime(new Date('2026-01-01T00:05:00Z'))
    expect(formatAge('2026-01-01T00:00:00Z')).toBe('5m')
  })

  it('formats hours', () => {
    vi.useFakeTimers().setSystemTime(new Date('2026-01-01T03:00:00Z'))
    expect(formatAge('2026-01-01T00:00:00Z')).toBe('3h')
  })

  it('formats days', () => {
    vi.useFakeTimers().setSystemTime(new Date('2026-01-05T00:00:00Z'))
    expect(formatAge('2026-01-01T00:00:00Z')).toBe('4d')
  })
})
