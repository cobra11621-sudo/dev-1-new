package com.cobra.dev1new

import android.app.Application
import com.cobra.dev1new.data.KtxTicketStore
import com.cobra.dev1new.data.TravelRepository

class CommuteApplication : Application() {
    lateinit var travelRepository: TravelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        travelRepository = TravelRepository.get(this)
        // No user action is needed after the first dev-3 transfer.  Recheck
        // the stored tickets whenever this app process starts on a new day.
        travelRepository.syncExternalKtxTickets(KtxTicketStore.read(this), System.currentTimeMillis())
    }
}
