interface Props {
  checked: boolean
  onChange: (checked: boolean) => void
  hiddenCount: number
}

/** Checkbox shown on Access Control pages to reveal the Kubernetes-bootstrapped
 *  rows (system:*, default ServiceAccounts, kube-system namespace, ...) that are
 *  hidden by default — see backend RbacFilters for how systemManaged is computed. */
export function SystemManagedToggle({ checked, onChange, hiddenCount }: Props) {
  if (!checked && hiddenCount === 0) return null

  return (
    <label className="flex items-center gap-2 text-xs text-gray-500 dark:text-slate-400 select-none cursor-pointer">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="rounded border-gray-300 dark:border-slate-600 text-blue-600 dark:text-blue-400 focus:ring-blue-500"
      />
      {checked ? 'Showing system-managed' : `Show system-managed (${hiddenCount} hidden)`}
    </label>
  )
}
