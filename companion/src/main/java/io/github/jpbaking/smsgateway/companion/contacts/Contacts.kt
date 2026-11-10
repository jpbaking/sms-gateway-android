package io.github.jpbaking.smsgateway.companion.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.github.jpbaking.smsgateway.companion.sync.Conversations
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class Contact(val name: String, val photoUri: String?)

data class ContactMatch(val name: String, val number: String, val label: String?, val photoUri: String?)

/**
 * Names (and photos) from this phone's contacts, for numbers that are saved, like a normal SMS
 * app shows them. Read-only, and only with the contacts permission; without it numbers are shown
 * as they are.
 */
class Contacts(private val context: Context) {
    private val resolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, Contact>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val _known = MutableStateFlow<Map<String, Contact>>(emptyMap())

    /** Contacts found so far, by the number as it was looked up. */
    val known: StateFlow<Map<String, Contact>> = _known.asStateFlow()

    private val _permitted = MutableStateFlow(checkPermission())
    val permitted: StateFlow<Boolean> = _permitted.asStateFlow()

    /** The country numbers without a country code are read in: this phone's SIM or network country. */
    val countryIso: String by lazy {
        val tm = context.getSystemService(TelephonyManager::class.java)
        listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)
    }

    private val keys = ConcurrentHashMap<String, String>()

    /** The conversation a number belongs to. See [Conversations.key]. */
    fun conversationKey(number: String): String = keys.getOrPut(number) {
        val e164 = if (number.any { it.isLetter() }) null else PhoneNumberUtils.formatNumberToE164(number, countryIso)
        Conversations.key(number, e164)
    }

    /** A number as people write it in this country, e.g. "0917 123 4567". */
    fun format(number: String): String =
        if (number.any { it.isLetter() }) number
        else PhoneNumberUtils.formatNumber(number, countryIso) ?: number

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = invalidate()
    }

    private var observing = false

    private fun checkPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Call after the permission dialog, and when the app comes back to the foreground. */
    fun onPermissionMaybeChanged() {
        val now = checkPermission()
        if (now != _permitted.value) {
            _permitted.value = now
            invalidate()
        }
        if (now && !observing) {
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        }
    }

    private fun invalidate() {
        cache.clear()
        missing.clear()
        _known.value = emptyMap()
        _generation.update { it + 1 }
    }

    private val _generation = MutableStateFlow(0)

    /** Changes whenever contacts may have changed, so lookups should be redone. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Looks up any of [numbers] not looked up yet. */
    suspend fun resolve(numbers: Collection<String>) {
        if (!_permitted.value) return
        val todo = numbers.filter { !cache.containsKey(it) && it !in missing }
        if (todo.isEmpty()) return
        val found = withContext(Dispatchers.IO) {
            todo.mapNotNull { number -> lookup(number)?.let { number to it } ?: run { missing += number; null } }
        }
        if (found.isEmpty()) return
        cache.putAll(found)
        _known.update { it + found }
    }

    private fun lookup(number: String): Contact? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            resolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) Contact(c.getString(0) ?: return@use null, c.getString(1)) else null
            }
        }.getOrNull()
    }

    /** Contacts whose name or number matches [query], for picking a recipient. */
    suspend fun search(query: String, limit: Int = 30): List<ContactMatch> {
        if (!_permitted.value || query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL,
                        ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE",
                )?.use { c ->
                    val out = ArrayList<ContactMatch>()
                    val seen = HashSet<String>()
                    while (c.moveToNext() && out.size < limit) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        if (!seen.add(name + "|" + conversationKey(number))) continue
                        val label = ContactsContract.CommonDataKinds.Phone
                            .getTypeLabel(context.resources, c.getInt(2), c.getString(3))?.toString()
                        out += ContactMatch(name, number, label, c.getString(4))
                    }
                    out
                }
            }.getOrNull().orEmpty()
        }
    }
}
