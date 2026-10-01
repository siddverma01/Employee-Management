import { NavLink } from 'react-router-dom'
import {
  Home, Users, LogOut,
  LayoutDashboard, FileSpreadsheet, ClipboardList, Table2, History, Search,
  CalendarDays, CalendarClock, FileText,
} from 'lucide-react'
import { cn } from '@/utils'
import { useAuth } from '@/hooks/useAuth'

type NavGroup = 'workspace' | 'administration' | 'attendance'

interface NavItem {
  to: string
  label: string
  icon: React.ReactNode
  adminOnly?: boolean
  group: NavGroup
  badge?: string
}

const NAV_GROUPS: { id: NavGroup; label: string }[] = [
  { id: 'workspace', label: 'Workspace' },
  { id: 'administration', label: 'Administration' },
  { id: 'attendance', label: 'Attendance' },
]

const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: <Home className="h-[18px] w-[18px] shrink-0" />, group: 'workspace' },
  { to: '/today', label: "Today's status", icon: <Users className="h-[18px] w-[18px] shrink-0" />, group: 'workspace' },
  { to: '/leaves', label: 'My leaves', icon: <ClipboardList className="h-[18px] w-[18px] shrink-0" />, group: 'workspace' },
  { to: '/calendar', label: 'Calendar', icon: <CalendarDays className="h-[18px] w-[18px] shrink-0" />, group: 'workspace' },
  { to: '/events', label: 'Events & Schedule', icon: <CalendarClock className="h-[18px] w-[18px] shrink-0" />, group: 'workspace' },
  { to: '/admin/dashboard', label: 'Admin dashboard', icon: <LayoutDashboard className="h-[18px] w-[18px] shrink-0" />, group: 'administration', adminOnly: true },
  { to: '/admin/employees', label: 'Employees', icon: <Users className="h-[18px] w-[18px] shrink-0" />, group: 'administration', adminOnly: true },
  { to: '/admin/leaves', label: 'Leave approvals', icon: <FileText className="h-[18px] w-[18px] shrink-0" />, group: 'administration', adminOnly: true, badge: '3' },
  { to: '/admin/swap-offs', label: 'Swap off approvals', icon: <FileText className="h-[18px] w-[18px] shrink-0" />, group: 'administration', adminOnly: true },
  { to: '/admin/holidays', label: 'Manage holidays', icon: <CalendarDays className="h-[18px] w-[18px] shrink-0" />, group: 'administration', adminOnly: true },
  { to: '/admin/imports', label: 'Excel import', icon: <FileSpreadsheet className="h-[18px] w-[18px] shrink-0" />, group: 'attendance', adminOnly: true },
  { to: '/admin/historical-import', label: 'Historical attendance', icon: <History className="h-[18px] w-[18px] shrink-0" />, group: 'attendance', adminOnly: true },
  { to: '/admin/roster', label: 'Attendance Roster', icon: <Table2 className="h-[18px] w-[18px] shrink-0" />, group: 'attendance' },
  { to: '/admin/attendance-history', label: 'Attendance History', icon: <Search className="h-[18px] w-[18px] shrink-0" />, group: 'attendance', adminOnly: true },
  { to: '/admin/audit-logs', label: 'Audit logs', icon: <ClipboardList className="h-[18px] w-[18px] shrink-0" />, group: 'attendance', adminOnly: true },
]

/**
 * Nav item — compact 36px row.
 *
 * Dark mode uses the Stitch surfaces rather than an emerald block: a 10%-alpha
 * HPE green wash, a 35%-alpha green hairline and green ink. The tint reads as a
 * quiet "you are here" rather than a solid highlight, so a bright neon block is
 * avoided. Inactive rows keep `border-transparent` so switching routes never
 * shifts the row width, and every row carries an explicit 150ms ease so hover
 * and active transitions share one curve.
 *
 * Colors are set on the row and inherited by the icon, so the icon desaturates
 * with the label on hover instead of needing its own per-state color.
 */
