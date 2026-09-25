import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Bell, ChevronDown, LogOut, Menu, Moon, Search, Sun, User } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { cn } from '@/utils'
import { useAuth } from '@/hooks/useAuth'
import { notificationApi } from '@/api'
import { Avatar } from '@/components/ui/Avatar'
import { TeamSelector } from '@/components/layout/TeamSelector'

/**
 * Persisted light/dark theme.
 * Applies/resolves the `.dark` class on <html> — every token in
 * src/styles/tokens.css flips through that class.
 */
function useTheme() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const stored = localStorage.getItem('theme')
    if (stored === 'light' || stored === 'dark') return stored
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  })

  useEffect(() => {
    const root = document.documentElement
    root.classList.toggle('dark', theme === 'dark')
    root.style.colorScheme = theme
    localStorage.setItem('theme', theme)
  }, [theme])

  return { theme, toggle: () => setTheme((t) => (t === 'dark' ? 'light' : 'dark')) }
}

const headerIconBtn =
  'flex h-9 w-9 items-center justify-center rounded-full text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-800'

export function Header({ onOpenMenu }: { onOpenMenu: () => void }) {
  const { user, logout } = useAuth()
  const location = useLocation()
  const [profileOpen, setProfileOpen] = useState(false)
  const { theme, toggle } = useTheme()
  const isDark = theme === 'dark'

  const { data: unreadCount = 0 } = useQuery({
    queryKey: ['notifications', 'unreadCount'],
    queryFn: notificationApi.unreadCount,
    refetchInterval: 30_000,
  })

  useEffect(() => setProfileOpen(false), [location.pathname])

  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-4 border-b border-surface-200 bg-topnav px-4 lg:px-6">
      {/* Left — mobile menu trigger + team context */}
      <div className="flex min-w-0 items-center gap-3">
        <button
          onClick={onOpenMenu}
          className="rounded p-1 text-surface-500 hover:text-surface-800 lg:hidden"
          aria-label="Open menu"
        >
          <Menu className="h-6 w-6" />
        </button>
        <TeamSelector className="w-32 grow sm:w-44 lg:w-48 lg:grow-0" />
      </div>

      {/* Right-aligned actions */}
      <div className="flex items-center gap-1.5">
        <button className={headerIconBtn} aria-label="Search" title="Search">
          <Search strokeWidth={1.5} className="h-5 w-5" />
        </button>

        {/* Dark / Light toggle — Sun in Dark mode (go light), Moon in Light mode (go dark) */}
        <button
          className={headerIconBtn}
          onClick={toggle}
          aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
          title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {isDark ? <Sun strokeWidth={1.5} className="h-5 w-5" /> : <Moon strokeWidth={1.5} className="h-5 w-5" />}
        </button>

        <Link
          to="/notifications"
          className={cn(headerIconBtn, 'relative')}
          aria-label="Notifications"
          title="Notifications"
        >
          <Bell strokeWidth={1.5} className="h-5 w-5" />
          {unreadCount > 0 && (
            <span className="absolute -right-0.5 -top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white">
              {unreadCount > 9 ? '9+' : unreadCount}
            </span>
          )}
        </Link>

        {/* Profile */}
        <div className="relative">
          <button
            onClick={() => setProfileOpen((v) => !v)}
            className="flex items-center gap-2 rounded-full py-1 pl-1 pr-2 transition-colors hover:bg-surface-100"
          >
            <Avatar name={user?.fullName} size="sm" />
            <span className="hidden text-sm font-medium text-surface-700 sm:block max-w-[120px] truncate">
              {user?.fullName ?? user?.email}
            </span>
            <ChevronDown className="hidden h-4 w-4 text-surface-500 sm:block" />
          </button>
          {profileOpen && (
            <div className="absolute right-0 top-full z-50 mt-2 w-48 rounded-lg border border-surface-200 bg-surface-0 py-1 shadow-hpe-md">
              <Link to="/profile" className="flex items-center gap-2 px-4 py-2 text-sm text-surface-700 transition-colors hover:bg-surface-100">
                <User className="h-4 w-4" /> My profile
              </Link>
              <hr className="my-1 border-surface-200" />
              <button onClick={logout} className="flex w-full items-center gap-2 px-4 py-2 text-sm text-red-600 transition-colors hover:bg-error-50">
                <LogOut className="h-4 w-4" /> Log out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

