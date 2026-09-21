package app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory

class PersistenceAndHttpTest {

    private fun tmpDb(): String = createTempDirectory("lossmirror").resolve("test.db").toString()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `clear and reimport gives reproducible analysis`() {
        val db = Database(tmpDb())
        val run1 = Fixture.build()
        db.upsertRun(run1, json.encodeToString(Run.serializer(), run1))
        val r1 = Analysis.analyze(Fixture.defaultConfig(run1), run1)

        db.clearAll()
        assertEquals(0, db.countRuns())

        val run2 = Fixture.build()
        db.upsertRun(run2, json.encodeToString(Run.serializer(), run2))
        val r2 = Analysis.analyze(Fixture.defaultConfig(run2), run2)
        assertEquals(r1.windows.map { it.energyJ }, r2.windows.map { it.energyJ })
        db.close()
    }

    @Test
    fun `http serves title imports fixture analyzes and exports csv`() = runBlocking {
        val db = Database(tmpDb())
        val port = freePort()
        val server = startServer(db, port).start(wait = false)
        val client = HttpClient(ClientCIO)
        try {
            val html = client.get("http://127.0.0.1:$port/").bodyAsText()
            assertTrue(html.contains("开关损耗镜"))

            val imported = client.post("http://127.0.0.1:$port/api/runs/import-fixture").bodyAsText()
            assertTrue(imported.contains("dp-2pulse-demo"))

            val cfg = client.get("http://127.0.0.1:$port/api/runs/dp-2pulse-demo/default-config").bodyAsText()
            val res = client.post("http://127.0.0.1:$port/api/analyze") {
                contentType(ContentType.Application.Json)
                setBody("""{"config":$cfg,"save":true}""")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertTrue(body.contains("BOUNDED") || body.contains("INVALID"))

            val id = Regex(""""id":"([^"]+)"""").find(body)!!.groupValues[1]
            val csvResp = client.get("http://127.0.0.1:$port/api/analyses/$id/export.csv")
            assertEquals(HttpStatusCode.OK, csvResp.status)
            val csv = csvResp.bodyAsText()
            assertTrue(csv.contains("window") && csv.contains("eon"))

            val cleared = client.post("http://127.0.0.1:$port/api/admin/clear").bodyAsText()
            assertTrue(cleared.contains("\"cleared\":true"))
        } finally {
            client.close()
            server.stop(50, 100)
            db.close()
        }
        Unit
    }
}
