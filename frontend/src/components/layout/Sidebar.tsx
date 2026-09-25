import { Link, NavLink } from 'react-router-dom'
import {
  Home, User, FileText, Plus, CalendarDays, Users, Bell, LogOut,
  LayoutDashboard, Building2, FileSpreadsheet, ClipboardList, Clock, Table2, History, Search,
} from 'lucide-react'
import { cn } from '@/utils'
import { useAuth } from '@/hooks/useAuth'
import hpeElement from '@/assets/hpe-element-color.svg'

interface NavItem {
  to: string
  label: string
  icon: React.ReactNode
  adminOnly?: boolean
}

const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: <Home className="h-5 w-5" /> },
  { to: '/profile', label: 'My profile', icon: <User className="h-5 w-5" /> },
  { to: '/today', label: "Today's status", icon: <Users className="h-5 w-5" /> },
  { to: '/leaves/new', label: 'Apply leave', icon: <Plus className="h-5 w-5" /> },
  { to: '/swap-off/new', label: 'Apply swap off', icon: <Clock className="h-5 w-5" /> },
  { to: '/leaves', label: 'My leaves', icon: <ClipboardList className="h-5 w-5" /> },
  { to: '/calendar', label: 'Calendar', icon: <CalendarDays className="h-5 w-5" /> },
  { to: '/holidays', label: 'Holidays', icon: <Building2 className="h-5 w-5" /> },
  { to: '/notifications', label: 'Notifications', icon: <Bell className="h-5 w-5" /> },
  { to: '/admin/dashboard', label: 'Admin dashboard', icon: <LayoutDashboard className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/employees', label: 'Employees', icon: <Users className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/leaves', label: 'Leave approvals', icon: <FileText className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/swap-offs', label: 'Swap off approvals', icon: <FileText className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/holidays', label: 'Manage holidays', icon: <CalendarDays className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/imports', label: 'Excel import', icon: <FileSpreadsheet className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/historical-import', label: 'Historical attendance', icon: <History className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/roster', label: 'Attendance Roster', icon: <Table2 className="h-5 w-5" /> },
  { to: '/admin/attendance-history', label: 'Attendance History', icon: <Search className="h-5 w-5" />, adminOnly: true },
  { to: '/admin/audit-logs', label: 'Audit logs', icon: <ClipboardList className="h-5 w-5" />, adminOnly: true },
]

// Active menu item: accent fill pill (rounded-md), 4px brand left-border,
// primary (white in dark) text. Inactive items: no fill, secondary text.
// Inactive items keep an invisible 4px border so the pill never shifts.
// When collapsed the label is hidden, the icon stays centered and the same
// active/inactive tinting is preserved so the current page stays identifiable.
const itemClass = ({ isActive, collapsed }: { isActive: boolean; collapsed?: boolean }) =>
  cn(
    'flex items-center gap-3 rounded-md border-l-4 px-3 py-2 text-sm transition-colors duration-150',
    collapsed && 'justify-center px-2',
    isActive
      ? 'border-brand bg-surface-100 font-semibold text-surface-800'
      : 'border-transparent text-surface-500 hover:bg-surface-100 hover:text-surface-800',
  )

function Brand({ collapsed = false }: { collapsed?: boolean }) {
  return (
    <div
      className={cn(
        'flex h-16 shrink-0 items-center border-b border-surface-200',
        collapsed ? 'justify-center px-1' : 'px-4',
      )}
    >
      <Link
        to="/dashboard"
        className="flex items-center gap-3"
        aria-label="Employee management home"
        title={collapsed ? 'Employee management' : undefined}
      >
        <img src={hpeElement} alt="HPE" className="block h-6 w-auto" />
        {!collapsed && <span className="truncate text-sm font-semibold text-surface-800">Employee management</span>}
      </Link>
    </div>
  )
}

export function Sidebar({ onNavigate, collapsed = false }: { onNavigate?: () => void; collapsed?: boolean }) {
  const { user, logout } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const visibleItems = NAV_ITEMS.filter((n) => !n.adminOnly || isAdmin)

  return (
    <div className="flex h-full flex-col bg-sidebar">
      <Brand collapsed={collapsed} />
      <nav className="flex flex-1 flex-col gap-0.5 overflow-y-auto py-2">
        {visibleItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            onClick={onNavigate}
            className={({ isActive }) => itemClass({ isActive, collapsed })}
            title={collapsed ? item.label : undefined}
          >
            {item.icon}
            {!collapsed && item.label}
          </NavLink>
        ))}
      </nav>
      <div className={cn('border-t border-surface-200', collapsed ? 'p-1' : 'p-2')}>
        <button
          onClick={() => {
            logout()
            onNavigate?.()
          }}
          className={cn(
            'flex w-full items-center gap-3 rounded-md border-l-4 border-transparent px-3 py-2 text-sm text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-800',
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