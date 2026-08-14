/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * Launch screen: shows the animated jelly wordmark briefly, then hands over
 * to MainActivity. The window background already contains the static banner
 * (instant paint), so the animated GIF simply replaces it for a moment.
 */
package org.lineageos.jelly

import android.content.Intent
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val image = findViewById<ImageView>(R.id.splashGif)
        // Fade the static background wordmark into the animated one.
        image.animate()
            .alpha(0f)
            .setDuration(0)
            .withEndAction {
                image.setImageDrawable(decodeWordmark())
                image.animate().alpha(1f).setDuration(220).start()
            }
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            image.animate().alpha(0.6f).setDuration(180).withEndAction {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }.start()
        }, 1150)
    }

    private fun decodeWordmark(): Drawable? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(assets, "jelly-title.gif")
                ).apply {
                    (this as? AnimatedImageDrawable)?.start()
                }
            } else {
                resources.getDrawable(R.drawable.splash_logo, theme)
            }
        } catch (e: Exception) {
            resources.getDrawable(R.drawable.splash_logo, theme)
        }
    }
}
