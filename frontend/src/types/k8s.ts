export interface Cluster {
  id: number
  name: string
  builtIn: boolean
  createdBy: string | null
  createdAt: string | null
  lastCheckedAt: string | null
  lastCheckOk: boolean | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | null
}

/** ADMIN's minimal-exposure view of a cluster request awaiting a decision —
 *  no kubeconfig, no health data (see backend PendingClusterDto). */
export interface PendingCluster {
  id: number
  name: string
  createdBy: string
  createdAt: string
}

export interface AppUser {
  id: number
  username: string
  role: 'ADMIN' | 'USER'
  /** 'oidc' accounts sign in through the identity provider and have no local
   *  password — the backend refuses to set one (see UserService.resetPassword),
   *  since a local password would outlive being disabled in the IdP. */
  identityProvider: 'local' | 'oidc'
}

export interface Namespace {
  name: string
  phase: string | null
  creationTimestamp: string | null
}

export interface NodeResource {
  name: string
  status: string
  roles: string
  kubeletVersion: string | null
  osImage: string | null
  cpuCapacity: string | null
  memoryCapacity: string | null
  cpuAllocatable: string | null
  memoryAllocatable: string | null
  creationTimestamp: string | null
}

export interface NodeMetrics {
  name: string
  cpuUsage: string | null
  memoryUsage: string | null
}

export interface IncidentPattern {
  signature: string
  count: number
  firstSeen: string
  lastSeen: string
}

export interface ChangeEntry {
  action: string
  resourceRef: string
  result: string
  createdAt: string | null
}

export interface ClusterInsights {
  available: boolean
  nodeCount: number
  nodeVersions: string[]
  namespaceCount: number
  podCount: number
  unhealthyPodCount: number
  unhealthyHighlights: string[]
  incidentNarrative: string | null
  topIncidents: IncidentPattern[]
  recentChanges: ChangeEntry[]
  lastUpdated: string | null
}

export interface PodMetrics {
  name: string
  namespace: string
  cpuUsage: string | null
  memoryUsage: string | null
}

export interface ContainerInfo {
  name: string
  image: string
  ready: boolean
  restartCount: number
  lastTerminatedReason: string | null
}

export interface Pod {
  name: string
  namespace: string
  phase: string
  nodeName: string | null
  restartCount: number
  podIP: string | null
  creationTimestamp: string | null
  lastTerminatedReason: string | null
  containers: ContainerInfo[]
}

export interface Deployment {
  name: string
  namespace: string
  desiredReplicas: number
  readyReplicas: number
  availableReplicas: number
  strategy: string
  image: string | null
  creationTimestamp: string | null
}

export interface ConfigMap {
  name: string
  namespace: string
  data: Record<string, string>
  binaryDataCount: number
  creationTimestamp: string | null
}

export interface Secret {
  name: string
  namespace: string
  type: string
  keys: string[]
  creationTimestamp: string | null
}

export interface StatefulSet {
  name: string
  namespace: string
  desiredReplicas: number
  readyReplicas: number
  serviceName: string | null
  image: string | null
  creationTimestamp: string | null
}

export interface DaemonSet {
  name: string
  namespace: string
  desired: number
  ready: number
  available: number
  image: string | null
  creationTimestamp: string | null
}

export interface Job {
  name: string
  namespace: string
  status: string
  succeeded: number
  failed: number
  active: number
  completions: number | null
  image: string | null
  startTime: string | null
  completionTime: string | null
  creationTimestamp: string | null
}

export interface CronJob {
  name: string
  namespace: string
  schedule: string | null
  suspended: boolean
  activeJobs: number
  image: string | null
  lastScheduleTime: string | null
  creationTimestamp: string | null
}

export interface ServiceResource {
  name: string
  namespace: string
  type: string
  clusterIP: string | null
  ports: string[]
  selector: Record<string, string>
  creationTimestamp: string | null
}

export interface IngressRule {
  host: string
  path: string
  backend: string
}

export interface Ingress {
  name: string
  namespace: string
  className: string | null
  rules: IngressRule[]
  creationTimestamp: string | null
}

export interface Pvc {
  name: string
  namespace: string
  status: string
  volumeName: string | null
  capacity: string | null
  accessModes: string[]
  storageClass: string | null
  creationTimestamp: string | null
}

