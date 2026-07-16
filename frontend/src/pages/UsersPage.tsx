import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { ErrorBanner } from '../components/ErrorBanner'
import { Modal } from '../components/Modal'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { useToast } from '../components/Toast'
import { useAuth } from '../auth/AuthContext'
import type { AppUser } from '../types/k8s'

const COLUMNS = [
  { key: 'username', label: 'Username' },
  { key: 'role', label: 'Role' },
  { key: 'actions', label: '' },
]

export function UsersPage() {
  const toast = useToast()
  const queryClient = useQueryClient()
  const { username: currentUsername } = useAuth()
  const [addOpen, setAddOpen] = useState(false)
  const [deleting, setDeleting] = useState<AppUser | null>(null)
  const [resetting, setResetting] = useState<AppUser | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER')
  const [newPassword, setNewPassword] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const { data, isLoading, isError, error } = useQuery<AppUser[]>({
    queryKey: ['users'],
    queryFn: async () => (await api.get<AppUser[]>('/users')).data,
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['users'] })

  const addMutation = useMutation({
    mutationFn: async () =>
      (await api.post<AppUser>('/users', { username: username.trim(), password, role })).data,
    onSuccess: (u) => {
      toast.success(`User ${u.username} created`)
      setAddOpen(false)
      setUsername('')
      setPassword('')
      setRole('USER')
      setFormError(null)
      invalidate()
    },
    onError: (e) => setFormError(apiErrorMessage(e, 'Could not create user')),
  })

  const resetMutation = useMutation({
    mutationFn: async () => {
      if (!resetting) return
      await api.put(`/users/${resetting.id}/password`, { password: newPassword })
    },
    onSuccess: () => {
      toast.success(`Password reset for ${resetting?.username}`)
      setResetting(null)
      setNewPassword('')
      setFormError(null)
    },
    onError: (e) => setFormError(apiErrorMessage(e, 'Could not reset password')),
  })

  const deleteUser = async () => {
    if (!deleting) return
    try {
      await api.delete(`/users/${deleting.id}`)
      toast.success(`User ${deleting.username} deleted`)
      setDeleting(null)
      invalidate()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
  }

  return (
    <Layout>
      <PageHeader
        title="Users"
        subtitle="KubeMind logins. USER accounts are read-only: all write actions and terminals require ADMIN. Every cluster call runs under KubeMind's own credentials — per-user Kubernetes RBAC is on the roadmap."
        count={data?.length}
        noun="user"
        actions={
          <button
            onClick={() => { setFormError(null); setAddOpen(true) }}
            className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                       hover:bg-blue-700 transition-colors"
          >
            Add user
          </button>
        }
      />

      {isLoading && <p className="text-sm text-gray-400 dark:text-slate-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load users: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS} minWidth="480px">
          {data.map((u) => (
            <Tr key={u.id}>
              <Td className="font-medium text-gray-900 dark:text-slate-100">
                {u.username}
                {u.username === currentUsername && (
                  <span className="ml-2 rounded-full bg-gray-100 dark:bg-slate-700 px-2 py-0.5 text-[10px] font-medium text-gray-500 dark:text-slate-400 ring-1 ring-inset ring-gray-500/20 dark:ring-slate-500/30">
                    you
                  </span>
                )}
              </Td>
              <Td>
                <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset ${
                  u.role === 'ADMIN'
                    ? 'bg-violet-50 dark:bg-violet-500/10 text-violet-700 dark:text-violet-400 ring-violet-600/20'
                    : 'bg-gray-50 dark:bg-slate-800/60 text-gray-600 dark:text-slate-400 ring-gray-500/20 dark:ring-slate-500/30'
                }`}>
                  {u.role}
                </span>
              </Td>
              <Td>
                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => { setFormError(null); setNewPassword(''); setResetting(u) }}
                    className="rounded-md border border-gray-300 dark:border-slate-600 px-2.5 py-1 text-xs text-gray-600 dark:text-slate-400
                               hover:bg-gray-50 dark:hover:bg-slate-800 transition-colors"
                  >
                    Reset password
                  </button>
                  {u.username !== currentUsername && (
                    <button
                      onClick={() => setDeleting(u)}
                      className="rounded-md border border-red-200 dark:border-red-500/30 px-2.5 py-1 text-xs text-red-600 dark:text-red-400
                                 hover:bg-red-50 transition-colors"
                    >
                      Delete
                    </button>
                  )}
                </div>
              </Td>
            </Tr>
          ))}
        </Table>
      )}

      {/* ── Add user ─────────────────────────────────────────────────────── */}
      <Modal open={addOpen} title="Add user" onClose={() => setAddOpen(false)}>
        <div className="space-y-4">
          <div>
            <label className="block text-xs text-gray-500 dark:text-slate-400 mb-1.5">Username</label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="jane.doe"
              maxLength={64}
              autoComplete="off"
              className="w-full rounded-md border border-gray-300 dark:border-slate-600 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 dark:text-slate-400 mb-1.5">Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Min 8 characters"
              maxLength={128}
              autoComplete="new-password"
              className="w-full rounded-md border border-gray-300 dark:border-slate-600 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 dark:text-slate-400 mb-1.5">Role</label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as 'USER' | 'ADMIN')}
              className="w-full rounded-md border border-gray-300 dark:border-slate-600 px-3 py-2 text-sm bg-white dark:bg-slate-900
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            >
              <option value="USER">USER — read-only</option>
              <option value="ADMIN">ADMIN — full access (effectively cluster-admin)</option>
            </select>
            {role === 'ADMIN' && (
              <p className="mt-1.5 text-xs text-amber-600 dark:text-amber-400">
                ADMINs can use the terminals and every write action — treat this like handing
                out cluster-admin.
              </p>
            )}
          </div>
          {formError && <p className="text-sm text-red-600 dark:text-red-400">{formError}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setAddOpen(false)}
              disabled={addMutation.isPending}
              className="rounded-md border border-gray-300 dark:border-slate-600 px-3.5 py-2 text-sm text-gray-700 dark:text-slate-300
                         hover:bg-gray-50 dark:hover:bg-slate-800 disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={() => addMutation.mutate()}
              disabled={addMutation.isPending || !username.trim() || password.length < 8}
              className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                         hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {addMutation.isPending ? 'Creating…' : 'Create user'}
            </button>
          </div>
        </div>
      </Modal>

      {/* ── Reset password ───────────────────────────────────────────────── */}
      <Modal
        open={!!resetting}
        title={`Reset password for ${resetting?.username ?? ''}`}
        onClose={() => setResetting(null)}
      >
        <div className="space-y-4">
          <div>
            <label className="block text-xs text-gray-500 dark:text-slate-400 mb-1.5">New password</label>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="Min 8 characters"
              maxLength={128}
              autoComplete="new-password"
              className="w-full rounded-md border border-gray-300 dark:border-slate-600 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          {formError && <p className="text-sm text-red-600 dark:text-red-400">{formError}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setResetting(null)}
              disabled={resetMutation.isPending}
              className="rounded-md border border-gray-300 dark:border-slate-600 px-3.5 py-2 text-sm text-gray-700 dark:text-slate-300
                         hover:bg-gray-50 dark:hover:bg-slate-800 disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={() => resetMutation.mutate()}
              disabled={resetMutation.isPending || newPassword.length < 8}
              className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                         hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {resetMutation.isPending ? 'Saving…' : 'Reset password'}
            </button>
          </div>
        </div>
      </Modal>

      {/* ── Delete confirm ───────────────────────────────────────────────── */}
      <ConfirmDialog
        open={!!deleting}
        title={`Delete user ${deleting?.username ?? ''}`}
        message={
          <>
            The user will no longer be able to sign in. Their audit log entries are kept.
          </>
        }
        confirmLabel="Delete"
        danger
        onConfirm={deleteUser}
        onClose={() => setDeleting(null)}
      />
    </Layout>
  )
}
