package com.revenium.usage

import com.revenium.usage.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts the application context actually starts.
 *
 * This exists because it did not, once: a Kotlin constructor parameter with a default
 * value makes the compiler emit a synthetic `DefaultConstructorMarker` parameter, which
 * Spring then tries to autowire as a bean. Every unit test passed -- they construct the
 * class directly -- and the failure only appeared when the container was started.
 *
 * The other integration tests would eventually have caught it, but by asserting on
 * behaviour, so the report would have said "invoice totals are wrong" rather than
 * "the application does not boot".
 */
@IntegrationTest
class ApplicationContextIntegrationTest(@Autowired val context: ApplicationContext) {

    @Test
    fun `the context starts with every bean wired`() {
        assertNotNull(context)
        assertTrue(context.beanDefinitionCount > 0)
    }

    @Test
    fun `the beans the service cannot run without are present`() {
        // Named individually rather than counted: a count passes while the one bean
        // that matters is missing.
        listOf(
            "ingestionService",
            "ratingService",
            "invoiceService",
            "reconciliationService",
            "outboxWorker",
            "outboxScheduler",
            "tenantGuardAspect",
            "instanceId",
        ).forEach { bean ->
            assertTrue(context.containsBean(bean), "missing bean: $bean")
        }
    }

    @Test
    fun `the tenant guard is proxied, so the aspect actually applies`() {
        // A @RequiresTenant service that Spring did not proxy would enforce nothing,
        // and every tenancy test that goes through the context would silently pass.
        val service = context.getBean("ingestionService")
        assertTrue(
            org.springframework.aop.support.AopUtils.isAopProxy(service),
            "IngestionService is not proxied; @RequiresTenant would not be enforced",
        )
    }
}
