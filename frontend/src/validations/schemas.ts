import { z } from 'zod'

export const loginSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
  rememberMe: z.boolean().optional(),
})

export type LoginForm = z.infer<typeof loginSchema>

export const leaveSchema = z
  .object({
    leaveType: z.string().min(1, 'Leave type is required'),
    startDate: z.string().min(1, 'Start date is required'),
    endDate: z.string().min(1, 'End date is required'),
    reason: z.string().min(3, 'Please provide a reason (min 3 characters)'),
    attachment: z.string().optional().nullable(),
    /** Only meaningful for COMP_OFF: the earned HPE Holiday entitlement being consumed. */
    hpeEntitlementId: z.string().optional(),
  })
  .refine((v) => !v.startDate || !v.endDate || v.startDate <= v.endDate, {
    message: 'Start date cannot be after end date',
    path: ['endDate'],
  })
  .superRefine((v, ctx) => {
    if (v.leaveType === 'COMP_OFF' && !v.hpeEntitlementId) {
      ctx.addIssue({ code: 'custom', message: 'Select the HPE Holiday to use', path: ['hpeEntitlementId'] })
    }
    if (v.leaveType !== 'COMP_OFF' && v.hpeEntitlementId) {
      ctx.addIssue({
        code: 'custom',
        message: 'An HPE Holiday can only be applied to Compensatory Off',
        path: ['hpeEntitlementId'],
      })
    }
  })

export type LeaveForm = z.infer<typeof leaveSchema>

export const swapOffSchema = z
  .object({
    workedDate: z.string().min(1, 'Worked date is required'),
    requestedOffDate: z.string().min(1, 'Requested off date is required'),
    workedForEmployeeId: z.string().min(1, 'Working on behalf of is required'),
    reason: z.string().min(3, 'Please provide a reason (min 3 characters)'),
    attachment: z.string().optional().nullable(),
  })
  .refine((v) => !v.workedDate || !v.requestedOffDate || v.workedDate !== v.requestedOffDate, {
    message: 'Worked date and requested off date must be different',
    path: ['requestedOffDate'],
  })

export type SwapOffForm = z.infer<typeof swapOffSchema>

export const employeeSchema = z.object({
  employeeCode: z.string().min(1, 'Employee code is required'),
  fullName: z.string().min(2, 'Full name is required'),
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
  role: z.enum(['EMPLOYEE', 'ADMIN']).default('EMPLOYEE'),
  password: z.string().optional(),
  departmentId: z.preprocess((v) => (v === '' || v == null ? undefined : Number(v)), z.number({ required_error: 'Team is required' })),
  managerId: z.coerce.number().optional().nullable(),
  phone: z.string().optional().nullable(),
  designation: z.string().optional().nullable(),
  location: z.string().optional().nullable(),
  shift: z.string().optional().nullable(),
  weekOff: z.string().optional().nullable(),
  dateOfJoining: z.string().min(1, 'Date of joining is required'),
  dateOfBirth: z.string().optional().nullable(),
})

export type EmployeeForm = z.infer<typeof employeeSchema>

export const holidaySchema = z.object({
  name: z.string().min(2, 'Holiday name is required'),
  date: z.string().min(1, 'Date is required'),
  country: z.string().default('US'),
  holidayType: z.enum(['PUBLIC', 'OPTIONAL', 'OBSERVED', 'HPE_HOLIDAY']).default('PUBLIC'),
  applicableLocations: z.enum(['ALL', 'PUNE_MUMBAI', 'BANGALORE', 'DELHI', 'HYDERABAD', 'CHENNAI', 'KOLKATA', 'US']).default('ALL'),
  description: z.string().optional().nullable(),
  scope: z.enum(['GLOBAL', 'TEAM']).default('GLOBAL'),
  teamId: z.preprocess((v) => (v === '' || v == null ? undefined : Number(v)), z.number().optional()),
}).superRefine((v, ctx) => {
  if (v.scope === 'TEAM' && v.teamId == null) {
    ctx.addIssue({ code: 'custom', message: 'Team is required for a team-scoped holiday', path: ['teamId'] })
  }
})

export type HolidayForm = z.infer<typeof holidaySchema>

export const eventSchema = z.object({
  title: z.string().min(2, 'Event title is required'),
  eventDate: z.string().min(1, 'Date is required'),
  eventType: z.string().default('COMPANY_EVENT'),
  description: z.string().optional().nullable(),
  scope: z.enum(['GLOBAL', 'TEAM']).default('GLOBAL'),
  teamId: z.preprocess((v) => (v === '' || v == null ? undefined : Number(v)), z.number().optional()),
}).superRefine((v, ctx) => {
  if (v.scope === 'TEAM' && v.teamId == null) {
    ctx.addIssue({ code: 'custom', message: 'Team is required for a team-scoped event', path: ['teamId'] })
  }
})

export type EventForm = z.infer<typeof eventSchema>

export const rejectSchema = z.object({
  rejectionReason: z.string().min(2, 'A rejection reason is required'),
})

export type RejectForm = z.infer<typeof rejectSchema>

export const attendanceSchema = z.object({
  employeeCode: z.string().min(1, 'Employee code is required'),
  date: z.string().min(1, 'Date is required'),
  attendanceType: z.string().min(1, 'Attendance type is required'),
  remarks: z.string().optional().nullable(),
})

export type AttendanceForm = z.infer<typeof attendanceSchema>