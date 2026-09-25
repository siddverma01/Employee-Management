import { describe, expect, it } from 'vitest'
import { calculateLeaveDays } from '@/pages/employee/ApplyLeavePage'
import { attendanceShort, daysBetween, formatDate, HPE_HOLIDAY_BADGE, HPE_HOLIDAY_LABEL, initials, isHpeHoliday, toISODate } from '@/utils'

describe('date utilities', () => {
  it('toISODate pads month and day', () => {
    expect(toISODate(new Date(2026, 8, 5))).toBe('2026-09-05')
    expect(toISODate(new Date(2026, 11, 31))).toBe('2026-12-31')
  })

  it('daysBetween counts inclusive days', () => {
    expect(daysBetween('2026-09-01', '2026-09-05')).toBe(5)
    expect(daysBetween('2026-09-01', '2026-09-01')).toBe(1)
  })

  it('formatDate renders a medium date', () => {
    expect(formatDate('2026-09-18')).toContain('2026')
    expect(formatDate(null)).toBe('—')
  })
})

describe('calculateLeaveDays', () => {
  it('excludes weekends', () => {
    // 2026-09-14 (Mon) to 2026-09-18 (Fri) = 5 days, skipping Sat/Sun
    expect(calculateLeaveDays('2026-09-14', '2026-09-18', new Set())).toBe(5)
    // 2026-09-12 (Sat) to 2026-09-13 (Sun) = 0 working days
    expect(calculateLeaveDays('2026-09-12', '2026-09-13', new Set())).toBe(0)
  })

  it('excludes holidays', () => {
    const holidays = new Set(['2026-09-16'])
    expect(calculateLeaveDays('2026-09-14', '2026-09-18', holidays)).toBe(4)
  })
})

describe('misc utils', () => {
  it('initials extracts up to two initials', () => {
    expect(initials('John Doe')).toBe('JD')
    expect(initials('Jane')).toBe('J')
    expect(initials()).toBe('?')
  })

  it('attendanceShort produces compact labels', () => {
    expect(attendanceShort('WORK_FROM_OFFICE')).toBe('WFO')
    expect(attendanceShort('WORK_FROM_HOME')).toBe('WFH')
    expect(attendanceShort('COMP_OFF')).toBe('CO')
  })
})

describe('isHpeHoliday', () => {
  it('matches a holiday flagged as HPE_HOLIDAY by the backend', () => {
    expect(
      isHpeHoliday({ kind: 'HOLIDAY', extra: { holidayType: 'HPE_HOLIDAY', country: 'IN' } }),
    ).toBe(true)
  })

  it('ignores regular holidays, leaves, events and birthdays', () => {
    expect(isHpeHoliday({ kind: 'HOLIDAY', extra: { holidayType: 'PUBLIC' } })).toBe(false)
    expect(isHpeHoliday({ kind: 'LEAVE', extra: { holidayType: 'HPE_HOLIDAY' } })).toBe(false)
    expect(isHpeHoliday({ kind: 'EVENT', extra: {} })).toBe(false)
    expect(isHpeHoliday({ kind: 'BIRTHDAY', extra: {} })).toBe(false)
  })

  it('never matches on employee/entitlement data - only holiday metadata', () => {
    expect(isHpeHoliday({ kind: 'HOLIDAY', extra: { entitlement: 'AVAILABLE' } })).toBe(false)
    expect(isHpeHoliday({ kind: 'COMP_OFF', extra: { holidayType: 'HPE_HOLIDAY' } })).toBe(false)
  })

  it('exposes the agreed labels', () => {
    expect(HPE_HOLIDAY_BADGE).toBe('HPEH')
    expect(HPE_HOLIDAY_LABEL).toBe('HPE Holiday')
  })
})