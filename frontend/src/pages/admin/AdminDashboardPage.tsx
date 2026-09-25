import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { adminApi, departmentApi } from '@/api'
import { toISODate } from '@/utils'
import { chartTheme } from '@/utils/chartTheme'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatCard } from '@/components/ui/StatCard'
import { LoadingState } from '@/components/ui/LoadingState'
import { Select } from '@/components/ui/Select'

const CHART_NAMES = {
  leaveUsage: 'Leave usage by type',
  wfhWfo: 'WFH vs WFO',
  monthlyTrend: 'Monthly leave trend',
  departmentLeave: 'Leave by team',
}

type ChartKey = keyof typeof CHART_NAMES

function chartData(labels: string[], values: number[], name: string): { label: string; value: number }[] {
  return labels.map((label, i) => ({ label, value: values[i] ?? 0 }))
}

export function AdminDashboardPage() {
  const today = new Date()
  const [from, setFrom] = useState(toISODate(new Date(today.getFullYear(), 0, 1)))
  const [to, setTo] = useState(toISODate(today))
  const [chart, setChart] = useState<ChartKey>('leaveUsage')
  const [teamId, setTeamId] = useState('')

  const { data: departments } = useQuery({ queryKey: ['teams'], queryFn: departmentApi.list })
  const selectedTeam = teamId === '' ? undefined : Number(teamId)

  const { data: summary, isLoading: loadingSummary } = useQuery({
    queryKey: ['admin', 'dashboard', 'summary', teamId],
    queryFn: () => adminApi.dashboardSummary(selectedTeam),
  })

  const { data: chartDataRaw } = useQuery({
    queryKey: ['admin', 'dashboard', 'chart', chart, from, to, teamId],
    queryFn: () => adminApi.chart(chart, from, to, selectedTeam),
  })

  const data = useMemo(
    () => (chartDataRaw ? chartData(chartDataRaw.labels, chartDataRaw.values, CHART_NAMES[chart]) : []),
    [chartDataRaw, chart],
  )

  if (loadingSummary) return <LoadingState label="Loading dashboard…" />

  const theme = chartTheme()

  return (
    <div>
      <PageHeader
        title="Admin dashboard"
        subtitle="Company-wide overview and analytics"
        actions={
          <Select
            name="team"
            className="w-52"
            value={teamId}
            onChange={(e) => setTeamId(e.target.value)}
            options={[{ value: '', label: 'All teams' }, ...(departments ?? []).map((d) => ({ value: String(d.id), label: d.name }))]}
          />
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Total employees" value={summary?.totalEmployees ?? 0} accent="brand" />
        <StatCard label="Active employees" value={summary?.activeEmployees ?? 0} accent="emerald" />
        <StatCard label="Working today" value={summary?.workingToday ?? 0} accent="brand" />
        <StatCard label="On leave today" value={summary?.onLeaveToday ?? 0} accent="amber" />
        <StatCard label="WFH today" value={summary?.wfhToday ?? 0} accent="violet" />
        <StatCard label="WFO today" value={summary?.wfoToday ?? 0} accent="brand" />
        <StatCard label="Pending leaves" value={summary?.pendingLeaves ?? 0} accent="red" />
        <StatCard label="Pending swap-offs" value={summary?.pendingSwapOffs ?? 0} accent="red" />
      </div>

      <div className="card p-4">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Select
              name="chart"
              className="w-60"
              value={chart}
              onChange={(e) => setChart(e.target.value as ChartKey)}
              options={Object.entries(CHART_NAMES).map(([value, label]) => ({ value, label }))}
            />
          </div>
          <div className="flex items-center gap-2 text-sm text-surface-500">
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="input" aria-label="From" />
            <span>to</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="input" aria-label="To" />
          </div>
        </div>

        {data.length === 0 ? (
          <p className="py-16 text-center text-sm text-surface-400">No data for the selected range.</p>
        ) : chart === 'monthlyTrend' || chart === 'leaveUsage' || chart === 'departmentLeave' ? (
          <ResponsiveContainer width="100%" height={340}>
            <BarChart data={data}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.grid} />
              <XAxis dataKey="label" tick={{ fontSize: 12, fill: theme.tick }} />
              <YAxis tick={{ fontSize: 12, fill: theme.tick }} allowDecimals={false} />
              <Tooltip />
              <Legend />
              <Bar dataKey="value" name={CHART_NAMES[chart]} fill={theme.accent} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <ResponsiveContainer width="100%" height={340}>
            <LineChart data={data}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.grid} />
              <XAxis dataKey="label" tick={{ fontSize: 12, fill: theme.tick }} />
              <YAxis tick={{ fontSize: 12, fill: theme.tick }} allowDecimals={false} />
              <Tooltip />
              <Legend />
              <Line type="monotone" dataKey="value" name={CHART_NAMES[chart]} stroke={theme.accent} strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  )
}