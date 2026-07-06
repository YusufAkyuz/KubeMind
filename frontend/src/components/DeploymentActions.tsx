import { useQueryClient } from '@tanstack/react-query'
import { ScaleButton } from './ScaleButton'
import { RestartButton } from './RestartButton'
import { EditYamlButton } from './EditYamlButton'
import { DeleteResourceButton } from './DeleteResourceButton'
import type { Deployment } from '../types/k8s'

interface Props {
  clusterId: string
  namespace: string
  deployment: Deployment
  onActionDone?: () => void
}

export function DeploymentActions({ clusterId, namespace, deployment, onActionDone }: Props) {
  const queryClient = useQueryClient()
  const base = `/clusters/${clusterId}/namespaces/${namespace}/deployments/${deployment.name}`

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['deployments', clusterId, namespace] })
    onActionDone?.()
  }

  return (
    <div className="flex flex-wrap gap-2">
      <ScaleButton
        endpoint={`${base}/scale`}
        resourceName={deployment.name}
        currentReplicas={deployment.desiredReplicas}
        readyReplicas={deployment.readyReplicas}
        onScaled={refresh}
      />
      <RestartButton endpoint={`${base}/restart`} resourceName={deployment.name} onRestarted={refresh} />
      <EditYamlButton clusterId={clusterId} ns={namespace} kind="Deployment" name={deployment.name} onApplied={refresh} />
      <DeleteResourceButton clusterId={clusterId} ns={namespace} kind="Deployment" name={deployment.name} onDeleted={refresh} />
    </div>
  )
}
