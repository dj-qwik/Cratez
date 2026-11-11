package com.cratez.app

import android.app.Application
import com.cratez.app.analysis.AnalysisEngine
import com.cratez.app.data.CratezDb
import com.cratez.app.data.Settings
import com.cratez.app.dupes.DupeScanner
import com.cratez.app.library.Importer
import com.cratez.app.player.PlayerConnection
import com.cratez.app.share.Beam
import com.cratez.app.share.Cloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CratezApp : Application() {
    lateinit var db: CratezDb
    lateinit var settings: Settings
    lateinit var engine: AnalysisEngine
    lateinit var importer: Importer
    lateinit var beam: Beam
    lateinit var cloud: Cloud
    lateinit var dupes: DupeScanner
    lateinit var player: PlayerConnection
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        db = CratezDb.create(this)
        settings = Settings(this)
        engine = AnalysisEngine(this, db, settings)
        importer = Importer(this, db)
        beam = Beam(this, db, settings, importer, engine)
        cloud = Cloud(this, db, settings, importer, engine)
        Cloud.schedule(this)
        dupes = DupeScanner(this)
        player = PlayerConnection(this)
        scope.launch {
            settings.ensureIdentity()
            engine.resumeInterrupted()
            cloud.register()
        }
    }
}
