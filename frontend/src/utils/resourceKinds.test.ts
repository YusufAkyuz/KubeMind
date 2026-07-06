import { describe, it, expect } from 'vitest'
import { ALLOWED_KINDS, KIND_ROUTES, KIND_TEMPLATES } from './resourceKinds'

// Adding a new kind to ALLOWED_KINDS without updating KIND_ROUTES/KIND_TEMPLATES
// is an easy mistake to make (it happened once already) — this guards it.
describe('resourceKinds consistency', () => {
  it('has a route for every allowed kind', () => {
    for (const kind of ALLOWED_KINDS) {
      expect(KIND_ROUTES[kind], `missing KIND_ROUTES entry for ${kind}`).toBeDefined()
    }
  })

  it('has a template for every allowed kind', () => {
    for (const kind of ALLOWED_KINDS) {
      expect(KIND_TEMPLATES[kind], `missing KIND_TEMPLATES entry for ${kind}`).toBeDefined()
    }
  })

  it('every template embeds the given namespace and matching kind', () => {
    for (const kind of ALLOWED_KINDS) {
      const yaml = KIND_TEMPLATES[kind]('test-ns')
      expect(yaml).toContain('namespace: test-ns')
      expect(yaml).toContain(`kind: ${kind}`)
    }
  })
})
