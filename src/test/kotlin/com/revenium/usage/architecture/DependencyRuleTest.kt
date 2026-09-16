package com.revenium.usage.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Enforces the dependency rule the README and CLAUDE.md both state.
 *
 * A plain source scan rather than ArchUnit: the rule is two lines of logic, and adding a
 * dependency to assert it would be more code than the assertion.
 *
 * This exists because the rule was broken three times in code that compiled, passed every
 * test and produced correct results — rating taking the outbox's JDBC projection,
 * invoicing injecting rating's JPA repository, ingestion injecting the outbox's. Each was
 * found by a human reading the code, which does not scale.
 */
class DependencyRuleTest {

    private val sourceRoot = File("src/main/kotlin/com/revenium/usage")

    private data class Violation(val file: String, val import: String) {
        override fun toString() = "$file imports $import"
    }

    private fun sourcesUnder(vararg layers: String): List<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> layers.any { layer -> file.path.contains("/$layer/") } }
            .toList()

    /** The module a file belongs to: `.../usage/<module>/<layer>/File.kt`. */
    private fun moduleOf(file: File): String =
        file.relativeTo(sourceRoot).path.substringBefore(File.separatorChar)

    @Test
    fun `no application or domain class imports another module's infrastructure`() {
        // Infrastructure is a module's private business. Importing another module's means
        // depending on how it stores things, not on what it offers -- which is what a
        // port is for.
        val violations = sourcesUnder("application", "domain").flatMap { file ->
            val module = moduleOf(file)
            file.readLines()
                .filter { it.startsWith("import com.revenium.usage.") && ".infrastructure." in it }
                .filterNot { "usage.$module.infrastructure." in it }
                .map { Violation(file.name, it.removePrefix("import ").trim()) }
        }

        assertTrue(violations.isEmpty(), "Dependency rule violated:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `no domain class imports from api or application`() {
        // The domain is the innermost layer: everything points at it, it points at
        // nothing. This is what keeps rating rules and monetary arithmetic unit-testable
        // with no Spring context.
        val violations = sourcesUnder("domain").flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import com.revenium.usage.") }
                .filter { ".api." in it || ".application." in it }
                .map { Violation(file.name, it.removePrefix("import ").trim()) }
        }

        assertTrue(violations.isEmpty(), "Domain depends outward:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `no domain class imports a web or servlet type`() {
        // Tenancy is the deliberate exception -- TenantFilter is a servlet filter and
        // lives beside the context it populates -- so it is excluded by name rather than
        // by weakening the rule.
        val violations = sourcesUnder("domain")
            .filterNot { moduleOf(it) == "tenancy" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.startsWith("import org.springframework.web") || it.startsWith("import jakarta.servlet") }
                    .map { Violation(file.name, it.removePrefix("import ").trim()) }
            }

        assertTrue(violations.isEmpty(), "Domain depends on the web layer:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `no production class uses a not-null assertion`() {
        // `!!` turns a compile-time question into a runtime crash. CLAUDE.md bans it;
        // this makes the ban checkable.
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filterNot { it.startsWith("*") || it.startsWith("//") }
                    .filter { Regex("""[\w)\]]!!""").containsMatchIn(it) }
                    .map { Violation(file.name, it) }
            }
            .toList()

        assertTrue(violations.isEmpty(), "Not-null assertions in production code:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `no monetary code uses floating point`() {
        // `0.1 + 0.2 != 0.3` in binary floating point, and an invoice that is off by a
        // fraction of a cent is an invoice a reviewer finds.
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filterNot { it.startsWith("*") || it.startsWith("//") }
                    .filter { Regex("""\b(Double|Float)\b|\.toDouble\(\)""").containsMatchIn(it) }
                    .map { Violation(file.name, it) }
            }
            .toList()

        assertTrue(violations.isEmpty(), "Floating point in production code:\n" + violations.joinToString("\n"))
    }
}
