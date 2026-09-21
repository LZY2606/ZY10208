package app

fun main(args: Array<String>) {
    var port = 5548
    var dbPath = "data/loss-mirror.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(i + 1)?.toIntOrNull() ?: port; i += 2 }
            "--db" -> { dbPath = args.getOrNull(i + 1) ?: dbPath; i += 2 }
            else -> i += 1
        }
    }
    val db = Database(dbPath)
    if (db.countRuns() == 0) {
        val run = Fixture.build()
        db.upsertRun(run, json.encodeToString(Run.serializer(), run))
        println("空数据库：已自动导入固定 fixture 记录 ${run.id}")
    }
    println("开关损耗镜：http://127.0.0.1:$port")
    startServer(db, port).start(wait = true)
}
