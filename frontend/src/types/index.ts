export type Role = 'EMPLOYEE' | 'ADMIN'

export interface AuthUser {
  id: number
  email: string
  role: Role
  employeeCode: string | null
  fullName: string | null
  avatar: string | null
  employeeId: number | null
  teamId?: number | null
  teamName?: string | null
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: AuthUser
}

export interface Department {
  id: number
  name: string
  description: string | null
}

export interface Summary {
  id: number
  employeeCode: string
  fullName: string
  email: string
  phone: string | null
  designation: string | null
  department: string | null
  location: string | null
  shift: string | null
  weekOff: string | null
  dateOfJoining: string | null
  employmentStatus: 'ACTIVE' | 'INACTIVE'
  avatar: string | null
  managerId: number | null
  managerName: string | null
}

export interface Stats {
  totalDaysSinceJoining: number
  totalWorkingDays: number
  workFromOfficeDays: number
  workFromHomeDays: number
  totalLeaveDays: number
  plDays: number
  slDays: number
  coDays: number
  pendingLeaves: number
  approvedLeaves: number
  rejectedLeaves: number
}

export interface LeaveBalance {
  leaveType: string
  leaveTypeCode: string
  leaveTypeLabel: string
  allocated: number
  used: number
  available: number
}

export interface Profile {
  id: number
  employeeCode: string
  fullName: string
  email: string
  phone: string | null
  department: string | null
  departmentId: number | null
  designation: string | null
  manager: string | null
  managerId: number | null
  location: string | null
  shift: string | null
  weekOff: string | null
  dateOfJoining: string | null
  dateOfBirth: string | null
  employmentStatus: 'ACTIVE' | 'INACTIVE'
  avatar: string | null
  stats: Stats
  leaveBalances: LeaveBalance[]
}

export type LeaveType = 'PRIVILEGE_LEAVE' | 'SICK_LEAVE' | 'COMP_OFF'
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
export type AttendanceType =
  | 'WORK_FROM_OFFICE'
  | 'WORK_FROM_HOME'
  | 'LEAVE'
  | 'PRIVILEGE_LEAVE'
  | 'SICK_LEAVE'
  | 'COMP_OFF'
  | 'FURLOUGH'
  | 'HOLIDAY'
  | 'WEEK_OFF'
  | 'ATTRITION'

export interface Leave {
  id: number
  employeeId: number
  employeeCode: string
  employeeName: string
  department: string | null
  leaveType: LeaveType
  leaveTypeCode: string
  leaveTypeLabel: string
  startDate: string
  endDate: string
  days: number
  reason: string | null
  attachment: string | null
  status: LeaveStatus
  rejectionReason: string | null
  appliedOn: string
  decidedAt: string | null
}

export interface SwapOff {
  id: number
  employeeId: number
  employeeCode: string
  employeeName: string
  department: string | null
  workedForEmployeeId: number
  workedForEmployeeCode: string
  workedForEmployeeName: string
  workedDate: string
  requestedOffDate: string
  reason: string | null
  attachment: string | null
  status: LeaveStatus
  rejectionReason: string | null
  compOffCredited: boolean
  appliedOn: string
  decidedAt: string | null
}

export type ScopeType = 'GLOBAL' | 'TEAM'

export interface Holiday {
  id: number
  name: string
  date: string
  country: string
  holidayType: 'PUBLIC' | 'OPTIONAL' | 'OBSERVED' | 'HPE_HOLIDAY'
  description: string | null
  scope: ScopeType
  teamId: number | null
}

export interface CompanyEvent {
  id: number
  title: string
  description: string | null
  eventDate: string
  eventType: string
  scope: ScopeType
  teamId: number | null
}

export interface CalendarEvent {
  id: number
  kind: 'LEAVE' | 'COMP_OFF' | 'HOLIDAY' | 'BIRTHDAY' | 'EVENT'
  date: string
  startDate: string
  endDate: string
  title: string
  subtitle: string | null
  employeeName: string | null
  employeeCode: string | null
  employeeId: number | null
  leaveType: string | null
  leaveTypeCode: string | null
  leaveTypeLabel?: string | null
  status: string | null
  description: string | null
  extra: Record<string, unknown>
}

export interface NotificationItem {
  id: number
  title: string
  body: string | null
  type: string
  link: string | null
  read: boolean
  createdAt: string
}

