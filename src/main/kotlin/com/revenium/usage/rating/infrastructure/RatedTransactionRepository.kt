package com.revenium.usage.rating.infrastructure

import com.revenium.usage.rating.domain.RatedTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface RatedTransactionRepository : JpaRepository<RatedTransaction, Long> {

    /**
     * The current rating for an event, if any.
     *
     * Superseded rows are excluded: they are history, not the figure anything should be
     * billed from. This mirrors the partial unique index, which only constrains rows
     * where `superseded_by IS NULL`.
     */
    @Query(
        """
        SELECT r FROM RatedTransaction r
        WHERE r.tenantId = :tenantId AND r.rawEventId = :rawEventId AND r.supersededBy IS NULL
        """
    )
    fun findCurrentByRawEventId(
        @Param("tenantId") tenantId: String,
        @Param("rawEventId") rawEventId: Long,
    ): RatedTransaction?

    @Query(
        """
        SELECT r FROM RatedTransaction r
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
    ): List<RatedTransaction>

    @Query(
        """
        SELECT count(r) FROM RatedTransaction r
        WHERE r.tenantId = :tenantId AND r.supersededBy IS NULL
        """
    )
    fun countCurrent(@Param("tenantId") tenantId: String): Long
}
