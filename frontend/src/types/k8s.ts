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
  /** Null when this release wasn't installed through KubeMind — editing values is disabled then. */
  chartRef: string | null
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
