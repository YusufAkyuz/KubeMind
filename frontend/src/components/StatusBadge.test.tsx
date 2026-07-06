import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it('renders the status text', () => {
    render(<StatusBadge status="Running" />)
    expect(screen.getByText('Running')).toBeInTheDocument()
  })

  it('uses the emerald (healthy) style for Running', () => {
    render(<StatusBadge status="Running" />)
    expect(screen.getByText('Running').className).toContain('emerald')
  })

  it('uses the red (degraded) style for Failed', () => {
    render(<StatusBadge status="Failed" />)
    expect(screen.getByText('Failed').className).toContain('red')
  })

  it('falls back to a neutral style for an unrecognized status', () => {
    render(<StatusBadge status="SomeWeirdPhase" />)
    expect(screen.getByText('SomeWeirdPhase').className).toContain('gray')
  })
})
