package com.revenium.usage.tenancy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TenantAwareDataSourceTest {

    private val statement = mockk<PreparedStatement>(relaxed = true)
    private val connection = mockk<Connection>(relaxed = true) {
        every { prepareStatement(any<String>()) } returns statement
    }
    private val delegate = mockk<DataSource> {
        every { connection } returns this@TenantAwareDataSourceTest.connection
        every { getConnection(any(), any()) } returns this@TenantAwareDataSourceTest.connection
    }
    private val dataSource = TenantAwareDataSource(delegate)

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    @Test
    fun `binds the tenant as a parameter, never as interpolated SQL`() {
        TenantContext.runAs(TenantId("tenant-a")) { dataSource.connection }

        // This statement is the mechanism that enforces isolation, so it must not be
        // constructible from input.
        verify { connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)") }
        verify { statement.setString(1, "tenant-a") }
        verify { statement.execute() }
    }

    @Test
    fun `applies the tenant on the credentialed overload too`() {
        // Missing this overload would leave a quiet route to an unscoped connection.
        TenantContext.runAs(TenantId("tenant-b")) { dataSource.getConnection("u", "p") }

        verify { statement.setString(1, "tenant-b") }
    }

    @Test
    fun `clears the tenant when none is in scope, rather than leaving the last one`() {
        // The bug this guards against was real: returning early on a null tenant left
        // the PREVIOUS borrower's tenant on the pooled connection, and the next
        // unscoped caller silently inherited it. The outbox worker's cross-tenant claim
        // then saw only one tenant's rows.
        //
        // The setting is written as NULL instead, which every policy treats as "no
        // tenant" -- failing closed.
        val handedOut = dataSource.connection

        assertSame(connection, handedOut)
        verify { statement.setNull(1, java.sql.Types.VARCHAR) }
        verify { statement.execute() }
    }

    @Test
    fun `closes and refuses a connection it could not scope`() {
        // An unscoped connection must never escape: it would either see nothing, which
        // is merely confusing, or -- with a privileged role -- see everything.
        every { connection.prepareStatement(any<String>()) } throws SQLException("connection lost")

        assertFailsWith<IllegalStateException> {
            TenantContext.runAs(TenantId("tenant-a")) { dataSource.connection }
        }
        verify { connection.close() }
    }
}
