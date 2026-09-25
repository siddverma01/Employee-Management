import { beforeEach, describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { Avatar } from '@/components/ui/Avatar'
import { EmptyState } from '@/components/ui/EmptyState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Pagination } from '@/components/ui/Pagination'

function wrapper() {
  const qc = new QueryClient()
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('UI kit components', () => {
  beforeEach(() => window.localStorage.clear())

  it('Avatar renders initials for a name', () => {
    render(<Avatar name="John Doe" />, { wrapper: wrapper() })
    expect(document.body.textContent).toContain('JD')
  })

  it('StatusBadge shows status text', () => {
    render(<StatusBadge status="APPROVED" />, { wrapper: wrapper() })
    expect(document.body.textContent).toContain('approved')
  })

  it('EmptyState shows title and description', () => {
    render(<EmptyState title="Nothing here" description="Try later" />, { wrapper: wrapper() })
    expect(document.body.textContent).toContain('Nothing here')
    expect(document.body.textContent).toContain('Try later')
  })

  it('Pagination renders prev, paging info and next', () => {
    render(<Pagination page={1} size={10} totalPages={5} totalElements={42} onPageChange={() => undefined} />, { wrapper: wrapper() })
    expect(document.body.textContent).toContain('42')
  })
})