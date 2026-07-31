import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { useAuth } from './AuthContext'
import { Logo } from '../components/Icons'
import { LoginBackground } from './LoginBackground'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(username, password)
      navigate('/')
    } catch (e) {
      // Show what the server actually said. Blanket-labelling every failure
      // "Invalid username or password" sent people off resetting a password
      // when the real cause was server-side (see ApiExceptionHandler's 503).
      setError(apiErrorMessage(e, 'Could not sign in — is the server reachable?'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="relative min-h-screen flex items-center justify-center bg-neutral-950 px-4 overflow-hidden">
      <LoginBackground />
      <form
        onSubmit={onSubmit}
        className="relative w-full max-w-sm bg-white dark:bg-neutral-900 p-8 rounded-2xl shadow-2xl space-y-4"
      >
        <div className="flex items-center gap-3">
          <Logo className="w-9 h-9" />
          <div>
            <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-neutral-100">KubeMind</h1>
            <p className="text-xs text-gray-400 dark:text-neutral-500">AI-assisted Kubernetes dashboard</p>
          </div>
        </div>
        {error && <div className="text-sm text-red-600 dark:text-red-400">{error}</div>}
        <input
          className="w-full border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                     text-gray-900 dark:text-neutral-100 rounded px-3 py-2"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="Username"
          autoComplete="username"
        />
        <input
          type="password"
          className="w-full border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                     text-gray-900 dark:text-neutral-100 rounded px-3 py-2"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="Password"
          autoComplete="current-password"
        />
        <button
          type="submit"
          disabled={submitting}
          className="w-full bg-blue-600 text-white rounded py-2 hover:bg-blue-700 disabled:opacity-50"
        >
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
