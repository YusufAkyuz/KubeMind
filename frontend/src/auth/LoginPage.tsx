import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { useAuth } from './AuthContext'
import { useAppConfig } from '../hooks/useAppConfig'
import { Logo } from '../components/Icons'
import { LoginBackground } from './LoginBackground'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const { data: config } = useAppConfig()
  // The backend bounces the browser back here with this when the identity
  // provider can't be reached (SsoAvailabilityFilter) or the round trip failed
  // partway (oauth2Login's failureUrl). Both are recoverable by using the
  // password form below, so the message has to say that rather than just fail.
  const [searchParams] = useSearchParams()
  const ssoProblem = searchParams.get('sso')
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
        {ssoProblem && (
          <div className="rounded border border-amber-300 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10
                          px-3 py-2 text-sm text-amber-800 dark:text-amber-400">
            {ssoProblem === 'unavailable'
              ? "Couldn't reach your identity provider, so SSO is unavailable right now."
              : 'Signing in with SSO did not complete.'}{' '}
            Sign in with your username and password below, or try SSO again.
          </div>
        )}
        {config?.oidcEnabled && (
          <>
            {/* A plain link, not a fetch: Spring's oauth2Login() DSL owns this
                redirect, the code exchange, and the callback — see
                SecurityConfig/OidcClientConfig. Landing back on the SPA root
                afterward hits the same GET /auth/me AuthContext already runs
                on mount, same as a password login. */}
            <a
              href="/oauth2/authorization/oidc"
              className="flex w-full items-center justify-center gap-2 rounded border border-gray-300 dark:border-neutral-600
                         bg-white dark:bg-neutral-800 text-gray-700 dark:text-neutral-200 py-2 text-sm font-medium
                         hover:bg-gray-50 dark:hover:bg-neutral-700 transition-colors"
            >
              Sign in with SSO
            </a>
            <div className="flex items-center gap-3 text-xs text-gray-400 dark:text-neutral-500">
              <div className="h-px flex-1 bg-gray-200 dark:bg-neutral-700" />
              or
              <div className="h-px flex-1 bg-gray-200 dark:bg-neutral-700" />
            </div>
          </>
        )}
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
