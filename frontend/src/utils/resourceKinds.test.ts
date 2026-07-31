import { describe, it, expect } from 'vitest'
import { ALLOWED_KINDS, KIND_ROUTES, KIND_TEMPLATES, RBAC_KINDS, isRbacKind } from './resourceKinds'

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

// This list must stay identical to the backend's ManifestValidation.RBAC_KINDS —
// they gate the same thing from two sides, and a deployment running without
// cluster-admin depends on both agreeing.
describe('RBAC kinds', () => {
  it('matches the backend allowlist exactly', () => {
    expect([...RBAC_KINDS].sort()).toEqual(
      ['ClusterRole', 'ClusterRoleBinding', 'Role', 'RoleBinding', 'ServiceAccount'])
  })

  it('flags escalation-capable kinds and nothing else', () => {
    expect(isRbacKind('RoleBinding')).toBe(true)
    expect(isRbacKind('ClusterRoleBinding')).toBe(true)
    expect(isRbacKind('Deployment')).toBe(false)
    expect(isRbacKind('Secret')).toBe(false)
  })
})
