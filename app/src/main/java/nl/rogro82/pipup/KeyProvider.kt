package nl.rogro82.pipup

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Sets the shared key that PiPupService requires on every request ("Authorization: Bearer <key>").
 * Only adb (the shell user) or root may call it, so no other app on the TV can set, change or clear the key:
 *
 *   adb shell content call --uri content://nl.rogro82.pipup.key --method set_key --arg <key>
 *
 * An empty --arg clears the key, which lets any device on the network send popups again.
 */
class KeyProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("only adb may set the PiPup key")
        }
        if (method != "set_key") throw IllegalArgumentException("unknown method: $method")

        context!!.getSharedPreferences(PiPupService.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(PiPupService.PREF_TOKEN, arg?.trim().orEmpty()).apply()

        return Bundle().apply { putBoolean("key_set", !arg.isNullOrBlank()) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
