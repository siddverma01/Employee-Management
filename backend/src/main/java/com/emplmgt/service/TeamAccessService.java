package com.emplmgt.service;

import com.emplmgt.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Team-based visibility enforcement.
 *
 * Rules:
 *  - ADMIN can always view any team with full internal data (FULL).
 *  - A non-admin with no target team requested defaults to their own team (FULL).
 *  - A non-admin requesting a team other than their own sees basic availability only (BASIC).
 */
@Component
@RequiredArgsConstructor
public class TeamAccessService {

    public enum AccessMode {
        FULL,
        BASIC
    }

    public record ResolvedTeam(Long teamId, AccessMode mode) {
    }

    private final SecurityUtils securityUtils;

    public ResolvedTeam resolve(Long requestedTeamId) {
        boolean admin = securityUtils.isAdmin();
        Long myTeamId = securityUtils.currentTeamId();
        if (admin) {
            return new ResolvedTeam(requestedTeamId, AccessMode.FULL);
        }
        if (requestedTeamId == null) {
            return new ResolvedTeam(myTeamId, AccessMode.FULL);
        }
        AccessMode mode = myTeamId != null && myTeamId.equals(requestedTeamId)
                ? AccessMode.FULL
                : AccessMode.BASIC;
        return new ResolvedTeam(requestedTeamId, mode);
    }

    public boolean isAdmin() {
        return securityUtils.isAdmin();
    }
}