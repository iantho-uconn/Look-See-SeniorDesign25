package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLandmarkInfoServiceTest {
    @Test
    fun fetchesLiveInfoAndEncodesLandmarkId(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(jsonResponse(successResponse))
        val cache = RecordingLiveLandmarkMerchantCache()
        val service = LiveLandmarkInfoService(http, cache)

        val result = service.fetchLiveInfo("landmark/with space")

        assertEquals("landmark-1", result.landmarkId)
        assertEquals("Summer Special", result.activePromotion?.name)
        assertEquals(1, result.activePromotions?.size)
        assertEquals("https://merchant.example", result.merchantWebsite)
        assertEquals("10 Main Street", result.merchantAddress)
        assertTrue(http.requests.single().url.contains("landmark%2Fwith%20space/live-info"))
        assertEquals(3_500, http.requests.single().timeoutMillis)
    }

    @Test
    fun successfulFetchCachesAndPublishesEveryMerchantField(): Unit = runBlocking {
        val cache = RecordingLiveLandmarkMerchantCache()
        val service = LiveLandmarkInfoService(
            RecordingBusinessHttpClient(jsonResponse(successResponse)),
            cache,
        )

        val result = service.fetchLiveInfo("landmark-1", timeoutSeconds = 2.25)

        val profile = cache.saved.single()
        assertEquals("Merchant", profile.merchantName)
        assertEquals("Bio", profile.merchantBio)
        assertEquals("555-0100", profile.merchantPhone)
        assertEquals("https://merchant.example", profile.merchantWebsite)
        assertEquals("10 Main Street", profile.merchantAddress)
        assertEquals("https://merchant.example/logo.png", profile.merchantLogoUrl)
        assertEquals(profile, service.merchantProfile.value)
        assertSame(result, service.latestInfo.value)
    }

    @Test
    fun cachedProfileCanBePublishedBeforeNetworkRefresh() {
        val cached = LiveLandmarkMerchantProfile(
            landmarkId = "landmark-1",
            merchantName = "Cached Merchant",
            merchantWebsite = "https://cached.example",
            merchantAddress = "1 Cache Lane",
        )
        val cache = RecordingLiveLandmarkMerchantCache(
            mutableMapOf(cached.landmarkId to cached),
        )
        val service = LiveLandmarkInfoService(RecordingBusinessHttpClient(), cache)

        assertSame(cached, service.loadCachedMerchantProfile("landmark-1"))
        assertSame(cached, service.merchantProfile.value)
        assertNull(service.latestInfo.value)
    }

    @Test
    fun nonSuccessfulResponsePreservesStatusAndBody(): Unit = runBlocking {
        val service = LiveLandmarkInfoService(
            RecordingBusinessHttpClient(jsonResponse("not found", statusCode = 404)),
            RecordingLiveLandmarkMerchantCache(),
        )

        try {
            service.fetchLiveInfo("missing")
            throw AssertionError("Expected LiveLandmarkInfoServiceException")
        } catch (error: LiveLandmarkInfoServiceException) {
            assertEquals(404, error.statusCode)
            assertEquals("not found", error.responseBody)
        }
    }

    private companion object {
        val successResponse =
            """{
              "ok":true,
              "landmarkId":"landmark-1",
              "label":"Clock Tower",
              "shortDescription":"Historic tower",
              "websiteUrl":"https://landmark.example",
              "isActive":true,
              "promotionEnabled":true,
              "activePromotion":{
                "promotionId":"promotion-1",
                "landmarkId":"landmark-1",
                "landmarkLabel":"Clock Tower",
                "name":"Summer Special",
                "description":"Ten percent off",
                "enabled":true
              },
              "activePromotions":[{
                "promotionId":"promotion-1",
                "landmarkId":"landmark-1",
                "landmarkLabel":"Clock Tower",
                "name":"Summer Special",
                "description":"Ten percent off",
                "enabled":true
              }],
              "activePromotionCount":1,
              "merchantName":"Merchant",
              "merchantBio":"Bio",
              "merchantPhone":"555-0100",
              "merchantWebsite":"https://merchant.example",
              "merchantAddress":"10 Main Street",
              "merchantLogoUrl":"https://merchant.example/logo.png"
            }""".trimIndent()
    }
}

private class RecordingLiveLandmarkMerchantCache(
    private val values: MutableMap<String, LiveLandmarkMerchantProfile> = mutableMapOf(),
) : LiveLandmarkMerchantCache {
    val saved = mutableListOf<LiveLandmarkMerchantProfile>()

    override fun read(landmarkId: String): LiveLandmarkMerchantProfile? = values[landmarkId]

    override fun write(profile: LiveLandmarkMerchantProfile) {
        values[profile.landmarkId] = profile
        saved += profile
    }
}
