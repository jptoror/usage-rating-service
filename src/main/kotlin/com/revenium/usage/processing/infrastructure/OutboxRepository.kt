package com.revenium.usage.processing.infrastructure

import com.revenium.usage.processing.domain.OutboxMessage
import com.revenium.usage.processing.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface OutboxMessageRepository : JpaRepository<OutboxMessage, Long> {

    fun findByTenantIdAndRawEventId(tenantId: String, rawEventId: Long): OutboxMessage?

    fun countByTenantIdAndStatus(tenantId: String, status: OutboxStatus): Long
}
