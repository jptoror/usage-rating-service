package com.revenium.usage.shared.error

import com.revenium.usage.tenancy.CrossTenantAccessException
import com.revenium.usage.tenancy.MissingTenantException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

private val log = KotlinLogging.logger {}

/**
 * Translates exceptions into RFC 7807 `application/problem+json`.
 *
 * No stack trace ever reaches a client: internal structure is useful to an attacker and
 * useless to an integrator. The log keeps the detail; the response keeps the meaning.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MissingTenantException::class)
    fun handleMissingTenant(e: MissingTenantException): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "missing-tenant",
            title = "Missing tenant",
            detail = "The X-Tenant-Id header is required for this operation",
        )

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ProblemDetail =
        if (e.headerName.equals("X-Tenant-Id", ignoreCase = true)) {
            problem(
                status = HttpStatus.BAD_REQUEST,
                type = "missing-tenant",
                title = "Missing tenant",
                detail = "The X-Tenant-Id header is required for this operation",
            )
        } else {
            problem(
                status = HttpStatus.BAD_REQUEST,
                type = "missing-header",
                title = "Missing header",
                detail = "Required header '${e.headerName}' is missing",
            )
        }

    @ExceptionHandler(CrossTenantAccessException::class)
    fun handleCrossTenant(e: CrossTenantAccessException): ProblemDetail {
        // Logged in full, reported vaguely: confirming which tenant identifiers exist
        // is itself a small information leak.
        log.warn { "Cross-tenant access rejected: $e" }
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "cross-tenant-access",
            title = "Cross-tenant access denied",
            detail = "The request targets a tenant other than the authenticated one",
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "malformed-request",
            title = "Malformed request body",
            detail = "The request body could not be parsed as JSON",
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ProblemDetail {
        log.error(e) { "Unhandled exception" }
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "internal-error",
            title = "Internal error",
            detail = "The request could not be processed",
        )
    }

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply {
        this.type = URI.create("$PROBLEM_TYPE_BASE/$type")
        this.title = title
    }

    private companion object {
        const val PROBLEM_TYPE_BASE = "https://revenium.example/problems"
    }
}
