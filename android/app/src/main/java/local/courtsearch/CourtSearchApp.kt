package local.courtsearch

import android.app.Application

class CourtSearchApp : Application() {
    lateinit var search: SearchController
        private set

    override fun onCreate() {
        super.onCreate()
        search = SearchController(this)
    }
}
