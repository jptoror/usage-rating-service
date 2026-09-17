package com.revenium.usage.rating.infrastructure.persistence

import com.revenium.usage.rating.domain.model.RatedTransaction
import com.revenium.usage.rating.domain.port.out.RatedTransactionStore
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface RatedTransactionJpaRepository : JpaRepository<RatedTransactionEntity, Long> {

    /** Superseded rows are history, not the figure anything should be billed from. */
    @Query(
        """
        SELECT r FROM RatedTransactionEntity r
        WHERE r.tenantId = :tenantId AND r.rawEventId = :rawEventId AND r.supersededBy IS NULL
        """
    )
    fun findCurrentByRawEventId(
        @Param("tenantId") tenantId: String,
        @Param("rawEventId") rawEventId: Long,
    ): RatedTransactionEntity?

    @Query(
        """
        SELECT r FROM RatedTransactionEntity r
        WHERE r.tenantId = :tenantId
          AND r.customerId = :customerId
          AND r.billingPeriod = :period
          AND r.supersededBy IS NULL
        ORDER BY r.transactionCode, r.occurredAt
        """
    )
    fun findCurrentForBillingPeriod(
        @Param("tenantId") tenantId: String,
        @Param("customerId") customerId: String,
        @Param("period") period: LocalDate,
    ): List<RatedTransactionEntity>

    @Query(
        """
        SELECT count(r) FROM RatedTransactionEntity r
        WHERE r.tenantId = :tenantId AND r.supersededBy IS NULL
        """
    )
    fun countCurrent(@Param("tenantId") tenantId: String): Long
}

/**
 * The JPA adapter for [RatedTransactionStore].
 *
 * Translates between the domain model and the persistence entity. The entity never
 * escapes this package.
 */
@Repository
class JpaRatedTransactionStore(
    private val repository: RatedTransactionJpaRepository,
) : RatedTransactionStore {

    override fun findCurrent(tenant: TenantId, rawEventId: Long): RatedTransaction? =
        repository.findCurrentByRawEventId(tenant.value, rawEventId)?.toDomain()

    override fun save(rated: RatedTransaction): RatedTransaction =
        // saveAndFlush, not save: a constraint violation must surface here, where it can
        // be distinguished from a genuine failure, rather than at commit.
        repository.saveAndFlush(RatedTransactionEntity.fromDomain(rated)).toDomain()

    override fun findForBillingPeriod(
        tenant: TenantId,
        customer: CustomerId,
        period: BillingPeriod,
    ): List<RatedTransaction> =
        repository
            .findCurrentForBillingPeriod(tenant.value, customer.value, period.startDate)
            .map { it.toDomain() }
}
