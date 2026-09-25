import { api } from './client'
import type {
  AdminSummary, Attendance, AttendanceHistoryMeta, AttendanceHistoryResponse, AuditLog, AuthUser, CalendarEvent, ChartData, CompanyEvent, Department,
  EmployeeDashboard, EmployeeHistoricalAttendance, Holiday, HistoricalCommitResponse, HistoricalHistoryItem,
  HistoricalInspectResponse, HistoricalMapStagedRequest, HistoricalMapStagedResponse, HistoricalMapUnknownResponse,
  HistoricalPreviewResponse, HistoricalRecordsPage, HistoricalStatusItem, HistoricalUnknownCode, ImportCommit,
  ImportHistoryItem, ImportPreview, ImportUpload, Leave,
  LeaveBalance, LoginResponse, NotificationItem, PageResponse, Profile, RosterBatchSaveResult,
  RosterCellEdit, RosterImportPreview, RosterMonthData, RosterMonthlyData, RosterPageMeta, RosterRowSave, RosterSaveResult,
  RosterStatusDetail, RosterTodayData, ScopeType, SwapOff, TeamDashboard, TodayStatus, UpcomingItem,
} from '@/types'

export const authApi = {
  login: async (email: string, password: string, rememberMe?: boolean) => {
    const { data } = await api.post<LoginResponse>('/auth/login', { email, password, rememberMe })
    return data
  },
  me: async () => {
    const { data } = await api.get<AuthUser>('/auth/me')
    return data
  },
  logout: () => api.post('/auth/logout'),
}

export const employeeApi = {
  me: () => api.get<Profile>('/employees/me').then((r) => r.data),
  upcomingBirthdays: (days = 30) =>
    api.get<UpcomingItem[]>('/employees/birthdays/upcoming', { params: { days } }).then((r) => r.data),
  search: (q: string) =>
    api.get<{ id: number; employeeCode: string; fullName: string; department: string | null }[]>('/employees/search', { params: { q } }).then((r) => r.data),
  rosterSearch: (q: string) =>
    api.get<{ employeeCode: string; fullName: string; department: string | null }[]>('/employees/roster-search', { params: { q } }).then((r) => r.data),
  rosterMonthSearch: (month: string, q: string) =>
    api.get<{ employeeCode: string; fullName: string; department: string | null }[]>('/employees/roster-month-search', { params: { month, q } }).then((r) => r.data),
}

export const departmentApi = {
  list: () => api.get<Department[]>('/departments').then((r) => r.data),
}

export const leaveApi = {
  my: () => api.get<Leave[]>('/leaves').then((r) => r.data),
  balances: () => api.get<LeaveBalance[]>('/leaves/balances').then((r) => r.data),
  apply: (payload: { leaveType: string; startDate: string; endDate: string; reason: string; attachment?: string }) =>
    api.post<Leave>('/leaves', payload).then((r) => r.data),
  cancel: (id: number) => api.post<Leave>(`/leaves/${id}/cancel`).then((r) => r.data),
}

export const swapOffApi = {
  my: () => api.get<SwapOff[]>('/swap-offs').then((r) => r.data),
  apply: (payload: { workedDate: string; requestedOffDate: string; workedForEmployeeId: string; reason: string; attachment?: string }) =>
    api.post<SwapOff>('/swap-offs', payload).then((r) => r.data),
  cancel: (id: number) => api.post<SwapOff>(`/swap-offs/${id}/cancel`).then((r) => r.data),
}

export const todayApi = {
  status: (teamId?: number, location?: string) =>
    api.get<TodayStatus>('/today/status', {
      params: { teamId: teamId ?? undefined, location: location ?? undefined },
    }).then((r) => r.data),
}

export const calendarApi = {
  month: (year: number, month: number, teamId?: number) =>
    api.get<CalendarEvent[]>('/calendar', { params: { year, month, teamId: teamId ?? undefined } }).then((r) => r.data),
}

export const holidayApi = {
  list: (params?: { from?: string; to?: string; country?: string; scope?: ScopeType; teamId?: number }) =>
    api.get<Holiday[]>('/holidays', { params }).then((r) => r.data),
  upcoming: (limit = 10, teamId?: number) =>
    api.get<Holiday[]>('/holidays/upcoming', { params: { limit, teamId: teamId ?? undefined } }).then((r) => r.data),
}

export const eventApi = {
  list: (params?: { from?: string; to?: string; scope?: ScopeType; teamId?: number }) =>
    api.get<CompanyEvent[]>('/events', { params }).then((r) => r.data),
}

export const notificationApi = {
  list: (page = 0, size = 30) =>
    api.get<NotificationItem[]>('/notifications', { params: { page, size } }).then((r) => r.data),
  unreadCount: () => api.get<{ count: number }>('/notifications/unread-count').then((r) => r.data.count),
  markRead: (id: number) => api.post(`/notifications/${id}/read`),
  markAllRead: () => api.post<{ count: number }>('/notifications/read-all').then((r) => r.data),
}

