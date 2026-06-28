import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from './auth/LoginPage'
import { ProtectedRoute } from './components/ProtectedRoute'
import { NodesPage } from './pages/NodesPage'
import { NamespacesPage } from './pages/NamespacesPage'
import { PodsPage } from './pages/PodsPage'
import { DeploymentsPage } from './pages/DeploymentsPage'
import { EventsPage } from './pages/EventsPage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      {/* Default redirect */}
      <Route path="/" element={<ProtectedRoute><Navigate to="/nodes" replace /></ProtectedRoute>} />

      {/* Cluster-scoped */}
      <Route path="/nodes" element={<ProtectedRoute><NodesPage /></ProtectedRoute>} />
      <Route path="/namespaces" element={<ProtectedRoute><NamespacesPage /></ProtectedRoute>} />

      {/* Namespace-scoped */}
      <Route path="/namespaces/:ns/pods" element={<ProtectedRoute><PodsPage /></ProtectedRoute>} />
      <Route path="/namespaces/:ns/deployments" element={<ProtectedRoute><DeploymentsPage /></ProtectedRoute>} />
      <Route path="/namespaces/:ns/events" element={<ProtectedRoute><EventsPage /></ProtectedRoute>} />
    </Routes>
  )
}
