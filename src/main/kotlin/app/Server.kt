package app

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.serialization.Serializable
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

@Serializable
data class AnalyzeRequest(val config: AnalysisConfig, val save: Boolean = true, val name: String? = null)

@Serializable
data class RunListResponse(val runs: List<RunSummary>)

@Serializable
data class AnalysisListResponse(val analyses: List<AnalysisSummary>)

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    explicitNulls = false
    allowSpecialFloatingPointValues = true
}

object AppServerRoutes {
    private fun loadRun(db: Database, id: String): Run? = db.getRunJson(id)?.let { json.decodeFromString<Run>(it) }

    fun Application.install(db: Database) {
        routing {
            get("/api/runs") {
                call.respondText(json.encodeToString(RunListResponse.serializer(), RunListResponse(db.listRuns())), ContentType.Application.Json)
            }
            get("/api/runs/{id}") {
                val id = call.parameters["id"]!!
                val body = db.getRunJson(id)
                if (body == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                else call.respondText(body, ContentType.Application.Json)
            }
            post("/api/runs/import-fixture") {
                val run = Fixture.build()
                db.upsertRun(run, json.encodeToString(Run.serializer(), run))
                call.respondText(json.encodeToString(Run.serializer(), run), ContentType.Application.Json)
            }
            get("/api/runs/{id}/default-config") {
                val run = loadRun(db, call.parameters["id"]!!)
                if (run == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                else call.respondText(json.encodeToString(AnalysisConfig.serializer(), Fixture.defaultConfig(run)), ContentType.Application.Json)
            }
            post("/api/analyze") {
                val req = json.decodeFromString<AnalyzeRequest>(call.receiveText())
                val run = loadRun(db, req.config.runId)
                    ?: return@post call.respondText("run not found", status = HttpStatusCode.NotFound)
                val result = Analysis.analyze(req.config, run)
                val named = if (req.name != null) result.copy(name = req.name) else result
                if (req.save) {
                    val cfgJson = json.encodeToString(AnalysisConfig.serializer(), named.config)
                    val resJson = json.encodeToString(AnalysisResult.serializer(), named)
                    db.insertAnalysis(named, cfgJson, resJson)
                }
                call.respondText(json.encodeToString(AnalysisResult.serializer(), named), ContentType.Application.Json)
            }
            get("/api/analyses") {
                call.respondText(
                    json.encodeToString(AnalysisListResponse.serializer(), AnalysisListResponse(db.listAnalyses(call.request.queryParameters["runId"]))),
                    ContentType.Application.Json
                )
            }
            get("/api/analyses/{id}") {
                val body = db.getAnalysisResult(call.parameters["id"]!!)
                if (body == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                else call.respondText(body, ContentType.Application.Json)
            }
            get("/api/analyses/{id}/export.csv") {
                val a = db.getAnalysisResult(call.parameters["id"]!!)?.let { json.decodeFromString<AnalysisResult>(it) }
                if (a == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                else {
                    val csv = exportCsv(a)
                    call.respondBytes(csv.toByteArray(Charsets.UTF_8), ContentType.Text.CSV, HttpStatusCode.OK)
                }
            }
            post("/api/admin/clear") {
                db.clearAll()
                call.respondText("""{"cleared":true}""", ContentType.Application.Json)
            }
            get("/api/health") {
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            }
            get("/") {
                val html = object {}.javaClass.getResourceAsStream("/web/index.html")!!.bufferedReader().readText()
                call.respondText(html, ContentType.Text.Html)
            }
            staticResources("/static", "web")
        }
    }

    private fun exportCsv(a: AnalysisResult): String {
        val sb = StringBuilder()
        sb.appendLine("analysis_id,run_id,created_at,window,t0_s,t1_s,energy_J,energy_low_J,energy_high_J,status")
        for (w in a.windows) {
            sb.append(listOf(a.id, a.runId, a.createdAt, w.name, w.t0 ?: "", w.t1 ?: "",
                w.energyJ ?: "", w.energyLowJ ?: "", w.energyHighJ ?: "", w.status).joinToString(",") { "\"$it\"" }).append('\n')
        }
        return sb.toString()
    }
}

fun startServer(db: Database, port: Int) =
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        with(AppServerRoutes) { install(db) }
    }