export const attendanceApi = {
  me: (year: number, month: number) =>
    api.get<Attendance[]>('/attendance/me', { params: { year, month } }).then((r) => r.data),
}

export const dashboardApi = {
  me: () => api.get<EmployeeDashboard>('/dashboard/me').then((r) => r.data),
  team: (teamId?: number) =>
    api.get<TeamDashboard>('/dashboard/team', { params: { teamId: teamId ?? undefined } }).then((r) => r.data),
}

export const adminApi = {
  dashboardSummary: (teamId?: number) =>
    api.get<AdminSummary>('/admin/dashboard/summary', { params: { teamId: teamId ?? undefined } }).then((r) => r.data),
  chart: (name: string, from?: string, to?: string, teamId?: number) =>
    api.get<ChartData>('/admin/dashboard/charts', { params: { name, from, to, teamId: teamId ?? undefined } }).then((r) => r.data),

  employees: (params: Record<string, unknown>) =>
    api.get<PageResponse<import('@/types').Summary>>('/admin/employees', { params }).then((r) => r.data),
  employee: (id: number) => api.get<Profile>(`/admin/employees/${id}`).then((r) => r.data),
  createEmployee: (payload: Record<string, unknown>) =>
    api.post<Profile>('/admin/employees', payload).then((r) => r.data),
  updateEmployee: (id: number, payload: Record<string, unknown>) =>
    api.put<Profile>(`/admin/employees/${id}`, payload).then((r) => r.data),
  setEmployeeStatus: (id: number, status: 'ACTIVE' | 'INACTIVE') =>
    api.patch(`/admin/employees/${id}/status`, null, { params: { status } }),

  departments: () => api.get<Department[]>('/departments').then((r) => r.data),
  createDepartment: (payload: { name: string; description?: string }) =>
    api.post<Department>('/admin/departments', payload).then((r) => r.data),

  leaves: (params: Record<string, unknown>) =>
    api.get<PageResponse<Leave>>('/admin/leaves', { params }).then((r) => r.data),
  approveLeave: (id: number) => api.post<Leave>(`/admin/leaves/${id}/approve`).then((r) => r.data),
  rejectLeave: (id: number, rejectionReason: string) =>
    api.post<Leave>(`/admin/leaves/${id}/reject`, { rejectionReason }).then((r) => r.data),

  swapOffs: (params: Record<string, unknown>) =>
    api.get<PageResponse<SwapOff>>('/admin/swap-offs', { params }).then((r) => r.data),
  approveSwapOff: (id: number) => api.post<SwapOff>(`/admin/swap-offs/${id}/approve`).then((r) => r.data),
  rejectSwapOff: (id: number, rejectionReason: string) =>
    api.post<SwapOff>(`/admin/swap-offs/${id}/reject`, { rejectionReason }).then((r) => r.data),

  attendance: (params: Record<string, unknown>) =>
    api.get<PageResponse<Attendance>>('/admin/attendance', { params }).then((r) => r.data),
  upsertAttendance: (payload: Record<string, unknown>) =>
    api.post<Attendance>('/admin/attendance', payload).then((r) => r.data),
  deleteAttendance: (id: number) => api.delete(`/admin/attendance/${id}`),

  holidays: (params?: { from?: string; to?: string }) =>
    api.get<Holiday[]>('/holidays', { params }).then((r) => r.data),
  createHoliday: (payload: Record<string, unknown>) => api.post<Holiday>('/admin/holidays', payload).then((r) => r.data),
  updateHoliday: (id: number, payload: Record<string, unknown>) =>
    api.put<Holiday>(`/admin/holidays/${id}`, payload).then((r) => r.data),
  deleteHoliday: (id: number) => api.delete(`/admin/holidays/${id}`),

  events: (params?: { from?: string; to?: string }) =>
    api.get<CompanyEvent[]>('/events', { params }).then((r) => r.data),
  createEvent: (payload: Record<string, unknown>) => api.post<CompanyEvent>('/admin/events', payload).then((r) => r.data),
  updateEvent: (id: number, payload: Record<string, unknown>) =>
    api.put<CompanyEvent>(`/admin/events/${id}`, payload).then((r) => r.data),
  deleteEvent: (id: number) => api.delete(`/admin/events/${id}`),

  auditLogs: (params: Record<string, unknown>) =>
    api.get<PageResponse<AuditLog>>('/admin/audit-logs', { params }).then((r) => r.data),

  uploadExcel: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<ImportUpload>('/admin/excel/upload', form).then((r) => r.data)
  },
  setMapping: (importId: number, mapping: Record<string, unknown>) =>
    api.post<ImportPreview>(`/admin/excel/${importId}/mapping`, mapping).then((r) => r.data),
  previewImport: (importId: number) =>
    api.get<ImportPreview>(`/admin/excel/${importId}/preview`).then((r) => r.data),
  commitImport: (importId: number) =>
    api.post<ImportCommit>(`/admin/excel/${importId}/commit`).then((r) => r.data),
  importHistory: () => api.get<ImportHistoryItem[]>('/admin/excel/imports').then((r) => r.data),

  rosterImportPreview: (teamId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<RosterImportPreview>('/admin/roster/import-preview', form, { params: { teamId } }).then((r) => r.data)
  },
  rosterSave: (payload: { teamId: number; month: string; mode: 'IMPORT' | 'MERGE'; rows: RosterRowSave[] }) =>
    api.post<RosterSaveResult>('/admin/roster/save', payload).then((r) => r.data),
  roster: (teamId: number | null, month: string) =>
    api.get<RosterMonthData>('/admin/roster', { params: { teamId: teamId ?? undefined, month } }).then((r) => r.data),
  rosterMonths: (teamId?: number | null) =>
    api.get<string[]>('/admin/roster/months', { params: { teamId: teamId ?? undefined } }).then((r) => r.data),
  rosterDelete: (id: number) => api.delete(`/admin/roster/${id}`),

  rosterMonthly: (params: Record<string, unknown>) =>
    api.get<RosterMonthlyData>('/roster/monthly', { params }).then((r) => r.data),
  rosterMonthlyMeta: (params: Record<string, unknown>) =>
    api.get<RosterPageMeta>('/roster/monthly/meta', { params }).then((r) => r.data),
  rosterMonthlySave: (changes: RosterCellEdit[]) =>
    api.post<RosterBatchSaveResult>('/admin/roster/monthly/save', { changes }).then((r) => r.data),
  rosterToday: (params: Record<string, unknown>) =>
    api.get<RosterTodayData>('/roster/today', { params }).then((r) => r.data),

  rosterStatusDetail: (employeeId: string, date: string) =>
    api.get<RosterStatusDetail>('/roster/status-detail', { params: { employeeId, date } }).then((r) => r.data),
  saveRosterStatusDetail: (employeeId: string, date: string, description: string) =>
    api.post<import('@/types').RosterStatusDetail>('/admin/roster/status-detail', {
      employeeId,
      date,
      description: description || null,
    }).then((r) => r.data),
  clearRosterStatusDetail: (employeeId: string, date: string) =>
    api.delete<import('@/types').RosterStatusDetail>('/admin/roster/status-detail', {
      params: { employeeId, date },
    }).then((r) => r.data),

  broadcastNotification: (payload: { title: string; body?: string; type: string }) =>
    api.post('/admin/notifications/broadcast', payload),

  historicalInspect: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<HistoricalInspectResponse>('/admin/historical/inspect', form).then((r) => r.data)
  },
  historicalPreview: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<HistoricalPreviewResponse>('/admin/historical/preview', form).then((r) => r.data)
  },
  historicalPreviewOf: (importId: number, page = 0, size = 50) =>
    api.get<HistoricalPreviewResponse>(`/admin/historical/imports/${importId}/preview`, { params: { page, size } })
      .then((r) => r.data),
  historicalMapStaged: (importId: number, body: HistoricalMapStagedRequest) =>
    api.post<HistoricalMapStagedResponse>(`/admin/historical/imports/${importId}/map`, body).then((r) => r.data),
  historicalReport: (importId: number) =>
    api.get<Blob>(`/admin/historical/imports/${importId}/report`, { responseType: 'blob' }).then((r) => r.data),
  historicalCommit: (importId: number, teamId?: number | null) =>
    api.post<HistoricalCommitResponse>(`/admin/historical/imports/${importId}/commit`, null, {
      params: { teamId: teamId ?? undefined },
    }).then((r) => r.data),
  historicalHistory: () => api.get<HistoricalHistoryItem[]>('/admin/historical/imports').then((r) => r.data),
  historicalStatuses: () => api.get<HistoricalStatusItem[]>('/admin/historical/statuses').then((r) => r.data),
  historicalUnknownCodes: () =>
    api.get<HistoricalUnknownCode[]>('/admin/historical/unknown-codes').then((r) => r.data),
  historicalMapUnknown: (from: string, to: string) =>
    api.post<HistoricalMapUnknownResponse>('/admin/historical/unknown-codes/map', { from, to }).then((r) => r.data),
  historicalRecords: (params: Record<string, unknown>) =>
    api.get<HistoricalRecordsPage>('/admin/historical/records', { params }).then((r) => r.data),

  attendanceHistory: (params: Record<string, unknown>) =>
    api.get<AttendanceHistoryResponse>('/admin/attendance-history', { params }).then((r) => r.data),
  attendanceHistoryMeta: () =>
    api.get<AttendanceHistoryMeta>('/admin/attendance-history/meta').then((r) => r.data),
  attendanceHistoryExport: (params: Record<string, unknown>, format: 'csv' | 'xlsx') =>
    api.get<Blob>('/admin/attendance-history/export', {
      params: { ...params, format },
      responseType: 'blob',
    }).then((r) => r.data),

  employeeHistoricalAttendance: (id: number, month?: string) =>
    api.get<EmployeeHistoricalAttendance>(`/admin/employees/${id}/historical-attendance`, {
      params: { month: month ?? undefined },
    }).then((r) => r.data),
}