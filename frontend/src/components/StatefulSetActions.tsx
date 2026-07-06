import { useQueryClient } from '@tanstack/react-query'
import { ScaleButton } from './ScaleButton'
import { RestartButton } from './RestartButton'
import { EditYamlButton } from './EditYamlButton'
import { DeleteResourceButton } from './DeleteResourceButton'
import type { StatefulSet } from '../types/k8s'

interface Props {
  clusterId: string
  namespace: string
  statefulSet: StatefulSet
  onActionDone?: () => void
}

export function StatefulSetActions({ clusterId, namespace, statefulSet, onActionDone }: Props) {
  const queryClient = useQueryClient()
  const base = `/clusters/${clusterId}/namespaces/${namespace}/statefulsets/${statefulSet.name}`

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['statefulsets', clusterId, namespace] })
    onActionDone?.()
  }

  return (
    <div className="flex flex-wrap gap-2">
      <ScaleButton
        endpoint={`${base}/scale`}
        resourceName={statefulSet.name}
        currentReplicas={statefulSet.desiredReplicas}
        readyReplicas={statefulSet.readyReplicas}
        onScaled={refresh}
      />
      <RestartButton endpoint={`${base}/restart`} resourceName={statefulSet.name} onRestarted={refresh} />
      <EditYamlButton clusterId={clusterId} ns={namespace} kind="StatefulSet" name={statefulSet.name} onApplied={refresh} />
      <DeleteResourceButton clusterId={clusterId} ns={namespace} kind="StatefulSet" name={statefulSet.name} onDeleted={refresh} />
    </div>
  )
}
