import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from './auth/LoginPage'
import { ProtectedRoute } from './components/ProtectedRoute'
import { NodesPage } from './pages/NodesPage'
import { NamespacesPage } from './pages/NamespacesPage'
import { PodsPage } from './pages/PodsPage'
import { DeploymentsPage } from './pages/DeploymentsPage'
import { EventsPage } from './pages/EventsPage'
import { LogsPage } from './pages/LogsPage'
import { AuditPage } from './pages/AuditPage'
import { ClustersPage } from './pages/ClustersPage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      {/* Default: the built-in local cluster */}
      <Route path="/" element={<ProtectedRoute><Navigate to="/clusters/0/nodes" replace /></ProtectedRoute>} />

      {/* Cluster-scoped */}
      <Route path="/clusters/:clusterId/nodes" element={<ProtectedRoute><NodesPage /></ProtectedRoute>} />
      <Route path="/clusters/:clusterId/namespaces" element={<ProtectedRoute><NamespacesPage /></ProtectedRoute>} />

      {/* Namespace-scoped */}
      <Route path="/clusters/:clusterId/namespaces/:ns/pods" element={<ProtectedRoute><PodsPage /></ProtectedRoute>} />
      <Route path="/clusters/:clusterId/namespaces/:ns/deployments" element={<ProtectedRoute><DeploymentsPage /></ProtectedRoute>} />
      <Route path="/clusters/:clusterId/namespaces/:ns/events" element={<ProtectedRoute><EventsPage /></ProtectedRoute>} />
      <Route path="/clusters/:clusterId/namespaces/:ns/pods/:pod/logs" element={<ProtectedRoute><LogsPage /></ProtectedRoute>} />

      {/* Admin */}
      <Route path="/settings/clusters" element={<ProtectedRoute><ClustersPage /></ProtectedRoute>} />
      <Route path="/audit" element={<ProtectedRoute><AuditPage /></ProtectedRoute>} />
    </Routes>
  )
}
