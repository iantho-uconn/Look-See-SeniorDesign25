package looksee.angelll.com.models

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAutoRefreshServiceTest {
    @Test
    fun usableLocationImmediatelyUpdatesLocalModelSelection() {
        val fixture = fixture()

        fixture.service.updateLocation(latitude = 40.7128, longitude = -74.0060)

        assertEquals(listOf(40.7128 to -74.0060), fixture.selectedLocations)
        assertEquals(0, fixture.refreshLocations.size)
        fixture.service.close()
    }

    @Test
    fun refreshWithoutLocationIsSkipped() = runBlocking {
        val fixture = fixture()

        fixture.service.refreshNow()

        assertEquals("Skipped: no location available yet", fixture.service.lastRefreshReason.value)
        assertNull(fixture.service.lastRefreshAtMillis.value)
        assertTrue(fixture.refreshLocations.isEmpty())
        fixture.service.close()
    }

    @Test
    fun manualRefreshUsesLatestLocationAndRecordsChangedResult() = runBlocking {
        val fixture = fixture(refreshResult = true)
        fixture.service.updateLocation(latitude = 40.7128, longitude = -74.0060)

        fixture.service.refreshNow()

        assertEquals(listOf(40.7128 to -74.0060), fixture.refreshLocations)
        assertEquals("Manual refresh", fixture.service.lastRefreshReason.value)
        assertEquals(1_000L, fixture.service.lastRefreshAtMillis.value)
        assertEquals(
            ModelRefreshLocation(40.7128, -74.0060),
            fixture.service.lastRefreshLocation.value,
        )
        assertTrue(fixture.service.lastRefreshChangedModels.value)
        fixture.service.close()
    }

    @Test
    fun manualRefreshBypassesCooldownAndMovementChecks() = runBlocking {
        val fixture = fixture()
        fixture.service.updateLocation(latitude = 40.7128, longitude = -74.0060)
        fixture.service.refreshNow()
        fixture.nowMillis = 1_001L

        fixture.service.refreshNow()

        assertEquals(2, fixture.refreshLocations.size)
        assertEquals("Manual refresh", fixture.service.lastRefreshReason.value)
        fixture.service.close()
    }

    @Test
    fun pollingRefreshSkipsWhileCooldownIsActive() = runBlocking {
        val fixture = fixture()
        fixture.service.updateLocation(latitude = 40.7128, longitude = -74.0060)
        fixture.service.refreshNow()
        fixture.nowMillis = 60_999L
        fixture.service.updateLocation(latitude = 40.7138, longitude = -74.0060)

        fixture.service.performRefreshIfNeeded(force = false)

        assertEquals(1, fixture.refreshLocations.size)
        assertEquals("Skipped: cooldown active", fixture.service.lastRefreshReason.value)
        fixture.service.close()
    }

    @Test
    fun pollingRefreshRequiresFiftyMetersOfMovementAfterCooldown() = runBlocking {
        val fixture = fixture()
        fixture.service.updateLocation(latitude = 40.7128, longitude = -74.0060)
        fixture.service.refreshNow()
        fixture.nowMillis = 61_000L
        fixture.service.updateLocation(latitude = 40.7129, longitude = -74.0060)

        fixture.service.performRefreshIfNeeded(force = false)

        assertEquals(1, fixture.refreshLocations.size)
        assertTrue(fixture.service.lastRefreshReason.value.startsWith("Skipped: moved only"))

        fixture.service.updateLocation(latitude = 40.7134, longitude = -74.0060)
        fixture.service.performRefreshIfNeeded(force = false)

        assertEquals(2, fixture.refreshLocations.size)
        assertEquals("Polling refresh", fixture.service.lastRefreshReason.value)
        fixture.service.close()
    }

    @Test
    fun startIsIdempotentSleepsBeforeFirstPollAndStopCancelsLoop() {
        val pollGate = CompletableDeferred<Unit>()
        var waitCalls = 0
        val fixture = fixture(
            waitForNextPoll = {
                waitCalls += 1
                pollGate.await()
            },
        )
        fixture.service.updateLocation(latitude = 40.7128, longitude = -74.0060)

        fixture.service.start()
        fixture.service.start()

        assertTrue(fixture.service.isPolling.value)
        assertEquals("Started polling", fixture.service.lastRefreshReason.value)
        assertEquals(1, waitCalls)
        assertTrue(fixture.refreshLocations.isEmpty())

        fixture.service.stop()

        assertFalse(fixture.service.isPolling.value)
        assertEquals("Stopped manually", fixture.service.lastRefreshReason.value)
        assertTrue(fixture.refreshLocations.isEmpty())
        fixture.service.close()
    }

    private fun fixture(
        refreshResult: Boolean = false,
        waitForNextPoll: suspend (Long) -> Unit = { CompletableDeferred<Unit>().await() },
    ): Fixture {
        val selectedLocations = mutableListOf<Pair<Double, Double>>()
        val refreshLocations = mutableListOf<Pair<Double, Double>>()
        var nowMillis = 1_000L

        val service = ModelAutoRefreshService(
            refreshModels = { latitude, longitude ->
                refreshLocations += latitude to longitude
                refreshResult
            },
            updateModelSelection = { latitude, longitude ->
                selectedLocations += latitude to longitude
            },
            dispatcher = Dispatchers.Unconfined,
            currentTimeMillis = { nowMillis },
            waitForNextPoll = waitForNextPoll,
        )

        return Fixture(
            service = service,
            selectedLocations = selectedLocations,
            refreshLocations = refreshLocations,
            getNowMillis = { nowMillis },
            setNowMillis = { nowMillis = it },
        )
    }

    private class Fixture(
        val service: ModelAutoRefreshService,
        val selectedLocations: MutableList<Pair<Double, Double>>,
        val refreshLocations: MutableList<Pair<Double, Double>>,
        private val getNowMillis: () -> Long,
        private val setNowMillis: (Long) -> Unit,
    ) {
        var nowMillis: Long
            get() = getNowMillis()
            set(value) = setNowMillis(value)
    }
}
