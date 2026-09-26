import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { TeamProvider } from '@/hooks/useTeam'
import { MainLayout } from '@/components/layout/MainLayout'
import { LoadingState } from '@/components/ui/LoadingState'
import { LoginPage } from '@/pages/public/LoginPage'
import { DashboardPage } from '@/pages/employee/DashboardPage'
import { ProfilePage } from '@/pages/employee/ProfilePage'
import { ApplyLeavePage } from '@/pages/employee/ApplyLeavePage'
import { MyLeavesPage } from '@/pages/employee/MyLeavesPage'
import { ApplySwapOffPage } from '@/pages/employee/ApplySwapOffPage'
import { MySwapOffsPage } from '@/pages/employee/MySwapOffsPage'
import { LeavePage } from '@/pages/employee/LeavePage'
import { CalendarPage } from '@/pages/employee/CalendarPage'
import { TodayPage } from '@/pages/employee/TodayPage'
import { HolidaysPage } from '@/pages/employee/HolidaysPage'
import { NotificationsPage } from '@/pages/employee/NotificationsPage'
import { AttendancePage } from '@/pages/employee/AttendancePage'
import { AdminDashboardPage } from '@/pages/admin/AdminDashboardPage'
import { AdminEmployeesPage } from '@/pages/admin/AdminEmployeesPage'
import { AdminEmployeeDetailPage } from '@/pages/admin/AdminEmployeeDetailPage'
import { AdminLeavesPage } from '@/pages/admin/AdminLeavesPage'
import { AdminSwapOffsPage } from '@/pages/admin/AdminSwapOffsPage'
import { AdminHolidaysPage } from '@/pages/admin/AdminHolidaysPage'
import { AdminImportPage } from '@/pages/admin/AdminImportPage'
import AdminHistoricalImportPage from '@/pages/admin/AdminHistoricalImportPage'
import { AdminRosterPage } from '@/pages/admin/AdminRosterPage'
import { AdminAttendanceHistoryPage } from '@/pages/admin/AdminAttendanceHistoryPage'
import { AdminAuditLogsPage } from '@/pages/admin/AdminAuditLogsPage'
import { AdminAttendancePage } from '@/pages/admin/AdminAttendancePage'
import { Dashboard as ThemeDemo } from '@/pages/demo/Dashboard'
import type { ReactNode } from 'react'

function Protected({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <LoadingState />
      </div>
    )
  }
  if (!user) return <Navigate to="/login" replace />
  return <>{children}</>
}

function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  if (user?.role !== 'ADMIN') return <Navigate to="/dashboard" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<Protected><TeamProvider><MainLayout /></TeamProvider></Protected>}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/demo" element={<ThemeDemo />} />
          <Route path="/profile" element={<ProfilePage />} />
<Route path="/leaves" element={<LeavePage />} />
<Route path="/leaves/new" element={<ApplyLeavePage />} />
<Route path="/swap-off/new" element={<ApplySwapOffPage />} />
<Route path="/swap-off" element={<MySwapOffsPage />} />
          <Route path="/calendar" element={<CalendarPage />} />
          <Route path="/today" element={<TodayPage />} />
          <Route path="/holidays" element={<HolidaysPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
<Route path="/attendance" element={<AttendancePage />} />
            {/* Attendance Roster — viewable by every authenticated user (read-only
                for employees); changes stay admin-only, enforced server-side. */}
            <Route path="/admin/roster" element={<AdminRosterPage />} />

            <Route element={<AdminOnly><OutletWrapper /></AdminOnly>}>
              <Route path="/admin/dashboard" element={<AdminDashboardPage />} />
              <Route path="/admin/employees" element={<AdminEmployeesPage />} />
              <Route path="/admin/employees/new" element={<AdminEmployeeDetailPage />} />
              <Route path="/admin/employees/:id" element={<AdminEmployeeDetailPage />} />
              <Route path="/admin/leaves" element={<AdminLeavesPage />} />
              <Route path="/admin/swap-offs" element={<AdminSwapOffsPage />} />
              <Route path="/admin/holidays" element={<AdminHolidaysPage />} />
              <Route path="/admin/imports" element={<AdminImportPage />} />
              <Route path="/admin/historical-import" element={<AdminHistoricalImportPage />} />
              <Route path="/admin/audit-logs" element={<AdminAuditLogsPage />} />
              <Route path="/admin/attendance" element={<AdminAttendancePage />} />
              <Route path="/admin/attendance-history" element={<AdminAttendanceHistoryPage />} />
            </Route>
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

// Small helper so the nested AdminOnly route can render children with the layout's <Outlet/>
function OutletWrapper() {
  return <Outlet />
}