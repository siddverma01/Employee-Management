import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LoginPage } from '@/pages/public/LoginPage'

vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

import { authApi } from '@/api'

function renderLogin() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { qc }
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.mocked(authApi.me).mockResolvedValue(null as never)
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'jwt-token',
      user: { id: 1, email: 'john@x.com', role: 'EMPLOYEE' as const, employeeCode: 'EMP-001', fullName: 'John Doe', avatar: null },
    } as never)
  })

  it('renders title and demo credentials', () => {
    renderLogin()
    expect(screen.getByText('Employee Management')).toBeInTheDocument()
    expect(screen.getByText(/admin@emplmgt.com/i)).toBeInTheDocument()
  })

  it('shows validation errors for empty submit', async () => {
    renderLogin()
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
    await waitFor(() => expect(screen.getByText('Email is required')).toBeInTheDocument())
    expect(screen.getByText('Password is required')).toBeInTheDocument()
    expect(authApi.login).not.toHaveBeenCalled()
  })

  it('calls login and stores token on valid submit', async () => {
    renderLogin()
    await userEvent.type(screen.getByPlaceholderText('you@company.com'), 'john@x.com')
    await userEvent.type(screen.getByPlaceholderText('••••••••'), 'Welcome@123')
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
    await waitFor(() => expect(authApi.login).toHaveBeenCalledWith('john@x.com', 'Welcome@123', true))
    await waitFor(() => expect(localStorage.getItem('emp_token')).toBe('jwt-token'))
  })
})