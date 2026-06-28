import { Routes, Route } from 'react-router-dom'
import { LoginPage } from './auth/LoginPage'
import { NamespacesPage } from './pages/NamespacesPage'
import { ProtectedRoute } from './components/ProtectedRoute'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <NamespacesPage />
          </ProtectedRoute>
        }
      />
    </Routes>
  )
}
