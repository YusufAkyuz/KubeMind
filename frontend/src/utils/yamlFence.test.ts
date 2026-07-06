import { describe, it, expect } from 'vitest'
import { stripLeadingFence, stripTrailingFence } from './yamlFence'

describe('stripLeadingFence', () => {
  it('strips a language-tagged fence', () => {
    expect(stripLeadingFence('```yaml\nfoo: bar\n')).toBe('foo: bar\n')
  })

  it('strips a bare fence', () => {
    expect(stripLeadingFence('```\nfoo: bar\n')).toBe('foo: bar\n')
  })

  it('leaves untouched text alone', () => {
    expect(stripLeadingFence('foo: bar\n')).toBe('foo: bar\n')
  })
})

describe('stripTrailingFence', () => {
  it('strips a trailing fence on its own line', () => {
    expect(stripTrailingFence('foo: bar\n```')).toBe('foo: bar')
  })

  it('strips a trailing fence with trailing whitespace', () => {
    expect(stripTrailingFence('foo: bar\n```  \n')).toBe('foo: bar')
  })

  it('leaves untouched text alone', () => {
    expect(stripTrailingFence('foo: bar\n')).toBe('foo: bar\n')
  })
})

describe('combined', () => {
  it('recovers plain YAML from a fully fenced AI response', () => {
    const raw = '```yaml\napiVersion: v1\nkind: Pod\n```'
    expect(stripTrailingFence(stripLeadingFence(raw))).toBe('apiVersion: v1\nkind: Pod')
  })
})
