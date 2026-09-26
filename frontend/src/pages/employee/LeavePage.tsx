import { useState, useMemo } from 'react'
import { Plus, CalendarDays, RefreshCw, X, Filter, Clock } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { ApplyLeaveModal } from './ApplyLeaveModal'
import { ApplySwapOffModal } from './ApplySwapOffModal'
import { MyLeavesTable } from './MyLeavesTable'
import { LeaveBalanceCards } from './LeaveBalanceCard'
import { cn } from '@/utils'

type LeaveStatusFilter = 'all' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

const STATUS_FILTERS: { value: LeaveStatusFilter; label: string }[] = [
  { value: 'all', label: 'All Requests' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

export function LeavePage() {
  const [statusFilter, setStatusFilter] = useState<LeaveStatusFilter>('all')
  const [showApplyLeaveModal, setShowApplyLeaveModal] = useState(false)
  const [showApplySwapOffModal, setShowApplySwapOffModal] = useState(false)

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-surface-900">Leave Management</h1>
          <p className="mt-1 text-sm text-surface-500">Track balances, request time off, and manage leave history.</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setShowApplySwapOffModal(true)}
            className="gap-2"
          >
            <RefreshCw className="h-4 w-4" />
            Apply Swap Off
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => setShowApplyLeaveModal(true)}
            className="gap-2"
          >
            <Plus className="h-4 w-4" />
            Apply Leave
          </Button>
        </div>
      </div>

      <LeaveBalanceCards />

      <div className="card">
        <div className="p-4 border-b border-surface-200">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div className="flex items-center gap-2">
              <Filter className="h-4 w-4 text-surface-400" />
              <span className="text-sm font-medium text-surface-700">Filter by status</span>
            </div>
            <div className="flex items-center gap-1">
              {STATUS_FILTERS.map((filter) => (
                <button
                  key={filter.value}
                  type="button"
                  onClick={() => setStatusFilter(filter.value)}
                  className={cn(
                    'px-3 py-1.5 text-xs font-medium rounded-lg transition-colors duration-150',
                    statusFilter === filter.value
                      ? 'bg-brand-500 text-white'
                      : 'text-surface-600 hover:bg-surface-100 hover:text-surface-900'
                  )}
                >
                  {filter.label}
                </button>
              ))}
            </div>
          </div>
        </div>
        <MyLeavesTable statusFilter={statusFilter} />
      </div>

      <ApplyLeaveModal isOpen={showApplyLeaveModal} onClose={() => setShowApplyLeaveModal(false)} />
      <ApplySwapOffModal isOpen={showApplySwapOffModal} onClose={() => setShowApplySwapOffModal(false)} />
    </div>
  )
}