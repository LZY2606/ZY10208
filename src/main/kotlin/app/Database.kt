package app

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class Database(path: String) {
    private val conn: Connection

    init {
        if (path != ":memory:") {
            val p: Path = Paths.get(path)
            if (p.parent != null) Files.createDirectories(p.parent)
        }
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    data_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analyses (
                    id TEXT PRIMARY KEY,
                    run_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    config_json TEXT NOT NULL,
                    result_json TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun upsertRun(run: Run, json: String) {
        conn.prepareStatement(
            """INSERT INTO runs(id,name,description,created_at,data_json) VALUES(?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description,
               created_at=excluded.created_at, data_json=excluded.data_json"""
        ).use { ps ->
            ps.setString(1, run.id); ps.setString(2, run.name); ps.setString(3, run.description)
            ps.setString(4, run.createdAt); ps.setString(5, json)
            ps.executeUpdate()
        }
    }

    fun getRunJson(id: String): String? =
        conn.prepareStatement("SELECT data_json FROM runs WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun listRuns(): List<RunSummary> {
        val out = mutableListOf<RunSummary>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,name,description,created_at FROM runs ORDER BY created_at").use { rs ->
                while (rs.next()) out.add(RunSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            }
        }
        return out
    }

    fun insertAnalysis(a: AnalysisResult, configJson: String, resultJson: String) {
        conn.prepareStatement(
            "INSERT INTO analyses(id,run_id,name,created_at,config_json,result_json) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, a.id); ps.setString(2, a.runId); ps.setString(3, a.name)
            ps.setString(4, a.createdAt); ps.setString(5, configJson); ps.setString(6, resultJson)
            ps.executeUpdate()
        }
    }

    fun listAnalyses(runId: String? = null): List<AnalysisSummary> {
        val out = mutableListOf<AnalysisSummary>()
        val sql = if (runId == null)
            "SELECT id,run_id,name,created_at FROM analyses ORDER BY created_at"
        else "SELECT id,run_id,name,created_at FROM analyses WHERE run_id=? ORDER BY created_at"
        conn.prepareStatement(sql).use { ps ->
            if (runId != null) ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(AnalysisSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            }
        }
        return out
    }

    fun getAnalysisResult(id: String): String? =
        conn.prepareStatement("SELECT result_json FROM analyses WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun getAnalysisConfig(id: String): String? =
        conn.prepareStatement("SELECT config_json FROM analyses WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun clearAll() {
        conn.createStatement().use {
            it.executeUpdate("DELETE FROM analyses")
            it.executeUpdate("DELETE FROM runs")
        }
    }

    fun countRuns(): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM runs").use { if (it.next()) it.getInt(1) else 0 }
    }

    fun close() = conn.close()
}

@Serializable
data class RunSummary(val id: String, val name: String, val description: String, val createdAt: String)
@Serializable
data class AnalysisSummary(val id: String, val runId: String, val name: String, val createdAt: String)
