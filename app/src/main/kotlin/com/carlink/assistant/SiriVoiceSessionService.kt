package com.carlink.assistant

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.Toast
import com.carlink.ui.settings.AdapterConfigPreference

class SiriVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SiriVoiceSession(this)
}

/** Forwards the AAOS assistant invocation to the active CarPlay adapter session. */
internal class SiriVoiceSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // [217] Settings → Adapter Configuration → Misc → Voice Button. Off normally disables these
        // components, so this only catches a session already starting when it was switched off.
        val result =
            if (AdapterConfigPreference.getInstance(context).getVoiceButtonSiriSync()) {
                SiriDelegation.registry.request()
            } else {
                SiriRequestResult.DISABLED
            }
        if (result != SiriRequestResult.QUEUED) {
            Toast.makeText(context, result.feedback, Toast.LENGTH_SHORT).show()
        }
        Handler(Looper.getMainLooper()).post { finish() }
    }
}
