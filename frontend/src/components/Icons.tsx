import { useId } from 'react'

interface IconProps {
  className?: string
}

/**
 * KubeMind brand mark: gradient tile with a connected-nodes glyph
 * (control plane + workloads). useId keeps gradient ids unique when
 * the logo renders more than once on a page.
 */
export function Logo({ className = 'w-7 h-7' }: IconProps) {
  const id = useId()
  return (
    <svg className={className} viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#3b82f6" />
          <stop offset="1" stopColor="#8b5cf6" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="8" fill={`url(#${id})`} />
      <g stroke="white" strokeWidth="1.6" strokeLinecap="round">
        <line x1="16" y1="16" x2="16" y2="8.5" />
        <line x1="16" y1="16" x2="9.5" y2="21.5" />
        <line x1="16" y1="16" x2="22.5" y2="21.5" />
      </g>
      <circle cx="16" cy="16" r="3" fill="white" />
      <circle cx="16" cy="8" r="2.1" fill="white" opacity="0.9" />
      <circle cx="9" cy="22" r="2.1" fill="white" opacity="0.9" />
      <circle cx="23" cy="22" r="2.1" fill="white" opacity="0.9" />
    </svg>
  )
}

export function IconServer({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="2" width="20" height="8" rx="2" />
      <rect x="2" y="14" width="20" height="8" rx="2" />
      <circle cx="6" cy="6" r="1" fill="currentColor" stroke="none" />
      <circle cx="6" cy="18" r="1" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function IconFolder({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7Z" />
    </svg>
  )
}

export function IconCube({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 16V8a2 2 0 0 0-1-1.73L13 2.27a2 2 0 0 0-2 0L4 6.27A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
      <path d="M3.29 7 12 12l8.71-5" />
      <line x1="12" y1="22" x2="12" y2="12" />
    </svg>
  )
}

export function IconLayers({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <polygon points="12 2 2 7 12 12 22 7 12 2" />
      <polyline points="2 17 12 22 22 17" />
      <polyline points="2 12 12 17 22 12" />
    </svg>
  )
}

export function IconClipboard({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2" />
      <rect x="9" y="3" width="6" height="4" rx="1" />
      <line x1="9" y1="12" x2="15" y2="12" />
      <line x1="9" y1="16" x2="13" y2="16" />
    </svg>
  )
}

export function IconTerminal({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <polyline points="4 17 10 11 4 5" />
      <line x1="12" y1="19" x2="20" y2="19" />
    </svg>
  )
}

export function IconMenu({ className = 'w-5 h-5' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <line x1="4" y1="6" x2="20" y2="6" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="18" x2="20" y2="18" />
    </svg>
  )
}

export function IconX({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  )
}

export function IconSearch({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="7" />
      <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
  )
}

export function IconChevronRight({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <polyline points="9 18 15 12 9 6" />
    </svg>
  )
}

export function IconRefresh({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M21.5 2v6h-6" />
      <path d="M21.34 15.57a10 10 0 1 1-.57-8.38" />
    </svg>
  )
}

export function IconArrowDown({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <line x1="12" y1="5" x2="12" y2="19" />
      <polyline points="19 12 12 19 5 12" />
    </svg>
  )
}

export function IconSliders({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <line x1="4" y1="21" x2="4" y2="14" /><line x1="4" y1="10" x2="4" y2="3" />
      <line x1="12" y1="21" x2="12" y2="12" /><line x1="12" y1="8" x2="12" y2="3" />
      <line x1="20" y1="21" x2="20" y2="16" /><line x1="20" y1="12" x2="20" y2="3" />
      <line x1="1" y1="14" x2="7" y2="14" /><line x1="9" y1="8" x2="15" y2="8" /><line x1="17" y1="16" x2="23" y2="16" />
    </svg>
  )
}

export function IconNetwork({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <rect x="9" y="2" width="6" height="6" rx="1" />
      <rect x="2" y="16" width="6" height="6" rx="1" /><rect x="16" y="16" width="6" height="6" rx="1" />
      <path d="M12 8v4M12 12H5v4M12 12h7v4" />
    </svg>
  )
}

export function IconDatabase({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <ellipse cx="12" cy="5" rx="8" ry="3" />
      <path d="M4 5v6c0 1.66 3.58 3 8 3s8-1.34 8-3V5" />
      <path d="M4 11v6c0 1.66 3.58 3 8 3s8-1.34 8-3v-6" />
    </svg>
  )
}

export function IconPlus({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  )
}

export function IconHistory({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 3v5h5" />
      <path d="M3.05 13A9 9 0 1 0 6 5.3L3 8" />
      <path d="M12 7v5l4 2" />
    </svg>
  )
}

/** Indeterminate progress ring. The gap in the arc is what makes the spin readable. */
export function IconSpinner({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={`${className} animate-spin`} viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth={2.5} opacity={0.25} />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" />
    </svg>
  )
}

export function IconPencil({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </svg>
  )
}

export function IconTrash({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6h18" />
      <path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    </svg>
  )
}

export function IconAlertTriangle({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  )
}

export function IconSparkles({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor">
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456ZM16.894 20.567 16.5 21.75l-.394-1.183a2.25 2.25 0 0 0-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 0 0 1.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 0 0 1.423 1.423l1.183.394-1.183.394a2.25 2.25 0 0 0-1.423 1.423Z"
      />
    </svg>
  )
}

export function IconChevronDown({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

/** Kubernetes helm-wheel logo mark */
export function IconHelm({ className = 'w-5 h-5' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 304 351" xmlns="http://www.w3.org/2000/svg">
      <mask id="helm-mask-a" fill="#fff">
        <path d="m0 0h313.303155v159.864865h-313.303155z" fillRule="evenodd" />
      </mask>
      <mask id="helm-mask-b" fill="#fff">
        <path d="m0 0h313.303155v159.864865h-313.303155z" fillRule="evenodd" />
      </mask>
      <g fill="none" fillRule="evenodd" transform="translate(-11 -51)">
        <path
          fill="currentColor"
          d="m11.6785714 189h19.7858357v26.789h23.9037v-26.789h19.7858358v75.25h-19.7858358v-28.695333h-23.9037v28.695333h-19.7858357zm86.1738429 75.25v-75.25h46.8030427v16.354333h-27.017207v12.240667h23.9037v16.655333h-23.9037v13.846h27.017207v16.153667zm68.4971567 0v-75.25h19.785836v55.384h27.117643v19.866zm77.536372-75.25 30.733328 27.892667 30.632893-27.892667h8.938779v75.25h-19.886272v-38.628333l-19.6854 17.959666-19.785835-17.859333v38.528h-19.886272v-75.25z"
        />
        <g transform="matrix(1 0 0 -1 11.958136 455)">
          <g fill="currentColor" mask="url(#helm-mask-a)">
            <path transform="matrix(.81915204 .57357644 -.57357644 .81915204 111.870091 -51.706556)" d="m203.460676 95.6875425c6.93631 0 12.559301-14.8092194 12.559301-33.0773172s-5.622991-33.0773172-12.559301-33.0773172c-6.936311 0-12.559301 14.8092194-12.559301 33.0773172s5.62299 33.0773172 12.559301 33.0773172z" />
            <path transform="matrix(-.81915204 .57357644 .57357644 .81915204 58.084611 47.704768)" d="m30.1423223 95.6875425c6.9363104 0 12.559301-14.8092194 12.559301-33.0773172s-5.6229906-33.0773172-12.559301-33.0773172-12.5593009 14.8092194-12.5593009 33.0773172 5.6229905 33.0773172 12.5593009 33.0773172z" />
            <path transform="matrix(-1 0 0 1 272.628524 53.670762)" d="m116.732815 66.2752676c6.936311 0 12.559301-14.8092193 12.559301-33.0773172 0-18.2680978-5.62299-33.07731713-12.559301-33.07731713-6.93631 0-12.559301 14.80921933-12.559301 33.07731713 0 18.2680979 5.622991 33.0773172 12.559301 33.0773172z" />
          </g>
          <path
            fill="none"
            stroke="currentColor"
            strokeWidth={20}
            mask="url(#helm-mask-a)"
            d="m251.467006 173.099849c-20.230076-33.609969-56.889565-56.067908-98.755776-56.067908-40.720798 0-76.5158766 21.245901-97.0586959 53.334588m2.1981107 129.169534c20.8403036 30.232701 55.5559042 50.026591 94.8605852 50.026591 39.376099 0 74.146424-19.865887 94.974049-50.191495"
          />
        </g>
        <g transform="translate(11.958136)">
          <g fill="currentColor" mask="url(#helm-mask-b)">
            <path transform="matrix(.81915204 .57357644 -.57357644 .81915204 111.870091 -54.166016)" d="m203.460676 95.6875425c6.93631 0 12.559301-14.8092194 12.559301-33.0773172s-5.622991-33.0773172-12.559301-33.0773172c-6.936311 0-12.559301 14.8092194-12.559301 33.0773172s5.62299 33.0773172 12.559301 33.0773172z" />
            <path transform="matrix(-.81915204 .57357644 .57357644 .81915204 58.084611 45.245308)" d="m30.1423223 95.6875425c6.9363104 0 12.559301-14.8092194 12.559301-33.0773172s-5.6229906-33.0773172-12.559301-33.0773172-12.5593009 14.8092194-12.5593009 33.0773172 5.6229905 33.0773172 12.5593009 33.0773172z" />
            <path transform="matrix(-1 0 0 1 272.628524 51.211302)" d="m116.732815 66.2752676c6.936311 0 12.559301-14.8092193 12.559301-33.0773172 0-18.2680978-5.62299-33.07731713-12.559301-33.07731713-6.93631 0-12.559301 14.80921933-12.559301 33.07731713 0 18.2680979 5.622991 33.0773172 12.559301 33.0773172z" />
          </g>
          <path
            fill="none"
            stroke="currentColor"
            strokeWidth={20}
            mask="url(#helm-mask-b)"
            d="m251.467006 170.64039c-20.230076-33.609969-56.889565-56.067908-98.755776-56.067908-40.720798 0-76.5158766 21.2459-97.0586959 53.334587m2.1981107 129.169534c20.8403036 30.232702 55.5559042 50.026591 94.8605852 50.026591 39.376099 0 74.146424-19.865886 94.974049-50.191494"
          />
        </g>
      </g>
    </svg>
  )
}

export function IconThumbUp({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 22V11M2 13v7a2 2 0 0 0 2 2h12.5a2 2 0 0 0 2-1.6l1.3-6.5a2 2 0 0 0-2-2.4H14V4.5a2.5 2.5 0 0 0-5 0V6a4 4 0 0 1-2 3.5L7 11" />
    </svg>
  )
}

export function IconThumbDown({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M17 2v11M22 11V4a2 2 0 0 0-2-2H7.5a2 2 0 0 0-2 1.6L4.2 10.1a2 2 0 0 0 2 2.4H10v6.6a2.5 2.5 0 0 0 5 0V18a4 4 0 0 1 2-3.5L17 13" />
    </svg>
  )
}

export function IconShield({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3Z" />
    </svg>
  )
}

export function IconDownload({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3v12m0 0l-4-4m4 4l4-4M4 19h16" />
    </svg>
  )
}

export function IconUsers({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  )
}

export function IconSun({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  )
}

export function IconMoon({ className = 'w-4 h-4' }: IconProps) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z" />
    </svg>
  )
}
