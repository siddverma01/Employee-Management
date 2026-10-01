import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { X } from 'lucide-react'
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
    /* Column shell: the 64px header owns the full viewport width, so the wordmark
       sits at the viewport's left edge above the sidebar. The second row holds
       the sidebar and the scrollable main area. */
    <div className="flex h-screen flex-col overflow-hidden bg-slate-50 text-slate-800 dark:bg-[#0d0f12] dark:text-slate-200">
      <Header
        onOpenMenu={() => setMobileOpen(true)}
        sidebarExpanded={sidebarExpanded}
        onToggleSidebar={() => setSidebarExpanded((v) => !v)}
      />

      <div className="flex min-h-0 flex-1">
        {/* Desktop sidebar */}
        <aside
          className={cn(
            'relative hidden shrink-0 flex-col border-r border-slate-200 bg-white transition-[width] duration-200 ease-in-out lg:flex dark:border-[#1e232b] dark:bg-[#121519]',
            /* Geometry is shared: 224px expanded / 72px collapsed in both themes.
               Only the border and surface colours differ. */
            sidebarExpanded ? 'lg:w-[224px] lg:min-w-[224px]' : 'lg:w-[4.5rem]',
          )}
        >
          <Sidebar collapsed={!sidebarExpanded} />
        </aside>

        {/* Mobile overlay */}
        {mobileOpen && (
          <div className="fixed inset-0 z-40 bg-black/40 lg:hidden" onClick={() => setMobileOpen(false)} />
        )}
        {/* Mobile drawer */}
        <aside
          className={cn(
            'fixed inset-y-0 left-0 z-50 flex w-[224px] min-w-[224px] flex-col bg-white transition-transform lg:hidden dark:bg-[#121519]',
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
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden">
          <div className="w-full min-w-0 flex-1 flex flex-col bg-slate-50 overflow-y-auto px-8 py-6 transition-all duration-300 ease-in-out dark:bg-[#0d0f12]">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
