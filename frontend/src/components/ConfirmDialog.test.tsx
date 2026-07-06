import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfirmDialog } from './ConfirmDialog'

describe('ConfirmDialog', () => {
  it('disables confirm until the required text is typed exactly', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn().mockResolvedValue(undefined)

    render(
      <ConfirmDialog
        open
        title="Delete pod"
        message="This cannot be undone."
        confirmLabel="Delete"
        danger
        requireText="my-pod"
        onConfirm={onConfirm}
        onClose={() => {}}
      />
    )

    const confirmButton = screen.getByRole('button', { name: 'Delete' })
    expect(confirmButton).toBeDisabled()

    await user.type(screen.getByRole('textbox'), 'wrong-name')
    expect(confirmButton).toBeDisabled()

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'my-pod')
    expect(confirmButton).toBeEnabled()

    await user.click(confirmButton)
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('enables confirm immediately when no requireText is given', () => {
    render(
      <ConfirmDialog
        open
        title="Restart deployment"
        message="Rolling restart."
        confirmLabel="Restart"
        onConfirm={vi.fn()}
        onClose={() => {}}
      />
    )
    expect(screen.getByRole('button', { name: 'Restart' })).toBeEnabled()
  })

  it('renders nothing when closed', () => {
    render(
      <ConfirmDialog
        open={false}
        title="Delete pod"
        message="This cannot be undone."
        confirmLabel="Delete"
        onConfirm={vi.fn()}
        onClose={() => {}}
      />
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
