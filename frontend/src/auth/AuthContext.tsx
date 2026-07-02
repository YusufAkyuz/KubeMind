import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'

interface MeResponse {
  username: string
  role: string
}

interface AuthState {
  username: string | null
  role: string | null
  isAdmin: boolean
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(null)
  const [role, setRole] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api
      .get<MeResponse>('/auth/me')
      .then((res) => {
        setUsername(res.data.username)
        setRole(res.data.role)
      })
      .catch(() => {
        setUsername(null)
        setRole(null)
      })
      .finally(() => setLoading(false))
  }, [])

  const login = async (user: string, password: string) => {
    const res = await api.post<MeResponse>('/auth/login', {
      username: user,
      password,
    })
    setUsername(res.data.username)
    setRole(res.data.role)
  }

  const logout = async () => {
    await api.post('/auth/logout')
    setUsername(null)
    setRole(null)
  }

  return (
    <AuthContext.Provider
      value={{ username, role, isAdmin: role === 'ADMIN', loading, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return ctx
}
