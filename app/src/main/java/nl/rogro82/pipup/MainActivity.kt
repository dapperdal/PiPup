/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package nl.rogro82.pipup

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import nl.rogro82.pipup.Utils.getIpAddress

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set the shared secret once over adb:
        //   adb shell am start -n nl.rogro82.pipup/.MainActivity --es token <secret>
        // After that, requests without "Authorization: Bearer <secret>" are refused. Changing or clearing it (empty
        // value) needs the current one too (--es old_token <current>), so another app on the TV can't swap it.
        intent.getStringExtra(PiPupService.PREF_TOKEN)?.let { newToken ->
            val prefs = getSharedPreferences(PiPupService.PREFS, MODE_PRIVATE)
            val current = prefs.getString(PiPupService.PREF_TOKEN, null)
            if (current.isNullOrEmpty() || intent.getStringExtra("old_token") == current) {
                prefs.edit().putString(PiPupService.PREF_TOKEN, newToken.trim()).apply()
            } else {
                Log.w(PiPupService.LOG_TAG, "token not changed: old_token missing or wrong")
            }
        }

        // start service in foreground

        val textViewConnection = findViewById<TextView>(R.id.textViewServerAddress)
        val textViewServerAddress = findViewById<TextView>(R.id.textViewServerAddress)

        when(val ipAddress = getIpAddress()) {
            is String -> {
                textViewConnection.setText(R.string.server_running)
                textViewServerAddress.apply {
                    visibility = View.VISIBLE
                    text = resources.getString(
                        R.string.server_address,
                        ipAddress,
                        PiPupService.SERVER_PORT
                    )
                }
            }
            else -> {
                textViewConnection.setText(R.string.no_network_connection)
                textViewServerAddress.visibility = View.INVISIBLE
            }
        }


        val serviceIntent = Intent(this, PiPupService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