const itemClass = ({ isActive, collapsed }: { isActive: boolean; collapsed?: boolean }) =>
  cn(
    'group flex min-h-9 min-w-0 items-center justify-between gap-2.5 whitespace-nowrap rounded-lg border px-3 py-1.5 text-[13px] font-medium leading-5 transition-[background-color,color,border-color] duration-150 ease-in-out',
    collapsed && 'justify-center px-2',
    isActive
      ? 'border-[rgba(0,179,136,0.35)] bg-[rgba(0,179,136,0.10)] font-semibold text-emerald-700 dark:border-[rgba(0,229,153,0.35)] dark:bg-[rgba(0,229,153,0.10)] dark:text-[#2DD4BF] dark:shadow-none dark:[&_svg]:text-[#00E599]'
      : 'border-transparent text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-[#CBD5E1] dark:hover:bg-[#1E232B] dark:hover:text-[#F8FAFC] dark:[&_svg]:text-[#94A3B8] dark:hover:[&_svg]:text-[#F8FAFC]',
  )

export function Sidebar({ onNavigate, collapsed = false }: { onNavigate?: () => void; collapsed?: boolean }) {
  const { user, logout } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const visibleItems = NAV_ITEMS.filter((n) => !n.adminOnly || isAdmin)

  return (
    /* No brand rail: the wordmark and the sidebar collapse control now live in
       the full-width header, so a second logo here would duplicate it. */
    <div className="flex h-full flex-col bg-transparent">
      <nav className="flex flex-1 flex-col overflow-y-auto py-1">
        {NAV_GROUPS.map((group) => {
          const groupItems = visibleItems.filter((n) => n.group === group.id)
          if (groupItems.length === 0) return null
          return (
            <div key={group.id} className="flex flex-col">
              {!collapsed && (
                /* 10px uppercase / 600 / ~0.08em, muted gray, compact rhythm. */
                <h2 className="px-3 pb-1 pt-4 text-[10px] font-semibold uppercase tracking-[0.08em] text-slate-400 dark:text-[#8A8F98]">
                  {group.label}
                </h2>
              )}
              {groupItems.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  onClick={onNavigate}
                  className={({ isActive }) => itemClass({ isActive, collapsed })}
                  title={collapsed ? item.label : undefined}
                >
                  {({ isActive }) => (
                    <>
                      <span className="flex min-w-0 items-center gap-3">
                        {item.icon}
                        {!collapsed && <span className="truncate whitespace-nowrap">{item.label}</span>}
                      </span>
                      {!collapsed && item.badge && (
                        <span className="text-[10px] bg-amber-100 text-amber-800 border border-amber-200 px-1.5 py-0.5 rounded font-semibold leading-none dark:bg-amber-500/20 dark:text-amber-300 dark:border-amber-500/40">
                          {item.badge}
                        </span>
                      )}
                      {!collapsed && isActive && (
                        <span className="h-[5px] w-[5px] rounded-full bg-[#00B388] dark:bg-[#00E599]" />
                      )}
                    </>
                  )}
                </NavLink>
              ))}
            </div>
          )
        })}
      </nav>
      <div
        data-probe="logout"
        className={cn('border-t border-slate-200 bg-white dark:border-[#222731] dark:bg-[#121519]', collapsed ? 'p-1' : 'p-2')}
      >
        <button
          onClick={() => {
            logout()
            onNavigate?.()
          }}
          className={cn(
            'group flex min-h-9 w-full items-center gap-3 rounded-lg px-3 py-1.5 text-[13px] font-medium leading-5 text-slate-600 transition-[background-color,color] duration-150 ease-in-out hover:bg-slate-100 hover:text-slate-900 dark:text-[#CBD5E1] dark:hover:bg-[#1E232B] dark:hover:text-[#F8FAFC] dark:[&_svg]:text-[#94A3B8] dark:hover:[&_svg]:text-[#F8FAFC]',
            collapsed && 'justify-center px-2',
          )}
          title={collapsed ? 'Log out' : undefined}
        >
          <LogOut className="h-[18px] w-[18px] shrink-0" strokeWidth={1.5} />
          {!collapsed && 'Log out'}
        </button>
      </div>
    </div>
  )
}