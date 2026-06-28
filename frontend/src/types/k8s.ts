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
