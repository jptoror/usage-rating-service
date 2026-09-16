package com.revenium.usage.tenancy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlin.test.assertFailsWith

class TenantConnectionPreparerTest {

    private val statement = mockk<PreparedStatement>(relaxed = true)
    private val connection = mockk<Connection>(relaxed = true)
    private val dataSource = mockk<DataSource>()

    private fun preparer(inTransaction: Boolean = true): TenantConnectionPreparer {
        every { connection.autoCommit } returns !inTransaction
        every { connection.prepareStatement(any<String>()) } returns statement
        every { dataSource.connection } returns connection
        return TenantConnectionPreparer(dataSource)
    }

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    @Test
    fun `binds the tenant as a parameter rather than interpolating it`() {
        preparer().applyTo(TenantId("tenant-a"))

        // set_config with a bound parameter: a hostile tenant id cannot alter the
        // statement. String interpolation here would be an injection point straight
        // into the mechanism that enforces isolation.
        verify { connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)") }
        verify { statement.setString(1, "tenant-a") }
        verify { statement.execute() }
    }

    @Test
    fun `refuses to run outside a transaction`() {
        // SET LOCAL outside a transaction is silently discarded, which would leave
        // RLS unset while the code believes isolation is in force.
        assertFailsWith<IllegalStateException> {
            preparer(inTransaction = false).applyTo(TenantId("tenant-a"))
        }
    }

    @Test
    fun `applyCurrent applies the tenant in scope`() {
        val preparer = preparer()
        TenantContext.runAs(TenantId("tenant-b")) { preparer.applyCurrent() }

        verify { statement.setString(1, "tenant-b") }
    }

    @Test
    fun `applyCurrent does nothing when no tenant is in scope`() {
        val preparer = preparer()
        preparer.applyCurrent()

        verify(exactly = 0) { statement.execute() }
    }
}
