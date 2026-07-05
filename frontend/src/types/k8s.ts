export interface Cluster {
  id: number
  name: string
  builtIn: boolean
  createdBy: string | null
  createdAt: string | null
  lastCheckedAt: string | null
  lastCheckOk: boolean | null
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

export interface ContainerInfo {
  name: string
  image: string
  ready: boolean
  restartCount: number
}

export interface Pod {
  name: string
  namespace: string
  phase: string
  nodeName: string | null
  restartCount: number
  podIP: string | null
  creationTimestamp: string | null
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
