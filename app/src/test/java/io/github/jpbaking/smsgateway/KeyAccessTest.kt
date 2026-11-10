package io.github.jpbaking.smsgateway

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.jpbaking.smsgateway.data.ApiKeys
import io.github.jpbaking.smsgateway.data.AppDatabase
import io.github.jpbaking.smsgateway.data.Scope
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.data.WebhookEntity
import io.github.jpbaking.smsgateway.data.WebhookOrigin
import io.github.jpbaking.smsgateway.server.EventStream
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.availableForRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What follows a key when it is switched off, narrowed, regenerated or revoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyAccessTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val settings = Settings(context)

    /** The keys whose event streams were asked to close, in order. */
    private val closed = mutableListOf<String>()
    private val keys = ApiKeys(db.apiKeys(), settings, db.webhooks(), onAccessReduced = { closed += it })

    @After
    fun close() = db.close()

    private fun webhook(id: String, keyId: String?) = WebhookEntity(
        id = id,
        url = "https://example.com/$id",
        events = "sms:received",
        secret = "secret",
        createdAt = 1,
        createdByKeyId = keyId,
        createdVia = if (keyId == null) WebhookOrigin.PHONE else WebhookOrigin.API,
    )

    @Test
    fun aNewKeyGetsTheScopesItWasCreatedWith(): Unit = runBlocking {
        val plain = keys.create("plain")
        assertEquals(setOf(Scope.SEND, Scope.READ), keys.authenticate(plain.plaintext)?.scopeSet())
        assertFalse(plain.record.needsReview)

        val listener = keys.create("listener", setOf(Scope.EVENTS))
        assertEquals(setOf(Scope.EVENTS), keys.authenticate(listener.plaintext)?.scopeSet())

        assertThrows(IllegalArgumentException::class.java) { runBlocking { keys.create("nothing", emptySet()) } }
        assertNull(keys.authenticate("sgw_not-a-key"))
    }

    @Test
    fun aDisabledKeyIsRefusedUntilItIsEnabledAgain(): Unit = runBlocking {
        val issued = keys.create("billing")
        val id = issued.record.id

        keys.setEnabled(id, false)
        assertNull(keys.authenticate(issued.plaintext))
        assertFalse(keys.allowsDelivery(id))
        assertEquals(listOf(id), closed)

        keys.setEnabled(id, true)
        assertNotNull(keys.authenticate(issued.plaintext))
        assertTrue(keys.allowsDelivery(id))
        // Turning a key on takes nothing away, so nothing is closed.
        assertEquals(listOf(id), closed)
    }

    @Test
    fun takingAScopeAwayClosesStreamsAndAddingOneDoesNot(): Unit = runBlocking {
        val issued = keys.create("billing")
        val id = issued.record.id
        val asOpened = keys.authenticate(issued.plaintext)!!

        keys.setScopes(id, Scope.ALL)
        assertTrue(closed.isEmpty())
        assertTrue(keys.stillAllows(asOpened, Scope.EVENTS))

        keys.setScopes(id, setOf(Scope.SEND))
        assertEquals(listOf(id), closed)
        assertFalse(keys.stillAllows(asOpened, Scope.EVENTS))
        assertTrue(keys.stillAllows(asOpened, Scope.SEND))

        assertThrows(IllegalArgumentException::class.java) { runBlocking { keys.setScopes(id, emptySet()) } }
    }

    @Test
    fun aRegeneratedKeyLeavesTheOldValueWithNothing(): Unit = runBlocking {
        val issued = keys.create("billing")
        val asOpened = keys.authenticate(issued.plaintext)!!

        val again = keys.regenerate(issued.record.id)!!
        assertNull(keys.authenticate(issued.plaintext))
        assertNotNull(keys.authenticate(again.plaintext))
        // A connection opened with the old value must not carry on under the new one.
        assertFalse(keys.stillAllows(asOpened, Scope.SEND))
        assertEquals(listOf(issued.record.id), closed)
    }

    @Test
    fun revokingAKeyRemovesItsWebhooksAndNobodyElses(): Unit = runBlocking {
        val mine = keys.create("mine")
        val other = keys.create("other")
        db.webhooks().insert(webhook("w-mine", mine.record.id))
        db.webhooks().insert(webhook("w-other", other.record.id))
        db.webhooks().insert(webhook("w-phone", null))

        assertTrue(keys.revoke(mine.record.id))
        assertEquals(listOf("w-other", "w-phone"), db.webhooks().all().map { it.id }.sorted())
        assertNull(keys.authenticate(mine.plaintext))
        assertFalse(keys.allowsDelivery(mine.record.id))
        assertEquals(listOf(mine.record.id), closed)
        assertNotNull(keys.authenticate(other.plaintext))
    }

    @Test
    fun keysFromBeforeScopesAreMarkedReviewedOnceTheirScopesAreSaved(): Unit = runBlocking {
        val issued = keys.create("old")
        val id = issued.record.id
        db.openHelper.writableDatabase.execSQL("UPDATE api_keys SET needsReview = 1 WHERE id = ?", arrayOf(id))
        assertTrue(db.apiKeys().get(id)!!.needsReview)

        keys.setScopes(id, setOf(Scope.SEND, Scope.READ))
        assertFalse(db.apiKeys().get(id)!!.needsReview)
    }

    @Test
    fun anEventStreamEndsWhenItsKeysStreamsAreClosed(): Unit = runBlocking {
        settings.eventStreamEnabled = true
        val stream = EventStream(db.events(), settings)
        val job = launch(Dispatchers.Default) { stream.serve(ByteChannel(autoFlush = true), null, null, keyId = "k1") }
        withTimeout(5_000) { stream.clients.first { it == 1 } }

        // Another key's streams closing leaves this one alone.
        stream.closeStreamsOf("k2")
        delay(300)
        assertTrue(job.isActive)

        stream.closeStreamsOf("k1")
        withTimeout(5_000) { job.join() }
        assertEquals(0, stream.clients.value)
    }

    @Test
    fun anEventStreamDoesNotStartForAKeyThatJustLostAccess(): Unit = runBlocking {
        settings.eventStreamEnabled = true
        val stream = EventStream(db.events(), settings)
        val out = ByteChannel(autoFlush = true)
        stream.serve(out, null, null, keyId = "k1") { false }
        assertEquals(0, stream.clients.value)
        assertEquals(0, out.availableForRead)
    }
}
