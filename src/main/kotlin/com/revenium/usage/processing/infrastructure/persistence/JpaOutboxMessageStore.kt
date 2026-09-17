package com.revenium.usage.processing.infrastructure.persistence

import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.processing.domain.model.OutboxStatus
import com.revenium.usage.processing.domain.port.out.OutboxMessageStore
import com.revenium.usage.tenancy.TenantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface OutboxMessageJpaRepository : JpaRepository<OutboxMessageEntity, Long> {

    fun findByTenantIdAndRawEventId(tenantId: String, rawEventId: Long): OutboxMessageEntity?

    fun countByTenantIdAndStatus(tenantId: String, status: OutboxStatus): Long
}

/**
 * The JPA adapter for [OutboxMessageStore].
 *
 * Translates between the domain model and the persistence entity. The entity never
 * escapes this package.
 */
@Repository
class JpaOutboxMessageStore(
    private val repository: OutboxMessageJpaRepository,
) : OutboxMessageStore {

    override fun enqueue(message: OutboxMessage): OutboxMessage =
        repository.save(OutboxMessageEntity.fromDomain(message)).toDomain()

    override fun findByRawEventId(tenant: TenantId, rawEventId: Long): OutboxMessage? =
        repository.findByTenantIdAndRawEventId(tenant.value, rawEventId)?.toDomain()

    override fun save(message: OutboxMessage): OutboxMessage =
        repository.save(OutboxMessageEntity.fromDomain(message)).toDomain()
}
