import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'
import { cn } from '@/utils'
import { Header } from '@/components/layout/Header'
import { Sidebar } from '@/components/layout/Sidebar'

/** Persisted desktop sidebar preference (kept out of the attendance database). */
const SIDEBAR_KEY = 'empmgmt.sidebarExpanded'

export function MainLayout() {
  const location = useLocation()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [sidebarExpanded, setSidebarExpanded] = useState<boolean>(() => {
    try {
      const stored = localStorage.getItem(SIDEBAR_KEY)
      return stored === null ? true : stored === 'true'
    } catch {
      return true
    }
  })

  useEffect(() => {
    setMobileOpen(false)
  }, [location.pathname])

  useEffect(() => {
    try {
      localStorage.setItem(SIDEBAR_KEY, String(sidebarExpanded))
    } catch {
      /* storage unavailable — keep in-memory state */
    }
  }, [sidebarExpanded])

  return (
    <div className="flex h-screen overflow-hidden bg-surface-50">
      {/* Desktop sidebar */}
      <aside
        className={cn(
          'relative hidden shrink-0 flex-col border-r border-surface-200 bg-sidebar transition-[width] duration-200 ease-in-out lg:flex',
          sidebarExpanded ? 'lg:w-64' : 'lg:w-[4.5rem]',
        )}
      >
        <button
          onClick={() => setSidebarExpanded((v) => !v)}
          aria-label={sidebarExpanded ? 'Collapse sidebar' : 'Expand sidebar'}
          title={sidebarExpanded ? 'Collapse sidebar' : 'Expand sidebar'}
          className="absolute -right-3 top-[22px] z-10 flex h-5 w-5 items-center justify-center rounded-full border border-surface-200 bg-surface-0 text-surface-500 shadow-hpe-sm transition-colors duration-150 hover:text-surface-800"
        >
          {sidebarExpanded ? <ChevronLeft className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
        </button>
        <Sidebar collapsed={!sidebarExpanded} />
      </aside>

      {/* Mobile overlay */}
      {mobileOpen && (
        <div className="fixed inset-0 z-40 bg-black/40 lg:hidden" onClick={() => setMobileOpen(false)} />
      )}
      {/* Mobile drawer */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-50 flex w-64 flex-col bg-sidebar transition-transform lg:hidden',
          mobileOpen ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        <button
          onClick={() => setMobileOpen(false)}
          className="absolute right-3 top-4 z-10 p-1 text-surface-500 hover:text-surface-800"
          aria-label="Close menu"
        >
          <X className="h-5 w-5" />
        </button>
        <Sidebar onNavigate={() => setMobileOpen(false)} />
      </aside>

      {/* Main area */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <Header onOpenMenu={() => setMobileOpen(true)} />
        <main className="flex-1 overflow-y-auto p-4 lg:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}