export interface Pv {
  name: string
  status: string
  capacity: string | null
  accessModes: string[]
  reclaimPolicy: string | null
  storageClass: string | null
  claimRef: string | null
  creationTimestamp: string | null
}

export interface K8sEvent {
  name: string
  namespace: string
  type: string
  reason: string | null
  message: string | null
  involvedObjectKind: string | null
  involvedObjectName: string | null
  count: number
  lastTimestamp: string | null
  firstTimestamp: string | null
}

export interface Hpa {
  name: string
  namespace: string
  targetRef: string
  minReplicas: number
  maxReplicas: number
  currentReplicas: number
  currentCpuPercent: number | null
  targetCpuPercent: number | null
  creationTimestamp: string | null
}

// ── Access Control (RBAC) — see AccessControlController ────────────────────────
// ServiceAccount/Role/RoleBinding are creatable/editable/deletable (generic resource
// endpoints); ClusterRole/ClusterRoleBinding are creatable/editable (cluster-scoped
// endpoints) but not deletable through this app yet. `systemManaged` is a UI-only
// hint (see backend RbacFilters) — pages default to hiding these rows.

export interface ServiceAccount {
  name: string
  namespace: string
  secretCount: number
  imagePullSecretCount: number
  automountToken: boolean | null
  creationTimestamp: string | null
  systemManaged: boolean
}

export interface RbacRule {
  apiGroups: string[]
  resources: string[]
  resourceNames: string[]
  verbs: string[]
}

export interface RbacSubject {
  kind: string
  name: string
  namespace: string | null
}

export interface Role {
  name: string
  namespace: string
  rules: RbacRule[]
  creationTimestamp: string | null
  systemManaged: boolean
}

/** Cluster-scoped. */
export interface ClusterRole {
  name: string
  rules: RbacRule[]
  creationTimestamp: string | null
  systemManaged: boolean
}

export interface RoleBinding {
  name: string
  namespace: string
  roleRefKind: string | null
  roleRefName: string | null
  subjects: RbacSubject[]
  creationTimestamp: string | null
  systemManaged: boolean
}

/** Cluster-scoped. */
export interface ClusterRoleBinding {
  name: string
  roleRefKind: string | null
  roleRefName: string | null
  subjects: RbacSubject[]
  creationTimestamp: string | null
  systemManaged: boolean
}

// ── Helm — see HelmReleaseController/HelmChartController/HelmRepoController ────
// Everything here shells out to the `helm` CLI on the backend (no Java Helm SDK
// exists) — same trust tier as the Cluster Terminal. Read (list/search) is open
// to any authenticated user; install/uninstall/repo add/remove are ADMIN-only.

export interface HelmRelease {
  name: string
  namespace: string
  revision: string
  updated: string
  status: string
  chart: string
  appVersion: string
}

export interface HelmReleaseDetail {
  values: string
  manifest: string
  notes: string
  /** Repository reference, when one could be resolved. No longer what gates editing. */
  chartRef: string | null
  /**
   * Whether there is any chart to re-apply with — normally the one Helm stored in
   * the cluster, so this is true even for releases installed from a terminal.
   * Charts whose subcharts Helm did not persist fall back to needing a chartRef.
   */
  valuesEditable: boolean
  /**
   * True while credentials in `values`/`manifest` are masked. Chart values carry
   * passwords and signing keys, and the manifest carries rendered Secrets — the
   * same data the Secrets page reveals only to an ADMIN, with an audit record.
   * Masked text is display-only: saving it back would write the mask over the
   * real credentials, so editing waits for a reveal.
   */
  masked: boolean
}

/**
 * One entry from a release's revision log. Unlike HelmRelease.revision this is
 * a number, because it is what a rollback is addressed to. Reading it needs no
 * chart reference — Helm keeps every revision in the cluster.
 */
export interface HelmRevision {
  revision: number
  updated: string
  status: string
  chart: string
  appVersion: string
  description: string
}

/**
 * What a chart repository buys you now. It used to be the price of editing a
 * release at all; it now only answers "is there a newer chart version?" —
 * everything else works without one.
 */
export interface HelmChartUpdate {
  chartRef: string | null
  currentVersion: string | null
  latestVersion: string | null
  updateAvailable: boolean
}

export interface HelmRepo {
  name: string
  url: string
}

export interface HelmChart {
  /** "repo/chart" */
  name: string
  version: string
  appVersion: string
  description: string
}
