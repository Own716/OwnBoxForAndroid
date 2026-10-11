package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

class YamlSafetyTest {

    private fun createSafeYaml(maxSize: Int = 10 * 1024 * 1024): Yaml {
        val loaderOptions = LoaderOptions().apply {
            codePointLimit = maxSize
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
        }
        val safeConstructor = SafeConstructor(loaderOptions)
        return Yaml(safeConstructor).apply {
            addTypeDescription(TypeDescription(String::class.java, "str"))
        }
    }

    @Test
    fun testValidClashYamlParsesSuccessfully() {
        val yamlText = """
            port: 7890
            socks-port: 7891
            allow-lan: false
            mode: rule
            log-level: info
            proxies:
              - name: "Sample SOCKS"
                type: socks5
                server: 127.0.0.1
                port: 1080
              - name: "Sample HTTP"
                type: http
                server: 1.2.3.4
                port: 8080
        """.trimIndent()

        val yaml = createSafeYaml()
        @Suppress("UNCHECKED_CAST")
        val result = yaml.load<Any>(yamlText) as? Map<String, Any?>
        assertNotNull(result)
        val proxies = result?.get("proxies") as? List<*>
        assertNotNull(proxies)
        assertEquals(2, proxies?.size)
    }

    @Test
    fun testMaliciousConstructorTagIsBlocked() {
        // Attempt arbitrary class instantiation via custom YAML tag
        val maliciousYaml = """
            proxies:
              - !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL ["http://127.0.0.1/malicious"]]]]
        """.trimIndent()

        val yaml = createSafeYaml()
        try {
            yaml.load<Any>(maliciousYaml)
            fail("SafeConstructor must reject arbitrary class instantiation tags")
        } catch (e: Exception) {
            assertTrue(
                "Exception should indicate unsupported class or construction error: ${e.message}",
                e is YAMLException || e.cause is YAMLException
            )
        }
    }

    @Test
    fun testBillionLaughsAliasBombIsBlocked() {
        // Billion Laughs style exponential alias expansion exceeding maxAliasesForCollections (50)
        val aliasBomb = """
            a: &a ["lol","lol","lol","lol","lol","lol","lol","lol","lol"]
            b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
            c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]
            d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]
            e: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d]
            f: &f [*e,*e,*e,*e,*e,*e,*e,*e,*e]
            g: &g [*f,*f,*f,*f,*f,*f,*f,*f,*f]
            proxies: *g
        """.trimIndent()

        val yaml = createSafeYaml()
        try {
            yaml.load<Any>(aliasBomb)
            fail("LoaderOptions maxAliasesForCollections must reject alias explosion bomb")
        } catch (e: Exception) {
            assertTrue(
                "Exception must indicate alias or recursion limit exceeded: ${e.message}",
                e is YAMLException || e.message?.contains("aliases", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun testCodePointLimitRejectsOversizedInput() {
        val tinyLimitYaml = createSafeYaml(maxSize = 50)
        val longContent = "proxies:\n  - name: test\n    server: " + "a".repeat(100)

        try {
            tinyLimitYaml.load<Any>(longContent)
            fail("LoaderOptions codePointLimit must reject input exceeding limit")
        } catch (e: Exception) {
            assertTrue(
                "Exception must indicate size limit exceeded: ${e.message}",
                e is YAMLException && (e.message?.contains("limit", ignoreCase = true) == true ||
                        e.message?.contains("size", ignoreCase = true) == true ||
                        e.message?.contains("code points", ignoreCase = true) == true)
            )
        }
    }
}
