package com.cobra.dev1new.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cobra.dev1new.CommuteApplication
import com.cobra.dev1new.data.KtxTicketStore

/** Receives a complete replacement list from the user's dev-3 app only. */
class KtxTicketReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_KTX_TICKETS) return
        val tickets = KtxTicketStore.replace(context, intent.getStringExtra(EXTRA_TICKETS_JSON).orEmpty())
        (context.applicationContext as? CommuteApplication)?.travelRepository
            ?.syncExternalKtxTickets(tickets, System.currentTimeMillis())
    }

    companion object {
        const val ACTION_SYNC_KTX_TICKETS = "com.cobra.dev1new.action.SYNC_KTX_TICKETS"
        const val EXTRA_TICKETS_JSON = "tickets_json"
    }
}
