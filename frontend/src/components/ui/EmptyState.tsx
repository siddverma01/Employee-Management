import { Inbox } from 'lucide-react'

export function EmptyState({ title = 'Nothing here yet', description }: { title?: string; description?: string }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
      <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-surface-100 text-surface-400">
        <Inbox className="h-6 w-6" />
      </div>
      <p className="text-sm font-medium text-surface-700">{title}</p>
      {description && <p className="mt-1 max-w-xs text-xs text-surface-400">{description}</p>}
    </div>
  )
}