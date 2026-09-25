import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import toast from 'react-hot-toast'
import { notificationApi } from '@/api'
import { extractMessage } from '@/api/client'
import { formatDateTime } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

const TYPE_DOTS: Record<string, string> = {
  LEAVE: 'bg-brand-500',
  SWAP_OFF: 'bg-violet-500',
  APPROVAL: 'bg-emerald-500',
  SYSTEM: 'bg-amber-500',
}

export function NotificationsPage() {
  const queryClient = useQueryClient()
  const { data: notifications, isLoading } = useQuery({ queryKey: ['notifications'], queryFn: () => notificationApi.list(0, 50) })

  const markRead = useMutation({
    mutationFn: notificationApi.markRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })

  const markAll = useMutation({
    mutationFn: notificationApi.markAllRead,
    onSuccess: (res) => {
      toast.success(`Marked ${res.count} as read`)
      queryClient.invalidateQueries({ queryKey: ['notifications'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  if (isLoading) return <LoadingState label="Loading notifications…" />

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Notifications"
        subtitle="Updates about your requests and company activity"
        actions={
          (notifications?.some((n) => !n.read)) ? (
            <Button variant="secondary" size="sm" loading={markAll.isPending} onClick={() => markAll.mutate()} disabled={!notifications?.some((n) => !n.read)}>
              Mark all as read
            </Button>
          ) : undefined
        }
      />

      {!notifications?.length ? (
        <EmptyState title="You're all caught up" description="New notifications will appear here." />
      ) : (
        <ul className="card divide-y divide-surface-200 overflow-hidden">
          {notifications.map((n) => (
            <li key={n.id} className={`flex items-start gap-3 px-4 py-3 ${n.read ? 'bg-surface-0' : 'bg-brand-50/50 dark:bg-surface-100/70'}`}>
              <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${TYPE_DOTS[n.type] ?? 'bg-surface-300'}`} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <p className={`text-sm font-medium ${n.read ? 'text-surface-600' : 'text-surface-900'}`}>{n.title}</p>
                  {!n.read && <span className="rounded-full bg-brand-600 px-1.5 py-0.5 text-[10px] font-semibold text-white">New</span>}
                </div>
                {n.body && <p className="mt-0.5 text-xs text-surface-500">{n.body}</p>}
                <p className="mt-1 text-[11px] text-surface-400">{formatDateTime(n.createdAt)}</p>
              </div>
              <div className="shrink-0">
                {n.link && <Link to={n.link} className="text-xs font-medium text-brand-600 hover:underline">View</Link>}
                {!n.read && (
                  <button
                    onClick={() => markRead.mutate(n.id)}
                    className="ml-2 text-xs font-medium text-surface-400 hover:text-surface-600"
                  >
                    Mark read
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}