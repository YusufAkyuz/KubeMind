import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { username, loading } = useAuth()

  if (loading) {
    return <div className="p-8 text-gray-500 dark:text-slate-400">Loading…</div>
  }
  if (!username) {
    return <Navigate to="/login" replace />
  }
  return <>{children}</>
}
