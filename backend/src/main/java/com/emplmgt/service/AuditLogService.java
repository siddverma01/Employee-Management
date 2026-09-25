package com.emplmgt.service;

import com.emplmgt.dto.AuditDtos;
import com.emplmgt.repository.AuditLogRepository;
import com.emplmgt.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final JsonUtil jsonUtil;

    @Transactional(readOnly = true)
    public Page<AuditDtos.Response> search(String action, String entityType, Pageable pageable) {
        return auditLogRepository.search(action, entityType, pageable)
                .map(a -> new AuditDtos.Response(
                        a.getId(),
                        a.getUser() != null ? a.getUser().getEmail() : "system",
                        a.getAction(),
                        a.getEntityType(),
                        a.getEntityId(),
                        jsonUtil.read(a.getOldValue()),
                        jsonUtil.read(a.getNewValue()),
                        a.getIpAddress(),
                        a.getCreatedAt()));
    }
}