import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Department } from '@/types'
import { departmentApi } from '@/api'
import { useAuth } from '@/hooks/useAuth'

interface TeamContextValue {
  teams: Department[]
  teamId: number | null
  effectiveTeamId: number | undefined
  teamLabel: string
  isAdmin: boolean
  setTeamId: (id: number | null) => void
}

const TeamContext = createContext<TeamContextValue>({
  teams: [],
  teamId: null,
  effectiveTeamId: undefined,
  teamLabel: '',
  isAdmin: false,
  setTeamId: () => undefined,
})

export function TeamProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const [teamId, setTeamId] = useState<number | null>(null)

  const { data: teams = [] } = useQuery({ queryKey: ['teams'], queryFn: departmentApi.list })

  const teamLabel = useMemo(() => {
    if (teamId != null) {
      return teams.find((t) => t.id === teamId)?.name ?? `Team #${teamId}`
    }
    return isAdmin ? 'All Teams' : user?.teamName ? user.teamName : 'My Team'
  }, [teamId, teams, isAdmin, user?.teamName])

  const effectiveTeamId = useMemo<number | undefined>(() => {
    if (teamId != null) return teamId
    return isAdmin ? undefined : (user?.teamId ?? undefined)
  }, [teamId, isAdmin, user?.teamId])

  const value = useMemo(
    () => ({ teams, teamId, effectiveTeamId, teamLabel, isAdmin, setTeamId }),
    [teams, teamId, effectiveTeamId, teamLabel, isAdmin],
  )

  return <TeamContext.Provider value={value}>{children}</TeamContext.Provider>
}

export function useTeam(): TeamContextValue {
  return useContext(TeamContext)
}