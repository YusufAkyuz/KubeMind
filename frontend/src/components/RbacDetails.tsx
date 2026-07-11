import { DrawerSection } from './DetailDrawer'
import type { RbacRule, RbacSubject } from '../types/k8s'

/** PolicyRule list shared by Role and ClusterRole detail drawers. */
export function RbacRules({ rules }: { rules: RbacRule[] }) {
  return (
    <>
      <DrawerSection title={`Rules (${rules.length})`} />
      {rules.length === 0 && <p className="text-sm text-gray-400">No rules.</p>}
      <div className="space-y-2">
        {rules.map((r, i) => (
          <div key={i} className="rounded-lg border border-gray-200 p-3 space-y-1 text-xs">
            <RuleLine label="API groups" values={r.apiGroups} fallback='""  (core)' />
            <RuleLine label="Resources" values={r.resources} />
            {r.resourceNames.length > 0 && <RuleLine label="Resource names" values={r.resourceNames} />}
            <RuleLine label="Verbs" values={r.verbs} mono={false} accent />
          </div>
        ))}
      </div>
    </>
  )
}

function RuleLine({ label, values, fallback = '—', mono = true, accent = false }: {
  label: string; values: string[]; fallback?: string; mono?: boolean; accent?: boolean
}) {
  return (
    <div className="flex gap-2">
      <span className="w-28 shrink-0 text-gray-400">{label}</span>
      <span className={`break-all ${mono ? 'font-mono' : ''} ${accent ? 'text-blue-700' : 'text-gray-700'}`}>
        {values.length > 0 ? values.join(', ') : fallback}
      </span>
    </div>
  )
}

/** Subject list shared by RoleBinding and ClusterRoleBinding detail drawers. */
export function RbacSubjects({ subjects, roleRefKind, roleRefName }: {
  subjects: RbacSubject[]; roleRefKind: string | null; roleRefName: string | null
}) {
  return (
    <>
      <DrawerSection title="Role ref" />
      <div className="rounded-lg border border-gray-200 p-3 text-xs">
        <span className="text-gray-400">{roleRefKind ?? '—'}</span>{' '}
        <span className="font-mono text-gray-800">{roleRefName ?? '—'}</span>
      </div>

      <DrawerSection title={`Subjects (${subjects.length})`} />
      {subjects.length === 0 && <p className="text-sm text-gray-400">No subjects.</p>}
      <div className="space-y-2">
        {subjects.map((s, i) => (
          <div key={i} className="rounded-lg border border-gray-200 p-3 text-xs flex gap-2">
            <span className="text-gray-400 shrink-0">{s.kind}</span>
            <span className="font-mono text-gray-800 break-all">
              {s.namespace ? `${s.namespace}/${s.name}` : s.name}
            </span>
          </div>
        ))}
      </div>
    </>
  )
}
