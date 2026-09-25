import { Spinner } from './Spinner'

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-surface-400 gap-2">
      <Spinner />
      <p className="text-sm">{label}</p>
    </div>
  )
}