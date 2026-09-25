import { describe, expect, it } from 'vitest'
import { employeeSchema, holidaySchema, leaveSchema, loginSchema, swapOffSchema } from '@/validations/schemas'

describe('validation schemas', () => {
  it('loginSchema requires email and password', () => {
    expect(loginSchema.safeParse({ email: '', password: '' }).success).toBe(false)
    expect(loginSchema.safeParse({ email: 'bad', password: 'x' }).success).toBe(false)
    expect(loginSchema.safeParse({ email: 'a@b.com', password: 'x' }).success).toBe(true)
  })

  it('leaveSchema rejects endDate before startDate', () => {
    const result = leaveSchema.safeParse({
      leaveType: 'PRIVILEGE_LEAVE',
      startDate: '2026-09-20',
      endDate: '2026-09-10',
      reason: 'some good reason',
    })
    expect(result.success).toBe(false)
    if (!result.success) expect(result.error.issues[0].path).toContain('endDate')
  })

  it('leaveSchema passes valid range', () => {
    expect(
      leaveSchema.safeParse({ leaveType: 'SICK_LEAVE', startDate: '2026-09-14', endDate: '2026-09-15', reason: 'not feeling well' }).success,
    ).toBe(true)
  })

  it('swapOffSchema rejects same worked and off date', () => {
    expect(
      swapOffSchema.safeParse({
        workedDate: '2026-09-20', requestedOffDate: '2026-09-20', workedForEmployeeId: '12', reason: 'swap day',
      }).success,
    ).toBe(false)
    expect(
      swapOffSchema.safeParse({
        workedDate: '2026-09-20', requestedOffDate: '2026-09-25', workedForEmployeeId: '12', reason: 'swap day',
      }).success,
    ).toBe(true)
  })

  it('employeeSchema requires code, team, name and valid email', () => {
    expect(employeeSchema.safeParse({ employeeCode: '', fullName: '', email: 'x', dateOfJoining: '' }).success).toBe(false)
    expect(
      employeeSchema.safeParse({ employeeCode: 'EMP-010', fullName: 'Jane Doe', email: 'jane@x.com', dateOfJoining: '2026-01-01' }).success,
    ).toBe(false)
    expect(
      employeeSchema.safeParse({ employeeCode: 'EMP-010', fullName: 'Jane Doe', email: 'jane@x.com', departmentId: 1, dateOfJoining: '2026-01-01' }).success,
    ).toBe(true)
  })

  it('holidaySchema requires name and date', () => {
    expect(holidaySchema.safeParse({ name: '', date: '' }).success).toBe(false)
    expect(holidaySchema.safeParse({ name: 'New Year', date: '2027-01-01', country: 'US' }).success).toBe(true)
  })

  it('holidaySchema validates team-scoped holidays', () => {
    const base = { name: 'Voice Offsite', date: '2027-02-01', country: 'US' }
    expect(holidaySchema.safeParse({ ...base, scope: 'TEAM' }).success).toBe(false)
    expect(holidaySchema.safeParse({ ...base, scope: 'TEAM', teamId: 2 }).success).toBe(true)
  })
})