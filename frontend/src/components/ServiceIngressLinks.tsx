import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { IconExternalLink } from './Icons'
import type { ServiceIngressLink } from '../types/k8s'

interface Props {
  clusterId: string
  ns: string
  name: string
}

/**
 * "Open" links for a Service that an Ingress already exposes. The real URL,
 * on its own origin, is the healthy way to use such an app; Forward's tunnel
 * is for services nothing exposes. An optional extra next to the drawer's
 * actions, so it renders nothing while loading, when the lookup fails, or when
 * no Ingress routes here — Forward is still there either way.
 */
export function ServiceIngressLinks({ clusterId, ns, name }: Props) {
  const { data } = useQuery<ServiceIngressLink[]>({
    queryKey: ['service-ingress-links', clusterId, ns, name],
    queryFn: async () =>
      (await api.get<ServiceIngressLink[]>(
        `/clusters/${clusterId}/namespaces/${ns}/services/${name}/ingress-links`)).data,
  })

  // The backend only builds http(s) URLs; checked again here because this
  // string lands in an href.
  const links = (data ?? []).filter((l) => /^https?:\/\//i.test(l.url))
  if (links.length === 0) return null

  return (
    <div className="pb-3 flex flex-wrap gap-2">
      {links.map((link) => (
        <a
          key={link.url}
          href={link.url}
          target="_blank"
          rel="noopener noreferrer"
          title={`Open through Ingress "${link.ingress}"`}
          className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 dark:border-neutral-600 px-3 py-1.5 text-xs font-medium
                     text-gray-700 dark:text-neutral-300 hover:bg-gray-50 dark:hover:bg-neutral-800 transition-colors"
        >
          <IconExternalLink className="w-3.5 h-3.5" />
          Open {label(link.url)}
        </a>
      ))}
    </div>
  )
}

function label(url: string): string {
  const { host, pathname } = new URL(url)
  return pathname === '/' ? host : host + pathname
}
