import { Link, NavLink } from 'react-router-dom'
import {
  Home, Users, LogOut,
  LayoutDashboard, Building2, FileSpreadsheet, ClipboardList, Table2, History, Search,
  CalendarDays, FileText,
} from 'lucide-react'
import { cn } from '@/utils'
import { useAuth } from '@/hooks/useAuth'
import hpeElement from '@/assets/hpe-element-color.svg'

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
  { to: '/dashboard', label: 'Dashboard', icon: <Home className="h-5 w-5" />, group: 'workspace' },
  { to: '/today', label: "Today's status", icon: <Users className="h-5 w-5" />, group: 'workspace' },
  { to: '/leaves', label: 'My leaves', icon: <ClipboardList className="h-5 w-5" />, group: 'workspace' },
  { to: '/calendar', label: 'Calendar', icon: <CalendarDays className="h-5 w-5" />, group: 'workspace' },
  { to: '/holidays', label: 'Holidays', icon: <Building2 className="h-5 w-5" />, group: 'workspace' },
  { to: '/admin/dashboard', label: 'Admin dashboard', icon: <LayoutDashboard className="h-5 w-5" />, group: 'administration', adminOnly: true },
  { to: '/admin/employees', label: 'Employees', icon: <Users className="h-5 w-5" />, group: 'administration', adminOnly: true },
  { to: '/admin/leaves', label: 'Leave approvals', icon: <FileText className="h-5 w-5" />, group: 'administration', adminOnly: true, badge: '3' },
  { to: '/admin/swap-offs', label: 'Swap off approvals', icon: <FileText className="h-5 w-5" />, group: 'administration', adminOnly: true },
  { to: '/admin/holidays', label: 'Manage holidays', icon: <CalendarDays className="h-5 w-5" />, group: 'administration', adminOnly: true },
  { to: '/admin/imports', label: 'Excel import', icon: <FileSpreadsheet className="h-5 w-5" />, group: 'attendance', adminOnly: true },
  { to: '/admin/historical-import', label: 'Historical attendance', icon: <History className="h-5 w-5" />, group: 'attendance', adminOnly: true },
  { to: '/admin/roster', label: 'Attendance Roster', icon: <Table2 className="h-5 w-5" />, group: 'attendance' },
  { to: '/admin/attendance-history', label: 'Attendance History', icon: <Search className="h-5 w-5" />, group: 'attendance', adminOnly: true },
  { to: '/admin/audit-logs', label: 'Audit logs', icon: <ClipboardList className="h-5 w-5" />, group: 'attendance', adminOnly: true },
]

// Active item: emerald pill + 1px border + trailing status dot.
// Inactive items keep a transparent border so the pill never shifts width.
const itemClass = ({ isActive, collapsed }: { isActive: boolean; collapsed?: boolean }) =>
  cn(
    'flex min-w-0 items-center justify-between gap-2.5 whitespace-nowrap px-2.5 py-1.5 rounded-lg text-sm transition-colors duration-150 border',
    collapsed && 'justify-center px-2',
    isActive
      ? 'text-emerald-700 bg-emerald-50 border-emerald-200 font-semibold shadow-sm whitespace-nowrap dark:text-emerald-400 dark:bg-emerald-950/40 dark:border-emerald-500/30'
      : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100 border-transparent dark:text-slate-300 dark:hover:text-white dark:hover:bg-slate-800/80',
  )

function Brand({ collapsed = false }: { collapsed?: boolean }) {
  return (
    <div
      className={cn(
        'flex h-16 shrink-0 items-center border-b border-slate-200',
        collapsed ? 'justify-center px-1' : 'px-4',
      )}
    >
      <Link
        to="/dashboard"
        className="flex min-w-0 items-center gap-3"
        aria-label="Employee management home"
        title={collapsed ? 'Employee management' : undefined}
      >
        <img src={hpeElement} alt="HPE" className="block h-6 w-auto shrink-0" />
        {!collapsed && (
          <span className="truncate whitespace-nowrap text-sm font-semibold text-surface-800">
            Employee management
          </span>
        )}
      </Link>
    </div>
  )
}

export function Sidebar({ onNavigate, collapsed = false }: { onNavigate?: () => void; collapsed?: boolean }) {
  const { user, logout } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const visibleItems = NAV_ITEMS.filter((n) => !n.adminOnly || isAdmin)

  return (
    <div className="flex h-full flex-col bg-transparent">
      <Brand collapsed={collapsed} />
      <nav className="flex flex-1 flex-col overflow-y-auto py-1">
        {NAV_GROUPS.map((group) => {
          const groupItems = visibleItems.filter((n) => n.group === group.id)
          if (groupItems.length === 0) return null
          return (
            <div key={group.id} className="flex flex-col">
              {!collapsed && (
                <h2 className="px-2.5 pt-3 pb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-400">
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
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 dark:bg-emerald-400" />
                      )}
                    </>
                  )}
                </NavLink>
              ))}
            </div>
          )
        })}
      </nav>
      <div className={cn('border-t border-slate-200 bg-slate-50 dark:border-slate-800 dark:bg-[#0e1420]', collapsed ? 'p-1' : 'p-2')}>
        <button
          onClick={() => {
            logout()
            onNavigate?.()
          }}
          className={cn(
            'flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm text-slate-600 transition-colors hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800/80 dark:hover:text-white',
            collapsed && 'justify-center px-2',
          )}
          title={collapsed ? 'Log out' : undefined}
        >
          <LogOut className="h-5 w-5" />
          {!collapsed && 'Log out'}
        </button>
      </div>
    </div>
  )
}