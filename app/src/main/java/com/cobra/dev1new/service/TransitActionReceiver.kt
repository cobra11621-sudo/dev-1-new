package com.cobra.dev1new.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cobra.dev1new.data.TravelRepository

class TransitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TransitNotificationFactory.ACTION_CANCEL_PLAN) return
        TravelRepository.get(context).cancelPlan(System.currentTimeMillis())
        context.stopService(Intent(context, TransitTrackingService::class.java))
    }
}
