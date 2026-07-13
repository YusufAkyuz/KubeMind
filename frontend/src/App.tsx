import { lazy, Suspense, useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from './auth/LoginPage'
import { ProtectedRoute } from './components/ProtectedRoute'
import { HomeRedirect } from './components/HomeRedirect'
import { NodesPage } from './pages/NodesPage'
import { NamespacesPage } from './pages/NamespacesPage'
import { PodsPage } from './pages/PodsPage'
import { DeploymentsPage } from './pages/DeploymentsPage'
import { EventsPage } from './pages/EventsPage'
import { LogsPage } from './pages/LogsPage'
import { AuditPage } from './pages/AuditPage'
import { ClustersPage } from './pages/ClustersPage'
import { UsersPage } from './pages/UsersPage'
import { ConfigMapsPage } from './pages/ConfigMapsPage'
import { SecretsPage } from './pages/SecretsPage'
import { StatefulSetsPage } from './pages/StatefulSetsPage'
import { DaemonSetsPage } from './pages/DaemonSetsPage'
import { JobsPage } from './pages/JobsPage'
import { CronJobsPage } from './pages/CronJobsPage'
import { HpasPage } from './pages/HpasPage'
import { ServicesPage } from './pages/ServicesPage'
import { IngressesPage } from './pages/IngressesPage'
import { PvcsPage } from './pages/PvcsPage'
import { PvsPage } from './pages/PvsPage'
import { CreateResourcePage } from './pages/CreateResourcePage'
import { CreateClusterResourcePage } from './pages/CreateClusterResourcePage'
import { RunbooksPage } from './pages/RunbooksPage'
import { ServiceAccountsPage } from './pages/ServiceAccountsPage'
import { RolesPage } from './pages/RolesPage'
import { RoleBindingsPage } from './pages/RoleBindingsPage'
import { ClusterRolesPage } from './pages/ClusterRolesPage'
import { ClusterRoleBindingsPage } from './pages/ClusterRoleBindingsPage'
import { HelmReleasesPage } from './pages/HelmReleasesPage'
import { HelmChartsPage } from './pages/HelmChartsPage'
import { ChatWidget } from './components/ChatWidget'
import { TerminalPanelProvider, useTerminalPanel } from './terminal/TerminalPanelContext'
import { ChatPanelProvider } from './chat/ChatPanelContext'
import { RightReserveProvider } from './layout/RightReserveContext'

// Code-split: xterm.js only loads once a terminal session is actually opened.
const TerminalPanel = lazy(() => import('./terminal/TerminalPanel').then((m) => ({ default: m.TerminalPanel })))

/** Mounts the (lazy) terminal panel only after the first session is opened, and keeps
 *  it mounted afterwards so closing/reopening never re-triggers the xterm.js chunk load. */
function TerminalPanelHost() {
  const { sessions } = useTerminalPanel()
  const [everOpened, setEverOpened] = useState(false)
  useEffect(() => { if (sessions.length > 0) setEverOpened(true) }, [sessions.length])
  if (!everOpened) return null
  return <Suspense fallback={null}><TerminalPanel /></Suspense>
}

export default function App() {
  return (
    <TerminalPanelProvider>
    <RightReserveProvider>
    <ChatPanelProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        {/* Default: first usable cluster, or the Clusters page if none yet (see HomeRedirect) */}
        <Route path="/" element={<ProtectedRoute><HomeRedirect /></ProtectedRoute>} />

        {/* Cluster-scoped */}
        <Route path="/clusters/:clusterId/nodes" element={<ProtectedRoute><NodesPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces" element={<ProtectedRoute><NamespacesPage /></ProtectedRoute>} />

        {/* Namespace-scoped */}
        <Route path="/clusters/:clusterId/namespaces/:ns/pods" element={<ProtectedRoute><PodsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/deployments" element={<ProtectedRoute><DeploymentsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/events" element={<ProtectedRoute><EventsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/statefulsets" element={<ProtectedRoute><StatefulSetsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/daemonsets" element={<ProtectedRoute><DaemonSetsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/jobs" element={<ProtectedRoute><JobsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/cronjobs" element={<ProtectedRoute><CronJobsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/hpas" element={<ProtectedRoute><HpasPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/configmaps" element={<ProtectedRoute><ConfigMapsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/secrets" element={<ProtectedRoute><SecretsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/services" element={<ProtectedRoute><ServicesPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/ingresses" element={<ProtectedRoute><IngressesPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/persistentvolumeclaims" element={<ProtectedRoute><PvcsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/persistentvolumes" element={<ProtectedRoute><PvsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/create" element={<ProtectedRoute><CreateResourcePage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/pods/:pod/logs" element={<ProtectedRoute><LogsPage /></ProtectedRoute>} />

        {/* Access Control (RBAC) — read-only */}
        <Route path="/clusters/:clusterId/namespaces/:ns/serviceaccounts" element={<ProtectedRoute><ServiceAccountsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/roles" element={<ProtectedRoute><RolesPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/rolebindings" element={<ProtectedRoute><RoleBindingsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/clusterroles" element={<ProtectedRoute><ClusterRolesPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/clusterrolebindings" element={<ProtectedRoute><ClusterRoleBindingsPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/create" element={<ProtectedRoute><CreateClusterResourcePage /></ProtectedRoute>} />

        {/* Helm */}
        <Route path="/clusters/:clusterId/namespaces/:ns/helm/releases" element={<ProtectedRoute><HelmReleasesPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/namespaces/:ns/helm/charts" element={<ProtectedRoute><HelmChartsPage /></ProtectedRoute>} />

        {/* Admin */}
        <Route path="/settings/clusters" element={<ProtectedRoute><ClustersPage /></ProtectedRoute>} />
        <Route path="/settings/users" element={<ProtectedRoute><UsersPage /></ProtectedRoute>} />
        <Route path="/audit" element={<ProtectedRoute><AuditPage /></ProtectedRoute>} />
        <Route path="/clusters/:clusterId/runbooks" element={<ProtectedRoute><RunbooksPage /></ProtectedRoute>} />
      </Routes>
      <ChatWidget />
      <TerminalPanelHost />
    </ChatPanelProvider>
    </RightReserveProvider>
    </TerminalPanelProvider>
  )
}
