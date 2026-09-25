import { describe, expect, it } from 'vitest'
import { formatShiftDisplay, formatShiftTime, parseShiftStartTime, to24hShift } from '@/utils/shift'

describe('parseShiftStartTime', () => {
  it('converts shift start to minutes since midnight', () => {
    expect(parseShiftStartTime('05:30-14:30')).toBe(330)
    expect(parseShiftStartTime('13:30-22:30')).toBe(810)
    expect(parseShiftStartTime('17:00-02:00')).toBe(1020)
    expect(parseShiftStartTime('19:00-04:00')).toBe(1140)
    expect(parseShiftStartTime('21:00-06:00')).toBe(1260)
    expect(parseShiftStartTime('08:00-17:00')).toBe(480)
    expect(parseShiftStartTime('00:00-08:00')).toBe(0)
    expect(parseShiftStartTime('12:00-21:00')).toBe(720)
  })

  it('returns null for unknown or malformed shifts', () => {
    for (const value of [null, undefined, '', '  ', 'Unknown Band', 'Day Shift', '05:30', '24:00-14:30', '05:30-25:00', '05:99-14:30']) {
      expect(parseShiftStartTime(value)).toBeNull()
    }
  })
})

describe('formatShiftTime', () => {
  it('renders 12-hour AM/PM with leading zeros', () => {
    expect(formatShiftTime('05:30-14:30')).toBe('05:30 AM - 02:30 PM')
    expect(formatShiftTime('13:30-22:30')).toBe('01:30 PM - 10:30 PM')
    expect(formatShiftTime('17:00-02:00')).toBe('05:00 PM - 02:00 AM')
    expect(formatShiftTime('19:00-04:00')).toBe('07:00 PM - 04:00 AM')
    expect(formatShiftTime('21:00-06:00')).toBe('09:00 PM - 06:00 AM')
    expect(formatShiftTime('08:00-17:00')).toBe('08:00 AM - 05:00 PM')
    expect(formatShiftTime('00:00-08:00')).toBe('12:00 AM - 08:00 AM')
    expect(formatShiftTime('12:00-21:00')).toBe('12:00 PM - 09:00 PM')
  })

  it('returns null for unknown or malformed shifts', () => {
    for (const value of [null, undefined, '', '  ', 'UK Shift', '05:30', '17:00-04:00-11:00']) {
      expect(formatShiftTime(value)).toBeNull()
    }
  })
})

describe('formatShiftDisplay', () => {
  it('renders AM/PM when the shift is parseable', () => {
    expect(formatShiftDisplay('05:30-14:30')).toBe('05:30 AM - 02:30 PM')
    expect(formatShiftDisplay('19:00-04:00')).toBe('07:00 PM - 04:00 AM')
  })

  it('falls back to the raw value for unparseable shifts', () => {
    expect(formatShiftDisplay('UK Shift')).toBe('UK Shift')
    expect(formatShiftDisplay('Day')).toBe('Day')
  })

  it('renders em-dash when missing', () => {
    expect(formatShiftDisplay(null)).toBe('—')
    expect(formatShiftDisplay(undefined)).toBe('—')
    expect(formatShiftDisplay('')).toBe('—')
  })
})

describe('to24hShift', () => {
  it('converts AM/PM shift back to the stored 24-hour format', () => {
    expect(to24hShift('05:30 AM - 02:30 PM')).toBe('05:30-14:30')
    expect(to24hShift('07:00 PM - 04:00 AM')).toBe('19:00-04:00')
    expect(to24hShift('12:00 AM - 08:00 AM')).toBe('00:00-08:00')
    expect(to24hShift('12:30 PM - 09:00 PM')).toBe('12:30-21:00')
    expect(to24hShift(' 07:00 PM  - 04:00 AM ')).toBe('19:00-04:00')
  })

  it('returns null for values that are not AM/PM shifts', () => {
    for (const value of [null, undefined, '', '19:00-04:00', 'Day Shift', '13:00 PM - 01:00 AM', '19:00 04:00']) {
      expect(to24hShift(value)).toBeNull()
    }
  })
})