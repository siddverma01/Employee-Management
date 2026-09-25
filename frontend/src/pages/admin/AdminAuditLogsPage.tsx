import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { AuditLog } from '@/types'
import { adminApi } from '@/api'
import { formatDateTime } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Pagination } from '@/components/ui/Pagination'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'

export function AdminAuditLogsPage() {
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'audit-logs', { page, search: debouncedSearch }],
    queryFn: () => adminApi.auditLogs({ page, size: 20, search: debouncedSearch || undefined }),
  })

  return (
    <div>
      <PageHeader title="Audit logs" subtitle="A trail of all administrative actions" />

      <div className="mb-4 max-w-md">
        <input value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} placeholder="Search by user, action, or entity…" className="input" />
      </div>

      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="grid place-items-center py-20"><Spinner /></div>
        ) : !data?.content.length ? (
          <EmptyState title="No audit entries" description="No activity matches your search." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[840px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">When</th>
                  <th className="th">User</th>
                  <th className="th">Action</th>
                  <th className="th">Entity</th>
                  <th className="th">IP</th>
                  <th className="th">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {data.content.map((a) => (
                  <tr key={a.id} className="transition-colors duration-150 hover:bg-rowhover">
                    <td className="td text-xs text-surface-500">{formatDateTime(a.createdAt)}</td>
                    <td className="td text-xs">{a.userEmail}</td>
                    <td className="td"><span className="rounded-full bg-surface-100 px-2 py-0.5 text-xs capitalize text-surface-700">{a.action.replace(/_/g, ' ')}</span></td>
                    <td className="td text-xs">{a.entityType}<span className="ml-1 text-surface-400">#{a.entityId}</span></td>
                    <td className="td text-xs text-surface-500">{a.ipAddress}</td>
                    <td className="td">
                      <AuditDiff oldValue={a.oldValue} newValue={a.newValue} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(data?.totalPages ?? 0) > 1 && (
          <Pagination page={data!.page} size={data!.size} totalPages={data!.totalPages} totalElements={data!.totalElements} onPageChange={(p) => setPage(p)} />
        )}
      </div>
    </div>
  )
}

function AuditDiff({ oldValue, newValue }: { oldValue: AuditLog['oldValue']; newValue: AuditLog['newValue'] }) {
  if (!newValue) return null
  const entries = Object.entries(newValue).slice(0, 4)
  if (entries.length === 0) return <span className="text-xs text-surface-400">—</span>
  return (
    <div className="flex flex-wrap gap-1">
      {entries.map(([k, v]) => {
        const changed = oldValue && oldValue[k] !== undefined && oldValue[k] !== v
        return (
          <span key={k} className={`rounded bg-surface-50 px-1.5 py-0.5 text-[10px] ${changed ? 'text-amber-700' : 'text-surface-500'}`} title={changed ? `changed from ${JSON.stringify(oldValue?.[k])}` : undefined}>
            {k}: {String(v)}
          </span>
        )
      })}
    </div>
  )
}