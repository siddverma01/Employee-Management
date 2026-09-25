import { useQueryClient } from '@tanstack/react-query'
import { notificationApi } from '@/api'

export function useNotifications() {
  const queryClient = useQueryClient()

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['notifications'] })
  }

  const markRead = async (id: number) => {
    await notificationApi.markRead(id)
    refresh()
  }

  const markAllRead = async () => {
    await notificationApi.markAllRead()
    refresh()
  }

  return { markRead, markAllRead, refresh }
}