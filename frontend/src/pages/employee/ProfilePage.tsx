import { useQuery } from '@tanstack/react-query'
import { Briefcase, Building2, CalendarCheck, CalendarDays, Mail, MapPin, Phone, User } from 'lucide-react'
import { employeeApi } from '@/api'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatCard } from '@/components/ui/StatCard'
import { Avatar } from '@/components/ui/Avatar'
import { LoadingState } from '@/components/ui/LoadingState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { formatDate } from '@/utils'

function InfoRow({ icon, label, value }: { icon?: React.ReactNode; label: string; value?: string | null }) {
  return (
    <div className="flex items-start gap-3">
      {icon && <span className="mt-0.5 text-surface-400">{icon}</span>}
      <div className="min-w-0">
        <p className="text-xs text-surface-400">{label}</p>
        <p className="truncate text-sm font-medium text-surface-800">{value || '—'}</p>
      </div>
    </div>
  )
}

export function ProfilePage() {
  const { data: profile, isLoading } = useQuery({ queryKey: ['profile', 'me'], queryFn: employeeApi.me })

  if (isLoading) return <LoadingState label="Loading profile…" />
  if (!profile) return null

  const stats = profile.stats

  return (
    <div>
      <PageHeader title="My profile" subtitle="Your personal information and employment statistics" />

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Personal info card */}
        <div className="card overflow-hidden lg:col-span-1 h-fit">
          <div className="h-20 bg-surface-100" />
          <div className="-mt-10 flex justify-center">
            <Avatar name={profile.fullName} src={profile.avatar} size="lg" />
          </div>
          <div className="px-6 pb-6">
            <div className="mt-3 text-center">
              <h2 className="text-lg font-bold text-surface-900">{profile.fullName}</h2>
              <p className="text-sm text-surface-500">
                {profile.designation ?? '—'} {profile.department ? `· ${profile.department}` : ''}
              </p>
              <div className="mt-2 flex justify-center">
                <StatusBadge status={profile.employmentStatus} />
              </div>
            </div>
            <div className="mt-6 space-y-4">
              <InfoRow icon={<User className="h-4 w-4" />} label="Employee ID" value={profile.employeeCode} />
              <InfoRow icon={<Mail className="h-4 w-4" />} label="Email" value={profile.email} />
              <InfoRow icon={<Phone className="h-4 w-4" />} label="Phone" value={profile.phone} />
              <InfoRow icon={<Building2 className="h-4 w-4" />} label="Department" value={profile.department} />
              <InfoRow icon={<Briefcase className="h-4 w-4" />} label="Designation" value={profile.designation} />
              <InfoRow icon={<User className="h-4 w-4" />} label="Manager" value={profile.manager} />
              <InfoRow icon={<MapPin className="h-4 w-4" />} label="Location" value={profile.location} />
              <InfoRow icon={<CalendarCheck className="h-4 w-4" />} label="Date of joining" value={formatDate(profile.dateOfJoining)} />
              <InfoRow icon={<CalendarDays className="h-4 w-4" />} label="Date of birth" value={formatDate(profile.dateOfBirth)} />
            </div>
          </div>
        </div>

        {/* Statistics */}
        <div className="space-y-4 lg:col-span-2">
          <div className="grid grid-cols-2 gap-4">
            <StatCard label="Days since joining" value={stats.totalDaysSinceJoining} accent="brand" />
            <StatCard label="Total working days" value={stats.totalWorkingDays} accent="emerald" />
            <StatCard label="Work from office" value={stats.workFromOfficeDays} accent="brand" />
            <StatCard label="Work from home" value={stats.workFromHomeDays} accent="violet" />
            <StatCard label="Total leave days" value={stats.totalLeaveDays} accent="amber" />
            <StatCard label="Pending leave requests" value={stats.pendingLeaves} accent="red" />
            <StatCard label="Approved leaves" value={stats.approvedLeaves} accent="emerald" />
            <StatCard label="Rejected leaves" value={stats.rejectedLeaves} accent="red" />
          </div>

          <div className="card p-4">
            <h3 className="mb-1 text-sm font-semibold text-surface-700">Leave Summary</h3>
            <p className="mb-3 text-xs text-surface-400">PL, SL and CO usage from approved requests</p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {[
                { label: 'PL · Privilege Leave', value: stats.plDays },
                { label: 'SL · Sick Leave', value: stats.slDays },
                { label: 'CO · Compensatory Off', value: stats.coDays },
              ].map((l) => (
                <div key={l.label} className="rounded-lg border border-surface-200 bg-surface-50 px-4 py-3 text-center">
                  <p className="text-2xl font-bold text-brand-700">{l.value}</p>
                  <p className="mt-1 text-xs text-surface-500">{l.label}</p>
                </div>
              ))}
            </div>
          </div>

          <div className="card p-4">
            <h3 className="mb-3 text-sm font-semibold text-surface-700">Leave Balances</h3>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {profile.leaveBalances.map((b) => (
                <div key={b.leaveType} className="rounded-lg border border-surface-200 p-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-surface-800">{b.leaveTypeLabel}</span>
                    <span className="text-xs text-surface-400">{b.leaveTypeCode}</span>
                  </div>
                  <p className="mt-2">
                    <span className="text-2xl font-bold text-surface-900">{b.available}</span>
                    <span className="text-sm text-surface-400"> / {b.allocated}</span>
                  </p>
                  <p className="text-xs text-surface-400">{b.used} used</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}