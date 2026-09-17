package com.revenium.usage.ingestion.domain.port.`in`

import com.revenium.usage.ingestion.domain.model.IngestionResult
import com.revenium.usage.ingestion.domain.model.RawTransactionInput

/**
 * Accepts one incoming usage transaction.
 *
 * An inbound port: what drives ingestion — today an HTTP controller, tomorrow a message
 * consumer or a bulk loader — depends on this rather than on the service class.
 */
interface IngestTransactionUseCase {
    fun ingest(input: RawTransactionInput): IngestionResult
}
