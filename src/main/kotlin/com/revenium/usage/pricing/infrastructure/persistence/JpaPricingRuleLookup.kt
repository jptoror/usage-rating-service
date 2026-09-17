package com.revenium.usage.pricing.infrastructure.persistence

import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.pricing.domain.port.out.PricingRuleLookup
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface PricingRuleJpaRepository : JpaRepository<PricingRuleEntity, Long> {

    /**
     * The rule covering [occurredAt], relying on the same half-open semantics the
     * EXCLUDE constraint enforces: `effectiveFrom <= occurredAt < effectiveTo`.
     *
     * The constraint guarantees at most one match, so this returns a single rule rather
     * than a list with a tie-break. If it ever returned two the database would already
     * be inconsistent, and silently picking one would hide that.
     */
    @Query(
        """
        SELECT r FROM PricingRuleEntity r
        WHERE r.tenantId = :tenantId
          AND r.transactionCode = :code
          AND r.effectiveFrom <= :occurredAt
          AND (r.effectiveTo IS NULL OR r.effectiveTo > :occurredAt)
        """
    )
    fun findApplicable(
        @Param("tenantId") tenantId: String,
        @Param("code") code: String,
        @Param("occurredAt") occurredAt: Instant,
    ): PricingRuleEntity?

    fun findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc(tenantId: String): List<PricingRuleEntity>
}

/**
 * The JPA adapter for [PricingRuleLookup].
 *
 * Deliberately thin: it translates typed domain values to the repository's primitives and
 * nothing else. Any logic here would be logic the rating unit tests cannot see.
 */
@Repository
class JpaPricingRuleLookup(
    private val repository: PricingRuleJpaRepository,
) : PricingRuleLookup {

    override fun findApplicable(
        tenant: TenantId,
        code: TransactionCode,
        occurredAt: Instant,
    ): PricingRule? =
        repository.findApplicable(tenant.value, code.value, occurredAt)?.toDomain()

    override fun findAllFor(tenant: TenantId): List<PricingRule> =
        repository.findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc(tenant.value)
            .map { it.toDomain() }

    override fun findById(tenant: TenantId, id: Long): PricingRule? =
        // Row-level security already restricts this, but the tenant is checked explicitly
        // too: a miss rather than a leak, even if a policy were ever dropped.
        repository.findById(id).orElse(null)
            ?.takeIf { it.tenantId == tenant.value }
            ?.toDomain()
}
