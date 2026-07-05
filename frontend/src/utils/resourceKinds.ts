/** Kinds the Create Resource feature supports — must mirror the backend allowlist. */
export const ALLOWED_KINDS = [
  'Pod', 'Deployment', 'StatefulSet', 'DaemonSet',
  'Service', 'Ingress', 'ConfigMap', 'Secret', 'PersistentVolumeClaim',
] as const

export type AllowedKind = (typeof ALLOWED_KINDS)[number]

/** Maps a kind to the URL segment of its list page, for the "+ Create" entry points and post-create redirect. */
export const KIND_ROUTES: Record<string, string> = {
  Pod: 'pods',
  Deployment: 'deployments',
  StatefulSet: 'statefulsets',
  DaemonSet: 'daemonsets',
  Service: 'services',
  Ingress: 'ingresses',
  ConfigMap: 'configmaps',
  Secret: 'secrets',
  PersistentVolumeClaim: 'persistentvolumeclaims',
}

/** Starter YAML skeletons shown when the editor is empty, before AI drafting. */
export const KIND_TEMPLATES: Record<AllowedKind, (ns: string) => string> = {
  Pod: (ns) => `apiVersion: v1
kind: Pod
metadata:
  name: my-pod
  namespace: ${ns}
spec:
  containers:
    - name: app
      image: nginx:1.27
      ports:
        - containerPort: 80
`,
  Deployment: (ns) => `apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-deployment
  namespace: ${ns}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: my-deployment
  template:
    metadata:
      labels:
        app: my-deployment
    spec:
      containers:
        - name: app
          image: nginx:1.27
          ports:
            - containerPort: 80
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 250m
              memory: 256Mi
`,
  StatefulSet: (ns) => `apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: my-statefulset
  namespace: ${ns}
spec:
  serviceName: my-statefulset
  replicas: 1
  selector:
    matchLabels:
      app: my-statefulset
  template:
    metadata:
      labels:
        app: my-statefulset
    spec:
      containers:
        - name: app
          image: nginx:1.27
          ports:
            - containerPort: 80
`,
  DaemonSet: (ns) => `apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: my-daemonset
  namespace: ${ns}
spec:
  selector:
    matchLabels:
      app: my-daemonset
  template:
    metadata:
      labels:
        app: my-daemonset
    spec:
      containers:
        - name: app
          image: nginx:1.27
`,
  Service: (ns) => `apiVersion: v1
kind: Service
metadata:
  name: my-service
  namespace: ${ns}
spec:
  type: ClusterIP
  selector:
    app: my-deployment
  ports:
    - port: 80
      targetPort: 80
`,
  Ingress: (ns) => `apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-ingress
  namespace: ${ns}
spec:
  rules:
    - host: example.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: my-service
                port:
                  number: 80
`,
  ConfigMap: (ns) => `apiVersion: v1
kind: ConfigMap
metadata:
  name: my-config
  namespace: ${ns}
data:
  key: value
`,
  Secret: (ns) => `apiVersion: v1
kind: Secret
metadata:
  name: my-secret
  namespace: ${ns}
type: Opaque
stringData:
  key: value
`,
  PersistentVolumeClaim: (ns) => `apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: my-claim
  namespace: ${ns}
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
`,
}
