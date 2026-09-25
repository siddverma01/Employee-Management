import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '@/utils'

interface PaginationProps {
  page: number
  size: number
  totalElements: number
  totalPages: number
  onPageChange: (page: number) => void
}

export function Pagination({ page, size, totalElements, totalPages, onPageChange }: PaginationProps) {
  if (totalPages <= 1) return null
  const from = totalElements === 0 ? 0 : page * size + 1
  const to = Math.min((page + 1) * size, totalElements)
  return (
    <div className="flex items-center justify-between border-t border-surface-200 px-4 py-3">
      <p className="text-xs text-surface-500">
        Showing {from}-{to} of {totalElements}
      </p>
      <div className="flex items-center gap-1">
        <button
          className="rounded-md border border-surface-300 p-1.5 text-surface-500 transition-colors hover:bg-surface-100 disabled:opacity-40"
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
        <span className="px-2 text-xs text-surface-600">
          {page + 1} / {totalPages}
        </span>
        <button
          className="rounded-md border border-surface-300 p-1.5 text-surface-500 transition-colors hover:bg-surface-100 disabled:opacity-40"
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
          aria-label="Next page"
        >
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>
    </div>
  )
}