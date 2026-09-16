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
    fun `hands out an unscoped connection when no tenant is in scope`() {
        // Liquibase and actuator legitimately run with no tenant. The policies then
        // match nothing, which fails closed.
        val handedOut = dataSource.connection

        assertSame(connection, handedOut)
        verify(exactly = 0) { statement.execute() }
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
