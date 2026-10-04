import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ErrorBoundary } from '@/components/ui/ErrorBoundary'

function Boom(): JSX.Element {
  throw new Error('kaboom from render')
}

describe('ErrorBoundary', () => {
  it('renders a recovery fallback instead of leaving the route blank', () => {
    // React logs the caught error; keep the test output clean.
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('kaboom from render')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload page' })).toBeInTheDocument()
    expect(spy).toHaveBeenCalled()

    spy.mockRestore()
  })
})
