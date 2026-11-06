package io.nekohasekai.sagernet.ui

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import okio.buffer

class LocalYacdDashboardTest {
    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test fun onlyBundledMainDocumentsMatch() {
        for (path in listOf("/ui", "/ui/", "/ui/index.html")) {
            assertTrue(LocalYacdDashboard.isLocalDocument("http://127.0.0.1:9090$path#/proxies"))
        }
        for (url in listOf("https://board.zash.run.place/", "https://example.com/ui",
            "http://localhost:9090/ui", "http://127.0.0.1:9091/ui", "https://127.0.0.1:9090/ui",
            "http://127.0.0.1:9090/ui/assets/index.js", "http://127.0.0.1:9090/version",
            "http://127.0.0.1.example.com:9090/ui", "http://user:pass@127.0.0.1:9090/ui")) {
            assertFalse(url, LocalYacdDashboard.isLocalDocument(url))
        }
    }

    @Test fun disconnectedAndDisabledAreDistinct() {
        assertEquals(LocalYacdDashboard.Failure.DISCONNECTED, LocalYacdDashboard.readiness(false, true))
        assertEquals(LocalYacdDashboard.Failure.DISABLED, LocalYacdDashboard.readiness(true, false))
        assertNull(LocalYacdDashboard.readiness(true, true))
    }

    @Test fun authenticatedVersionAndFailureCategories() {
        for ((code, body, expected) in listOf(
            Triple(200, "{\"version\":\"1.15.0-alpha.10\"}", null),
            Triple(401, "Unauthorized", LocalYacdDashboard.Failure.AUTHENTICATION),
            Triple(403, "Forbidden", LocalYacdDashboard.Failure.AUTHENTICATION),
            Triple(503, "Unavailable", LocalYacdDashboard.Failure.API),
            Triple(200, "<html>not an API</html>", LocalYacdDashboard.Failure.API),
            Triple(200, "{}", LocalYacdDashboard.Failure.API),
            Triple(302, "", LocalYacdDashboard.Failure.API)
        )) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals("http://127.0.0.1:9090/version", chain.request().url.toString())
                assertEquals("GET", chain.request().method)
                assertEquals("Bearer local-only", chain.request().header("Authorization"))
                assertEquals(java.util.concurrent.TimeUnit.SECONDS.toNanos(3), chain.call().timeout().timeoutNanos())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").header("Location", "https://example.com/")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                assertEquals(expected, LocalYacdDashboard.checkApi(client, "local-only"))
                assertEquals(1, calls)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
        val failed = OkHttpClient.Builder().addInterceptor { throw IOException("fixture timeout") }.build()
        try { assertEquals(LocalYacdDashboard.Failure.API, LocalYacdDashboard.checkApi(failed, "local-only")) }
        finally { failed.connectionPool.evictAll(); failed.dispatcher.executorService.shutdown() }
    }

    @Test fun bootstrapEscapesSecretAndBundledRouterContractMatches() {
        val secret = "\"</script><script>throw 1</script>\\\n\u2028"
        val html = LocalYacdDashboard.bootstrap("(__OWNBOX_YACD_CONFIG__)", secret, "reload")
        assertFalse(html.contains("<script>throw"))
        assertTrue(html.contains("\\u003c"))
        assertTrue(html.contains("\\u2028"))
        val json = html.removePrefix("<script>(").removeSuffix(")</script>")
        assertEquals(secret, org.json.JSONObject(json).getString("secret"))
        assertEquals(LocalYacdDashboard.ENDPOINT, org.json.JSONObject(json).getString("endpoint"))
        ZipFile(sourceFile("src/main/assets/yacd.zip")).use { zip ->
            val js = zip.entries().asSequence().filter { it.name.endsWith(".js") }
                .joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            assertTrue(js.contains("yacd.metacubex.one"))
            assertTrue(js.contains("selectedClashAPIConfigIndex"))
            assertTrue(js.contains("path:\"/backend\""))
            assertTrue(js.contains("path:\"/\",element:"))
        }
    }

    @Test fun fragmentChecksHealthBeforeHtmlAndNeverInjectsRemoteSecrets() {
        val source = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/WebviewFragment.kt").readText()
        val intercept = source.substringAfter("override fun shouldInterceptRequest").substringBefore("override fun onReceivedError")
        assertTrue(intercept.indexOf("!request.isForMainFrame") < intercept.indexOf("DataStore.clashApiSecret"))
        assertTrue(intercept.indexOf("!LocalYacdDashboard.isLocalDocument") < intercept.indexOf("DataStore.clashApiSecret"))
        assertTrue(intercept.indexOf("checkApi(dashboardClient, secret)") < intercept.indexOf("response.body?.string()"))
        assertTrue(intercept.contains("if (apiFailure != null) return dashboardError(apiFailure)"))
        assertTrue(intercept.contains("url.newBuilder().encodedPath(\"/ui/\")"))
        assertTrue(intercept.contains("<base href=\\\"/ui/\\\">"))
        assertFalse(intercept.contains("catch (e: Exception) {\n                    null"))
        val config = sourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(config.contains("secret = DataStore.clashApiSecret"))
        val health = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/LocalYacdDashboard.kt").readText()
        assertTrue(health.contains("followRedirects(false).followSslRedirects(false)"))
    }

    @Test fun healthResponseClosesOnSuccessAndAllFailures() {
        for ((code, text) in listOf(200 to "{\"version\":\"test\"}", 200 to "invalid", 401 to "Unauthorized")) {
            var closed = false
            val source = object : okio.ForwardingSource(okio.Buffer().writeUtf8(text)) {
                override fun close() { closed = true; super.close() }
            }.buffer()
            val body = object : okhttp3.ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = text.toByteArray().size.toLong()
                override fun source() = source
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").body(body).build()
            }.build()
            try { LocalYacdDashboard.checkApi(client, "local-only"); assertTrue(closed) }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}
