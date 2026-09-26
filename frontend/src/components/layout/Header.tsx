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
 *
 * Tailwind is configured with `darkMode: 'class'`, so dark mode is active only
 * while `dark` is present on <html>. Light mode is the absence of that class —
 * we never add a `light` class.
 *
 * The DOM class is the single source of truth: `toggleTheme` flips it directly
 * and mirrors the result into React state for the button icon. An init check
 * (mirrored by the blocking script in index.html, which prevents a light flash)
 * resolves the class on first mount.
 */
function useTheme() {
  const [isDark, setIsDark] = useState<boolean>(
    () => document.documentElement.classList.contains('dark'),
  )

  // Init check: re-assert the class from storage / OS preference on mount.
  useEffect(() => {
    try {
      const stored = localStorage.getItem('theme')
      const prefersDark =
        !('theme' in localStorage) &&
        window.matchMedia('(prefers-color-scheme: dark)').matches
      const dark = stored === 'dark' || prefersDark
      document.documentElement.classList.toggle('dark', dark)
      document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
      setIsDark(dark)
    } catch {
      /* storage unavailable (private mode) — keep the current class */
    }
  }, [])

  const updateThemeIcons = (dark: boolean) => setIsDark(dark)

  const toggleTheme = () => {
    const dark = document.documentElement.classList.toggle('dark')
    try {
      localStorage.setItem('theme', dark ? 'dark' : 'light')
    } catch {
      /* storage unavailable — theme still applies for this session */
    }
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
    updateThemeIcons(dark)
  }

  return { isDark, toggleTheme }
}

const headerIconBtn =
  'flex h-9 w-9 items-center justify-center rounded-full text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-800'

export function Header({ onOpenMenu }: { onOpenMenu: () => void }) {
  const { user, logout } = useAuth()
  const location = useLocation()
  const [profileOpen, setProfileOpen] = useState(false)
  const { isDark, toggleTheme } = useTheme()

  const { data: unreadCount = 0 } = useQuery({
    queryKey: ['notifications', 'unreadCount'],
    queryFn: notificationApi.unreadCount,
    refetchInterval: 30_000,
  })

  useEffect(() => setProfileOpen(false), [location.pathname])

  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 lg:px-6 dark:border-[#23252a] dark:bg-[#141518]">
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

        {/* Theme toggle — Sun shown in Dark mode (click for light), Moon in Light mode */}
        <button
          id="theme-toggle"
          className={headerIconBtn}
          onClick={toggleTheme}
          aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
          title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
          aria-pressed={isDark}
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
              <button onClick={logout} className="flex w-full items-center gap-2 px-4 py-2 text-sm text-red-600 transition-colors hover:bg-surface-100">
                <LogOut className="h-4 w-4" /> Log out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

