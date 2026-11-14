package com.deskmail

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where the browser comes back to the app after a sign-in at the provider (the address registered
 * with Microsoft, [com.deskmail.core.provider.MicrosoftSignIn.REDIRECT_URI]). What it brought goes
 * to the sign-in that waits for it ([com.deskmail.data.BrowserSignIn]), and the app's window is
 * brought back over the browser's tab, which closes. It shows nothing of its own.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.data?.let { deskMail.signIn.cameBack(it.toString()) }
        // the window the sign-in was begun in, with whatever was over it (the browser's tab) gone
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
