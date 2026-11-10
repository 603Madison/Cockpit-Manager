package app.cockpitmanager

import android.app.Application

class CockpitApp : Application() {
    val container: AppContainer by lazy { AppContainer.create(this) }
}