export type AvailabilityStatus = 'WORKING' | 'WFH' | 'OFF' | 'WEEK_OFF'

export interface TeamMemberAvailability {
  employeeId: number
  employeeCode: string
  fullName: string
  status: AvailabilityStatus
  shift: string | null
  location: string | null
  weekOff: string | null
  designation: string | null
  avatar: string | null
}

export interface TeamDashboard {
  teamId: number | null
  teamName: string | null
  mode: 'FULL' | 'BASIC'
  workingToday: number
  wfhToday: number
  onLeaveToday: number
  weekOffToday: number
  members: TeamMemberAvailability[]
}

export interface TodayStatus {
  mode: 'FULL' | 'BASIC'
  teamId: number | null
  teamName: string | null
  working: TodayEntry[]
  onLeave: TodayEntry[]
  members: TeamMemberAvailability[]
}

export interface TodayEntry {
  employeeId: number
  employeeCode: string
  fullName: string
  department: string | null
  designation: string | null
  avatar: string | null
  attendanceType: AttendanceType
  leaveType: LeaveType | null
  location: string | null
}

export interface UpcomingItem {
  date: string
  name: string
  type: string
}

export interface EmployeeDashboard {
  fullName: string
  department: string | null
  designation: string | null
  profilePicture: string | null
  today: string
  todayType: AttendanceType | 'LEAVE' | string
  leaveBalances: LeaveBalance[]
  wfhDays: number
  wfoDays: number
  pendingLeaves: number
  approvedUpcomingLeaves: number
  upcomingHolidays: UpcomingItem[]
  upcomingBirthdays: UpcomingItem[]
}

export interface AdminSummary {
  totalEmployees: number
  activeEmployees: number
  onLeaveToday: number
  workingToday: number
  pendingLeaves: number
  pendingSwapOffs: number
  wfhToday: number
  wfoToday: number
}

export interface Attendance {
  id: number
  employeeId: number
  employeeCode: string
  employeeName: string
  department: string | null
  date: string
  attendanceType: AttendanceType
  source: string
  remarks: string | null
}

export interface AuditLog {
  id: number
  userEmail: string
  action: string
  entityType: string | null
  entityId: string | null
  oldValue: Record<string, unknown> | null
  newValue: Record<string, unknown> | null
  ipAddress: string | null
  createdAt: string
}

export interface ImportUpload {
  importId: number
  fileName: string
  originalFileName: string
  status: string
  headers: string[]
  totalRows: number
  suggestedMapping: Record<string, string>
}

export interface ImportPreview {
  importId: number
  status: string
  headers: string[]
  mapping: Record<string, unknown>
  totalRows: number
  validRows: number
  invalidRows: number
  duplicateRows: number
  rows: ImportRow[]
}

export interface ImportRowDetail {
  sheet: string | null
  row: number | null
  column: string | null
  employeeId: string | null
  employeeName: string | null
  date: string | null
  rawValue: string | null
  errorType: string
  message: string
}

export interface ImportRow {
  rowNumber: number
  data: Record<string, unknown>
  status: string
  errors: string[]
  details?: ImportRowDetail[]
}

export interface ImportCommit {
  importId: number
  status: string
  totalRows: number
  validRows: number
  invalidRows: number
  duplicateRows: number
  importedRows: number
}

export interface ImportHistoryItem {
  id: number
  fileName: string
  originalFileName: string
  uploadedBy: string
  uploadedAt: string
  status: string
  totalRows: number
  validRows: number
  invalidRows: number
  duplicateRows: number
  importedRows: number
  committedAt: string | null
}

/** One roster row (employee) for a team + month; `days` keys are "yyyy-MM-dd". */
export interface RosterRow {
  id: number | null
  teamId?: number | null
  teamName?: string | null
  month: string
  employeeCode: string
  email: string | null
  employeeName: string
  location: string | null
  shift: string | null
  weekOff: string | null
  days: Record<string, string>
  status?: string
  errors?: string[]
}

/** Parsed + validated roster file, ready for the admin to confirm and import. */
export interface RosterImportPreview {
  teamId: number
  teamName: string
  month: string
  fileName: string
  totalRows: number
  newRows: number
  existingRows: number
  invalidRows: number
  rows: RosterRow[]
  usedStatuses: string[]
  warnings: string[]
}

