import { describe, expect, it } from 'vitest'
import { calculateLeaveDays } from '@/pages/employee/ApplyLeavePage'
import { attendanceShort, daysBetween, formatDate, initials, toISODate } from '@/utils'
import { holidayLabel, holidayCompactLabel, resolveHolidayDisplayType, getHolidayDisplayInfo } from '@/constants/holidayStatus'

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

describe('holidayStatus', () => {
  it('resolves HPE Holiday from backend extra', () => {
    expect(resolveHolidayDisplayType({ holidayType: 'HPE_HOLIDAY', country: 'IN' })).toBe('HPE_HOLIDAY')
    expect(resolveHolidayDisplayType({ holidayType: 'PUBLIC', country: 'US' })).toBe('US')
    expect(resolveHolidayDisplayType({ holidayType: 'PUBLIC', country: 'IN' })).toBe('PUBLIC')
  })

  it('provides human-readable labels', () => {
    expect(holidayLabel('HPE_HOLIDAY')).toBe('HPE Holiday')
    expect(holidayLabel('US')).toBe('US Holiday')
    expect(holidayLabel('PUBLIC')).toBe('Public Holiday')
    expect(holidayCompactLabel('HPE_HOLIDAY')).toBe('HPEH')
    expect(holidayCompactLabel('US')).toBe('US')
    expect(holidayCompactLabel('PUBLIC')).toBe('PH')
  })

  it('getHolidayDisplayInfo returns label, compactLabel, and style for CalendarEvent', () => {
    const hpeEvent = { kind: 'HOLIDAY', extra: { holidayType: 'HPE_HOLIDAY', country: 'IN' } }
    const usEvent = { kind: 'HOLIDAY', extra: { holidayType: 'PUBLIC', country: 'US' } }
    const regularEvent = { kind: 'HOLIDAY', extra: { holidayType: 'PUBLIC', country: 'IN' } }

    expect(getHolidayDisplayInfo(hpeEvent).label).toBe('HPE Holiday')
    expect(getHolidayDisplayInfo(hpeEvent).compactLabel).toBe('HPEH')
    expect(getHolidayDisplayInfo(hpeEvent).style.backgroundColor).toBe('var(--holiday-hpe-bg)')

    expect(getHolidayDisplayInfo(usEvent).label).toBe('US Holiday')
    expect(getHolidayDisplayInfo(usEvent).compactLabel).toBe('US')
    expect(getHolidayDisplayInfo(usEvent).style.backgroundColor).toBe('var(--holiday-us-bg)')

    expect(getHolidayDisplayInfo(regularEvent).label).toBe('Public Holiday')
    expect(getHolidayDisplayInfo(regularEvent).compactLabel).toBe('PH')
  })

  it('ignores non-holiday kinds', () => {
    expect(getHolidayDisplayInfo({ kind: 'LEAVE', extra: {} }).label).toBe('')
    expect(getHolidayDisplayInfo({ kind: 'EVENT', extra: {} }).label).toBe('')
    expect(getHolidayDisplayInfo({ kind: 'BIRTHDAY', extra: {} }).label).toBe('')
  })
})