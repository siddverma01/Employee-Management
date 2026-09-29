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

/**
 * Compact Stitch control.
 *
 * Dark mode: #1E2025 control surface, #2E323B hairline border, 8px radius and
 * muted ink. Hover lifts the surface slightly and brightens the icon, so the
 * affordance is legible without a glow. `duration-150 ease-in-out` is explicit
 * rather than inherited from the base transition, keeping every control on the
 * same 150ms curve. No box-shadow — separation comes from the border alone.
 */
const headerIconBtn =
  'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-surface-500 ' +
  'transition-[background-color,color,border-color] duration-150 ease-in-out ' +
  'hover:bg-surface-100 hover:text-surface-800 ' +
  'dark:border dark:border-[#2E323B] dark:bg-[#1E2025] dark:text-slate-400 ' +
  'dark:hover:border-[#3A3E48] dark:hover:bg-[#282B32] dark:hover:text-slate-100'

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
    /* Shared 56px header rail. Height and padding are identical in both themes;
       only the surface, border and ink colours differ. Inter is applied to <html>
       via --hpe-font-primary; font-sans is restated so the control typography
       holds even if the header is ever rendered outside the app shell. */
    <header className="flex h-14 shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-6 font-sans dark:border-[#23252A] dark:bg-[#141518]">
      {/* Left — mobile menu trigger + team context */}
      <div className="flex min-w-0 items-center gap-3">
        <button
          onClick={onOpenMenu}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-surface-500 transition-[background-color,color,border-color] duration-150 ease-in-out hover:bg-surface-100 hover:text-surface-800 dark:border dark:border-[#2E323B] dark:bg-[#1E2025] dark:text-slate-400 dark:hover:border-[#3A3E48] dark:hover:bg-[#282B32] dark:hover:text-slate-100 lg:hidden"
          aria-label="Open menu"
        >
          <Menu className="h-4 w-4" strokeWidth={1.5} />
        </button>
        <TeamSelector className="w-32 grow sm:w-44 lg:w-48 lg:grow-0" />
      </div>

      {/* Right-aligned actions */}
      <div className="flex items-center gap-1.5">
        <button className={headerIconBtn} aria-label="Search" title="Search">
          <Search strokeWidth={1.5} className="h-4 w-4" />
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
          {isDark ? <Sun strokeWidth={1.5} className="h-4 w-4" /> : <Moon strokeWidth={1.5} className="h-4 w-4" />}
        </button>

        <Link
          to="/notifications"
          className={cn(headerIconBtn, 'relative')}
          aria-label="Notifications"
          title="Notifications"
        >
          <Bell strokeWidth={1.5} className="h-4 w-4" />
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
            /* Same control surface as the icon buttons so the identity cluster
               reads as one row; the avatar is the only rounded element here. */
            className="flex items-center gap-2 rounded-lg border border-transparent py-1 pl-1 pr-2 transition-[background-color,color,border-color] duration-150 ease-in-out hover:bg-surface-100 dark:border-[#2E323B] dark:bg-[#1E2025] dark:hover:border-[#3A3E48] dark:hover:bg-[#282B32]"
          >
            <Avatar name={user?.fullName} size="sm" />
            <span className="hidden max-w-[120px] truncate text-sm font-medium text-surface-700 sm:block dark:text-slate-200">
              {user?.fullName ?? user?.email}
            </span>
            <ChevronDown className="hidden h-3.5 w-3.5 text-surface-500 transition-colors duration-150 ease-in-out sm:block dark:text-slate-400" strokeWidth={1.5} />
          </button>
          {profileOpen && (
            /* Menu surface sits one step above the header rail. The shadow is
               kept deliberately shallow in both themes — a 1px border plus a
               faint lift, not a large drop shadow. */
            <div className="absolute right-0 top-full z-50 mt-2 w-48 rounded-lg border border-surface-200 bg-surface-0 py-1 shadow-sm dark:border-[#2E323B] dark:bg-[#1A1B20]">
              <Link to="/profile" className="flex items-center gap-2 px-4 py-2 text-sm text-surface-700 transition-colors duration-150 ease-in-out hover:bg-surface-100 dark:text-slate-300 dark:hover:bg-[#23252A]">
                <User className="h-4 w-4" strokeWidth={1.5} /> My profile
              </Link>
              <hr className="my-1 border-surface-200 dark:border-[#2E323B]" />
              <button onClick={logout} className="flex w-full items-center gap-2 px-4 py-2 text-sm text-red-600 transition-colors duration-150 ease-in-out hover:bg-surface-100 dark:text-red-400 dark:hover:bg-[#23252A]">
                <LogOut className="h-4 w-4" strokeWidth={1.5} /> Log out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

