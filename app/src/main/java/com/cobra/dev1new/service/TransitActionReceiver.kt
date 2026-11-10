package com.cobra.dev1new.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cobra.dev1new.data.TravelRepository

class TransitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            TransitNotificationFactory.ACTION_CANCEL_PLAN -> {
                TravelRepository.get(context).cancelPlan(System.currentTimeMillis())
                context.stopService(Intent(context, TransitTrackingService::class.java))
            }
            TransitNotificationFactory.ACTION_MANUAL_BOARD_SUBWAY -> {
                // Keep the notification action on the same reducer path as the
                // in-app button; the service chooses and fixes one CSV train.
                TransitTrackingService.requestManualSubwayBoarding(context)
            }
        }
    }
}
