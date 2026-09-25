import { useCallback, useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { AuthUser } from '@/types'
import { authApi } from '@/api'
import { TOKEN_KEY } from '@/api/client'

interface AuthState {
  user: AuthUser | null
  loading: boolean
  authenticated: boolean
  login: (email: string, password: string, rememberMe?: boolean) => Promise<void>
  logout: () => void
}

let authCache: AuthUser | null = null

export function useAuth(): AuthState {
  const queryClient = useQueryClient()
  const [initializing, setInitializing] = useState(true)

  const { data: user, isLoading: queryLoading } = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      if (!localStorage.getItem(TOKEN_KEY)) return null
      if (authCache) return authCache
      const me = await authApi.me()
      authCache = me
      return me
    },
    retry: false,
    staleTime: Infinity,
  })

  useEffect(() => {
    const onUnauthorized = () => {
      authCache = null
      queryClient.setQueryData(['auth', 'me'], null)
    }
    window.addEventListener('auth:unauthorized', onUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized)
  }, [queryClient])

  useEffect(() => {
    if (!queryLoading) setInitializing(false)
  }, [queryLoading])

  const login = useCallback(
    async (email: string, password: string, rememberMe?: boolean) => {
      const res = await authApi.login(email, password, rememberMe)
      localStorage.setItem(TOKEN_KEY, res.token)
      authCache = res.user
      queryClient.setQueryData(['auth', 'me'], res.user)
    },
    [queryClient],
  )

  const logout = useCallback(() => {
    authApi.logout().catch(() => undefined)
    localStorage.removeItem(TOKEN_KEY)
    authCache = null
    queryClient.clear()
  }, [queryClient])

  return useMemo(
    () => ({
      user: user ?? null,
      loading: initializing,
      authenticated: Boolean(user),
      login,
      logout,
    }),
    [user, initializing, login, logout],
  )
}