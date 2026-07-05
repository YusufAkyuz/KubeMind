import { useQueryClient } from '@tanstack/react-query'
import { RestartButton } from './RestartButton'
import { EditYamlButton } from './EditYamlButton'
import type { DaemonSet } from '../types/k8s'

interface Props {
  clusterId: string
  namespace: string
  daemonSet: DaemonSet
  onActionDone?: () => void
}

// DaemonSets run one pod per matching node — there's no user-settable replica
// count, so no Scale action (unlike Deployment/StatefulSet).
export function DaemonSetActions({ clusterId, namespace, daemonSet, onActionDone }: Props) {
  const queryClient = useQueryClient()
  const base = `/clusters/${clusterId}/namespaces/${namespace}/daemonsets/${daemonSet.name}`

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['daemonsets', clusterId, namespace] })
    onActionDone?.()
  }

  return (
    <div className="flex flex-wrap gap-2">
      <RestartButton endpoint={`${base}/restart`} resourceName={daemonSet.name} onRestarted={refresh} />
      <EditYamlButton clusterId={clusterId} ns={namespace} kind="DaemonSet" name={daemonSet.name} onApplied={refresh} />
    </div>
  )
}