/** A saved team-month roster grid. */
export interface RosterMonthData {
  teamId: number | null
  teamName: string | null
  month: string
  rows: RosterRow[]
  usedStatuses: string[]
}

export interface RosterRowSave {
  id?: number | null
  employeeCode: string
  email?: string | null
  employeeName: string
  location?: string | null
  shift?: string | null
  weekOff?: string | null
  days: Record<string, string>
}

export interface RosterSaveResult {
  teamId: number
  month: string
  imported: number
  updated: number
  skipped: number
  errors: string[]
}

/** One day column of the month-wise Attendance Roster grid. */
export interface RosterDayInfo {
  date: string
  dayNumber: number
  weekday: string
  weekend: boolean
  holiday: boolean
  holidayName: string | null
}

/** One employee row of the Attendance Roster grid; `days` keys are "yyyy-MM-dd". */
export interface RosterEmployeeRow {
  employeeId: string
  employeeName: string
  email: string | null
  location: string | null
  shift: string | null
  weekOff: string | null
  teamId: number | null
  teamName: string | null
  days: Record<string, string>
}

export interface RosterMonthlyData {
  month: string
  teamId: number | null
  teamName: string | null
  days: RosterDayInfo[]
  employees: RosterEmployeeRow[]
  counters: Record<string, number>
  totalEmployees: number
  matchedEmployees: number
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface RosterTodayData {
  date: string
  teamId: number | null
  teamName: string | null
  weekday: string
  weekend: boolean
  holiday: boolean
  holidayName: string | null
  employees: RosterEmployeeRow[]
  counters: Record<string, number>
  totalEmployees: number
  matchedEmployees: number
}

export interface RosterMetaTeam {
  id: number
  name: string
}

export interface RosterStatusOption {
  code: string
  name: string
  displayColor: string
}

export interface RosterPageMeta {
  teams: RosterMetaTeam[]
  months: string[]
  locations: string[]
  shifts: string[]
  statuses: RosterStatusOption[]
}

export interface RosterCellEdit {
  employeeId: string
  date: string
  statusCode: string
}

/** Status description (reason) attached to one roster attendance cell. When
 *  the status comes from an approved Leave / Swap Off request,
 *  sourceRequestType/sourceReason carry the original reason, submitter and the
 *  actual approver (resolved server-side — never client supplied).
 *  For Swap Off, workedForName/workedDate identify the employee worked for and the worked date. */
export interface RosterStatusDetail {
  employeeId: string
  date: string
  statusCode: string | null
  statusName: string | null
  description: string | null
  createdByName: string | null
  createdAt: string | null
  updatedByName: string | null
  updatedAt: string | null
  sourceRequestId: number | null
  sourceRequestType: 'LEAVE' | 'SWAP_OFF' | null
  sourceReason: string | null
  submittedByName: string | null
  approvedByName: string | null
  approvedAt: string | null
  workedForName: string | null
  workedDate: string | null
}

export interface RosterBatchSaveResult {
  saved: number
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ChartData {
  labels: string[]
  values: number[]
}

export interface ApiError {
  message: string
  status?: number
  fieldErrors?: { field: string; message: string }[]
}

// ---------------------------------------------------------------- Historical import

export interface HistoricalInspectResponse {
  fileName: string
  fileSize: number
  sheetCount: number
  sheets: string[]
}

export interface HistoricalSheetAnalysis {
  sheetName: string
  month: string | null
  headerRow: number
  employeeColumns: number
  dateColumns: number
  employeeCount: number
  cellCount: number
  unknownCodeCount: number
  emptyCellCount: number
  skipped: boolean
  skipReason: string | null
  importable: boolean
  warnings: string[]
}

export interface HistoricalValidationIssue {
  severity: 'ERROR' | 'WARN' | 'INFO'
  type: string
  message: string
  sheetName: string | null
  row: number | null
  employeeId: string | null
  count: number
}

export interface HistoricalUnknownCodeDetail {
  code: string
  example: string | null
  count: number
  suggested: string | null
}

export interface HistoricalSummary {
  totalSheets: number
  sheetsImported: number
  sheetsSkipped: number
  employeesDetected: number
  recordsDetected: number
  newRecords: number
  updatedRecords: number
  duplicateRecords: number
  unknownCodes: number
  invalidRows: number
  warnings: number
  errors: number
  importableRows: number
  failedRows: number
  sheetDetails: HistoricalSheetAnalysis[]
}

export interface HistoricalRowView {
  id: number
  sheetName: string
  sourceRow: number
  employeeId: string
  employeeName: string | null
  attendanceDate: string | null
  existingStatus: string | null
  incomingStatus: string
  statusName: string
  action: 'INSERT' | 'UPDATE' | 'DUPLICATE' | 'INVALID'
  warning: string | null
  unknown: boolean
  location: string | null
  shift: string | null
}

export interface HistoricalPreviewResponse {
  importId: number
  fileName: string
  originalFileName: string
  status: string
  summary: HistoricalSummary
  totalRows: number
  page: number
  size: number
  rows: HistoricalRowView[]
  analysis: HistoricalSheetAnalysis[]
  issues: HistoricalValidationIssue[]
  unknownCodes: HistoricalUnknownCodeDetail[]
}

export interface HistoricalImportResult {
  employees: number
  attendanceRecords: number
  inserted: number
  updated: number
  duplicatesSkipped: number
  warnings: number
  unknownStatuses: number
  failedRows: number
}

export interface HistoricalCommitResponse {
  importId: number
  fileName: string
  originalFileName: string
  status: string
  summary: HistoricalSummary
  committedRows: number
  result: HistoricalImportResult
}

export interface HistoricalMapStagedRequest {
  from: string
  to?: string
}

export interface HistoricalMapStagedResponse {
  mapped: number
  from: string
  to: string | null
  toName: string | null
}

export interface HistoricalHistoryItem {
  id: number
  fileName: string
  originalFileName: string
  importedAt: string
  importedBy: string | null
  status: string
  totalSheets: number
  sheetsImported: number
  sheetsSkipped: number
  employeesDetected: number
  recordsDetected: number
  newRecords: number
  updatedRecords: number
  duplicateRecords: number
  unknownCodes: number
  invalidRows: number
  warnings: number
  errors: number
  failedRows: number
}

export interface HistoricalStatusItem {
  code: string
  name: string
  description: string | null
  displayColor: string | null
}

export interface HistoricalUnknownCode {
  code: string
  count: number
}

export interface HistoricalMapUnknownResponse {
  mapped: number
  to: string
  toName: string
}

export interface HistoricalRecordView {
  id: number
  employeeId: string
  employeeName: string | null
  attendanceDate: string
  statusCode: string
  statusName: string
  unknown: boolean
  sourceSheet: string | null
  sourceRow: number | null
  sourceFile: string | null
  importedAt: string
}

export interface HistoricalRecordsPage {
  records: HistoricalRecordView[]
  total: number
  page: number
  size: number
}

// ---------------------------------------------------------------- Attendance history (admin)

export interface AttendanceHistoryRecord {
  date: string
  employeeId: string
  employeeName: string | null
  teamId: number | null
  teamName: string | null
  location: string | null
  shift: string | null
  statusCode: string
  statusName: string | null
  sourceMonth: string | null
  sourceSheet: string | null
  sourceFile: string | null
  sourceRow: number | null
  importedAt: string | null
  unknown: boolean
}

export interface AttendanceHistorySummary {
  total: number
  byStatus: Record<string, number>
}

export interface AttendanceHistoryResponse {
  records: AttendanceHistoryRecord[]
  summary: AttendanceHistorySummary
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AttendanceHistoryMeta {
  teams: RosterMetaTeam[]
  months: string[]
  years: string[]
  locations: string[]
  shifts: string[]
  statuses: RosterStatusOption[]
}

// ---------------------------------------------------------------- Historical attendance (employee profile)

export interface EmployeeHistoricalOverview {
  dateJoined: string
  totalDays: number
  byStatus: Record<string, number>
}

export interface EmployeeHistoricalMonthStat {
  month: string
  counts: Record<string, number>
}

export interface EmployeeHistoricalCalendarDay {
  date: string
  statusCode: string | null
  statusName: string | null
  unknown: boolean
  weekend: boolean
}

export interface EmployeeHistoricalCalendar {
  month: string
  days: EmployeeHistoricalCalendarDay[]
}

export interface EmployeeHistoricalAttendance {
  found: boolean
  employeeId: string
  employeeName: string | null
  overview: EmployeeHistoricalOverview | null
  months: string[]
  monthly: EmployeeHistoricalMonthStat[]
  calendar: EmployeeHistoricalCalendar | null
}