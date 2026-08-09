package com.relay.message.service

import com.relay.common.exception.RelayException
import com.relay.message.PostgresTestcontainerConfig
import com.relay.message.model.dto.CreateDialogRequest
import com.relay.message.model.dto.OpenDialogResult
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * The point of `direct_key`: "open the chat with Bob" resolves to one dialog no matter who asks,
 * how often, or how simultaneously. The concurrent case is the one that matters — it is why the
 * uniqueness lives in the schema instead of in a SELECT-before-INSERT.
 *
 * Participant ids are unique per test because the Postgres container is shared across the suite,
 * and a direct dialog is now permanent for the pair that owns it.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
class DialogServiceIT {

    @Autowired private lateinit var dialogService: DialogService

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    @Test
    fun `opening the same chat twice returns the same dialog and reports the second as pre-existing`() {
        val alice = userId("alice")
        val bob = userId("bob")

        val first = dialogService.openDirect(alice, bob)
        val second = dialogService.openDirect(alice, bob)

        assertTrue(first.created)
        assertTrue(!second.created, "a repeat open is not a new dialog")
        assertEquals(first.dialog.id, second.dialog.id)
        assertEquals(setOf(alice, bob), second.dialog.participantIds)
        assertEquals("direct", second.dialog.type)
    }

    @Test
    fun `the dialog does not depend on who opened it`() {
        val alice = userId("alice")
        val bob = userId("bob")

        val opened = dialogService.openDirect(alice, bob)
        val fromTheOtherSide = dialogService.openDirect(bob, alice)

        assertEquals(opened.dialog.id, fromTheOtherSide.dialog.id, "the key is sorted, not caller-first")
        assertTrue(!fromTheOtherSide.created)
    }

    /**
     * More contenders than the two people involved, so several inserts really do land on the
     * constraint rather than the losers happening to read a row that is already committed — both
     * outcomes are correct, but only the first exercises the recovery.
     */
    @Test
    fun `two people opening the same chat at once end up in one dialog`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val openers = 6
        val startTogether = CyclicBarrier(openers)
        val pool = Executors.newFixedThreadPool(openers)

        val results = try {
            (0 until openers).map { i ->
                pool.submit<OpenDialogResult> {
                    startTogether.await()
                    // Alternating direction also asserts the key is sorted under contention.
                    if (i % 2 == 0) dialogService.openDirect(alice, bob) else dialogService.openDirect(bob, alice)
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, results.map { it.dialog.id }.toSet().size, "a glare must not produce two conversations")
        assertEquals(1, results.count { it.created }, "exactly one side opened it; the losers read the winner's row")
    }

    @Test
    fun `refuses a dialog with yourself`() {
        val alice = userId("alice")

        val ex = assertFailsWith<RelayException> { dialogService.openDirect(alice, alice) }

        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `refuses an id carrying the key separator, which would make two pairs share a key`() {
        val ex = assertFailsWith<RelayException> { dialogService.openDirect(userId("alice"), "bob:carol") }

        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `the internal path converges on the same dialog as the client-facing one`() {
        val alice = userId("alice")
        val bob = userId("bob")

        val internal = dialogService.create(CreateDialogRequest(setOf(alice, bob)))
        val clientFacing = dialogService.openDirect(alice, bob)

        assertEquals(internal.id, clientFacing.dialog.id, "two entry points, one dialog per pair")
        assertTrue(!clientFacing.created)
    }

    @Test
    fun `a group is not deduplicated by its membership`() {
        val members = setOf(userId("alice"), userId("bob"), userId("carol"))

        val first = dialogService.create(CreateDialogRequest(members))
        val second = dialogService.create(CreateDialogRequest(members))

        assertEquals("group", first.type)
        assertTrue(first.id != second.id, "the same people can want two separate groups")
    }
}
