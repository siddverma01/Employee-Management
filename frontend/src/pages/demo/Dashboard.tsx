import { Users, CalendarCheck, CalendarOff, TrendingUp } from 'lucide-react'
import { StatCard } from '@/components/ui/StatCard'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { StatusBadge } from '@/components/ui/StatusBadge'

/**
 * Theming demo — sample Dashboard wrapped in MainLayout.
 * Proves Light/Dark theming: cards, inputs, buttons, badges and the active-menu
 * indicators all flip through the tokens in src/styles/tokens.css when the
 * theme toggle in the header changes.
 */
export function Dashboard() {
  return (
    <div className="space-y-6">
      <PageHeader title="Theming demo" subtitle="A sample dashboard proving the Light / Dark design system" />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Team size" value={23} icon={<Users className="h-5 w-5" />} accent="brand" />
        <StatCard label="Present today" value={21} icon={<CalendarCheck className="h-5 w-5" />} accent="emerald" />
        <StatCard label="On leave" value={2} icon={<CalendarOff className="h-5 w-5" />} accent="amber" />
        <StatCard label="Attendance rate" value="94%" icon={<TrendingUp className="h-5 w-5" />} accent="info" />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="card space-y-5 p-5">
          <h2 className="text-base font-semibold text-surface-800">Controls</h2>
          <div className="space-y-4">
            <Input label="Full name" placeholder="Jane Smith" />
            <Select label="Team" options={[{ value: '', label: 'All Teams' }, { value: '1', label: 'HPE Voice Engineering' }]} />
            <div className="flex flex-wrap gap-2">
              <Button>Primary</Button>
              <Button variant="secondary">Secondary</Button>
              <Button variant="danger">Danger</Button>
              <Button variant="ghost">Ghost</Button>
            </div>
          </div>
        </div>

        <div className="card space-y-5 p-5">
          <h2 className="text-base font-semibold text-surface-800">Status badges</h2>
          <div className="flex flex-wrap gap-2">
            <StatusBadge status="PRESENT" />
            <StatusBadge status="ON_LEAVE" />
            <StatusBadge status="WEEK_OFF" />
            <StatusBadge status="PENDING" />
            <StatusBadge status="APPROVED" />
            <StatusBadge status="REJECTED" />
          </div>
          <h3 className="pt-2 text-sm font-semibold text-surface-800">Active menu item</h3>
          <p className="text-sm text-surface-500">
            The active sidebar entry uses the Active Menu Background token with a 4px brand left-border:
          </p>
          <div className="flex items-center gap-3 rounded-md border-l-4 border-brand bg-surface-100 px-3 py-2 text-sm font-semibold text-surface-800">
            <CalendarCheck className="h-5 w-5" /> Sample active navigation item
          </div>
        </div>
      </div>

      <div className="card p-5">
        <h2 className="mb-3 text-base font-semibold text-surface-800">Theme tokens</h2>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {[
            { name: 'Main background', className: 'bg-surface-50' },
            { name: 'Sidebar / cards', className: 'bg-surface-0' },
            { name: 'Active menu', className: 'bg-surface-100' },
            { name: 'Brand accent', className: 'bg-brand-500' },
          ].map((s) => (
            <div key={s.name} className="text-sm">
              <div className={`mb-1 h-10 rounded border border-surface-200 ${s.className}`} />
              <p className="text-surface-500">{s.name}</p>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}