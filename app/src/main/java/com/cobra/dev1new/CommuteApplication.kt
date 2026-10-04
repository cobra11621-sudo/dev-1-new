package com.cobra.dev1new

import android.app.Application
import com.cobra.dev1new.data.TravelRepository

class CommuteApplication : Application() {
    lateinit var travelRepository: TravelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        travelRepository = TravelRepository.get(this)
    }
}
