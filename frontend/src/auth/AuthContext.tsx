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
    const res = await api.post<{ logoutUrl?: string }>('/auth/logout')
    setUsername(null)
    setRole(null)
    // An SSO session also has to be ended at the identity provider, or its
    // cookie survives and the next "Sign in with SSO" click silently lands back
    // in this account. The backend hands back where to go (see SecurityConfig);
    // a full navigation, not a fetch, because the IdP has to see the browser.
    if (res.data?.logoutUrl) {
      window.location.href = res.data.logoutUrl
    }
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
