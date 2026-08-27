package looksee.angelll.com.models

import android.content.Context
import looksee.angelll.com.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.logging.Logger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The complete model/manifest identity required by Detector.
 *
 * Android loads [modelFile] directly as a TensorFlow Lite artifact; unlike iOS,
 * there is no compiled Core ML directory.
 */
data class ActiveModelRelease(
    val clusterId: String,
    val modelVersion: String,
    val modelFile: File,
    val manifestFile: File?,
    val classCount: Int,
    val modelKey: String?,
    val manifestKey: String?,
    val displayName: String = "Cluster $clusterId",
    val classLabels: List<String> = emptyList(),
) {
    val id: String
        get() = releaseIdentifier

    val releaseIdentifier: String
        get() = "$clusterId|$modelVersion"
}

data class BundledTestModel(
    val modelFile: File,
    val displayName: String = modelFile.nameWithoutExtension.replace('_', ' '),
    val classLabels: List<String> = emptyList(),
    val clusterId: String = "bundled-test",
    val modelVersion: String = "bundled-${modelFile.nameWithoutExtension}",
    val manifestFile: File? = null,
    val classCount: Int = classLabels.size,
    val modelKey: String = modelFile.name,
    val manifestKey: String? = manifestFile?.name,
) {
    val id: String
        get() = "$clusterId|$modelVersion|${modelFile.name}"

    val isInstalled: Boolean
        get() = modelFile.isFile && (manifestFile == null || manifestFile.isFile)
}

internal interface TestModelSelectionStore {
    fun readSelectedId(): String?
    fun writeSelectedId(value: String?)
}

private object EmptyTestModelSelectionStore : TestModelSelectionStore {
    override fun readSelectedId(): String? = null
    override fun writeSelectedId(value: String?) = Unit
}

/**
 * Selects one complete local model release from [ModelService] state.
 *
 * The closest object inside 75 meters wins. When no object is inside that
 * radius, the current exact cluster/version stays active while it remains a
 * complete loaded release; otherwise the first complete release is used.
 */
class ModelSelector internal constructor(
    modelState: StateFlow<ModelState>,
    private val activationRadiusMeters: Double = DEFAULT_ACTIVATION_RADIUS_METERS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val testingEnabled: Boolean = false,
    bundledTestModels: List<BundledTestModel> = emptyList(),
    private val testSelectionStore: TestModelSelectionStore =
        EmptyTestModelSelectionStore,
) : AutoCloseable {
    constructor(modelService: ModelService) : this(modelState = modelService.state)

    private val selectionLock = Any()
    private val selectorScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _activeRelease = MutableStateFlow<ActiveModelRelease?>(null)
    val activeRelease: StateFlow<ActiveModelRelease?> = _activeRelease.asStateFlow()

    /** Compatibility state for code that observes only the cluster ID. */
    private val _activeClusterId = MutableStateFlow<String?>(null)
    val activeClusterId: StateFlow<String?> = _activeClusterId.asStateFlow()

    val activeModelVersion: String?
        get() = activeRelease.value?.modelVersion

    val activeClassCount: Int?
        get() = activeRelease.value?.classCount

    private val _selectedTestModelId = MutableStateFlow<String?>(null)
    val selectedTestModelId: StateFlow<String?> = _selectedTestModelId.asStateFlow()

    val availableTestModels: List<BundledTestModel> = if (testingEnabled) {
        bundledTestModels
            .filter(BundledTestModel::isInstalled)
            .sortedBy(BundledTestModel::displayName)
    } else {
        emptyList()
    }

    val activeDisplayName: String
        get() = activeRelease.value?.displayName ?: "No model loaded"

    private var models: List<ModelInfo> = emptyList()
    private var latestUserLocation: Coordinate? = null

    init {
        require(activationRadiusMeters.isFinite() && activationRadiusMeters >= 0.0) {
            "activationRadiusMeters must be finite and non-negative."
        }

        restoreTestSelection()

        selectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            modelState.collect(::handleModelState)
        }
    }

    /** Updates selection using a WGS84 latitude and longitude. */
    fun updateUserLocation(latitude: Double, longitude: Double) {
        val location = Coordinate(latitude = latitude, longitude = longitude)
        if (!location.isValid()) {
            logger.warning(
                "Ignoring invalid user location latitude=$latitude, longitude=$longitude.",
            )
            return
        }

        synchronized(selectionLock) {
            latestUserLocation = location
            if (reactivateSelectedTestModelIfNeeded()) return
            chooseBestRelease(location)
        }
    }

    fun selectTestModel(testModel: BundledTestModel): Boolean = synchronized(selectionLock) {
        if (!testingEnabled || testModel !in availableTestModels || !testModel.isInstalled) {
            return@synchronized false
        }
        _selectedTestModelId.value = testModel.id
        testSelectionStore.writeSelectedId(testModel.id)
        activate(testModel)
    }

    fun useAutomaticModelSelection() = synchronized(selectionLock) {
        if (_selectedTestModelId.value == null) return@synchronized
        _selectedTestModelId.value = null
        testSelectionStore.writeSelectedId(null)
        latestUserLocation?.let(::chooseBestRelease) ?: chooseDefaultRelease()
    }

    private fun handleModelState(state: ModelState) {
        synchronized(selectionLock) {
            when (state) {
                is ModelState.Loaded -> {
                    models = state.models
                    val completeCount = models.count(::isCompleteRelease)
                    logger.info(
                        "ModelSelector received ${models.size} loaded model records " +
                                "($completeCount complete releases).",
                    )

                    if (!reactivateSelectedTestModelIfNeeded()) {
                        latestUserLocation?.let(::chooseBestRelease)
                            ?: chooseDefaultRelease()
                    }
                }

                ModelState.NotLoaded -> {
                    models = emptyList()
                    if (!reactivateSelectedTestModelIfNeeded()) {
                        clearActiveRelease("ModelService is not loaded")
                    }
                }

                ModelState.Loading -> {
                    // Preserve the current complete release during visible refreshes.
                }

                is ModelState.Failed -> {
                    // Preserve the current local release during backend/network failure.
                    logger.warning(
                        "ModelService failed; keeping active release: ${state.message}",
                    )
                }
            }
        }
    }

    private fun chooseBestRelease(userLocation: Coordinate) {
        var closestModel: ModelInfo? = null
        var closestDistance = Double.POSITIVE_INFINITY

        models.forEach { model ->
            if (!isCompleteRelease(model)) return@forEach

            model.objects.forEach objectLoop@{ objectLocation ->
                // ModelService normally performs this filtering. Keep the check here
                // so a malformed/injected ModelInfo cannot activate another cluster.
                if (objectLocation.clusterId != model.clusterId) return@objectLoop

                val objectCoordinate = Coordinate(
                    latitude = objectLocation.lat,
                    longitude = objectLocation.lon,
                )
                if (!objectCoordinate.isValid()) return@objectLoop

                val distance = distanceMeters(userLocation, objectCoordinate)
                if (distance <= activationRadiusMeters && distance < closestDistance) {
                    closestDistance = distance
                    closestModel = model
                }
            }
        }

        closestModel?.let { model ->
            activate(
                model = model,
                reason = "closest object is ${"%.1f".format(closestDistance)}m away",
            )
            return
        }

        val current = activeRelease.value
        if (current != null && models.any { model ->
                model.clusterId == current.clusterId &&
                        model.modelVersion == current.modelVersion &&
                        isCompleteRelease(model)
            }
        ) {
            logger.info(
                "No objects within ${"%.1f".format(activationRadiusMeters)}m; " +
                        "keeping release ${current.releaseIdentifier} active.",
            )
            return
        }

        chooseDefaultRelease()
    }

    private fun chooseDefaultRelease() {
        val fallback = models.firstOrNull(::isCompleteRelease)
        if (fallback == null) {
            clearActiveRelease("No complete model releases available")
            return
        }

        activate(
            model = fallback,
            reason = "defaulting to first complete loaded release",
        )
    }

    private fun activate(model: ModelInfo, reason: String) {
        val candidate = makeActiveRelease(model)
        if (candidate == null) {
            logger.warning(
                "Refusing to activate incomplete release " +
                        "cluster=${model.clusterId}, version=${model.modelVersion}.",
            )
            return
        }

        if (candidate == activeRelease.value) return

        val previous = activeRelease.value?.releaseIdentifier ?: "none"
        _activeRelease.value = candidate
        _activeClusterId.value = candidate.clusterId

        logger.info(
            "Active model release changed: previous=$previous, " +
                    "current=${candidate.releaseIdentifier}, " +
                    "classCount=${candidate.classCount}, " +
                    "model=${candidate.modelFile.name}, " +
                    "manifest=${candidate.manifestFile?.name ?: "none"}, reason=$reason.",
        )
    }

    private fun restoreTestSelection() {
        if (!testingEnabled) return
        val savedId = testSelectionStore.readSelectedId()
        val selected = savedId?.let { id ->
            availableTestModels.firstOrNull { it.id == id }
        } ?: availableTestModels.singleOrNull() ?: return
        _selectedTestModelId.value = selected.id
        if (savedId != selected.id) testSelectionStore.writeSelectedId(selected.id)
        activate(selected)
    }

    private fun reactivateSelectedTestModelIfNeeded(): Boolean {
        if (!testingEnabled) return false
        val selectedId = _selectedTestModelId.value ?: return false
        val selected = availableTestModels.firstOrNull { it.id == selectedId } ?: return false
        return activate(selected)
    }

    private fun activate(testModel: BundledTestModel): Boolean {
        if (!testModel.isInstalled) return false
        val candidate = ActiveModelRelease(
            clusterId = testModel.clusterId,
            modelVersion = testModel.modelVersion,
            modelFile = testModel.modelFile,
            manifestFile = testModel.manifestFile,
            classCount = testModel.classCount,
            modelKey = testModel.modelKey,
            manifestKey = testModel.manifestKey,
            displayName = testModel.displayName,
            classLabels = testModel.classLabels,
        )
        if (candidate != _activeRelease.value) {
            _activeRelease.value = candidate
            _activeClusterId.value = candidate.clusterId
            logger.info("Bundled test model selected: ${candidate.displayName}.")
        }
        return true
    }

    private fun clearActiveRelease(reason: String) {
        if (activeRelease.value == null && activeClusterId.value == null) {
            if (models.isEmpty()) logger.info(reason)
            return
        }

        logger.info("Clearing active model release: $reason.")
        _activeRelease.value = null
        _activeClusterId.value = null
    }

    private fun isCompleteRelease(model: ModelInfo): Boolean =
        model.classCount >= 0 && model.modelFile.isFile && model.manifestFile.isFile

    private fun makeActiveRelease(model: ModelInfo): ActiveModelRelease? {
        if (!isCompleteRelease(model)) return null

        return ActiveModelRelease(
            clusterId = model.clusterId,
            modelVersion = model.modelVersion,
            modelFile = model.modelFile,
            manifestFile = model.manifestFile,
            classCount = model.classCount,
            modelKey = model.modelKey,
            manifestKey = model.manifestKey,
            displayName = "Cluster ${model.clusterId}",
        )
    }

    /** Stops this selector's state observer. Do not close the shared instance. */
    override fun close() {
        selectorScope.cancel()
    }

    private data class Coordinate(
        val latitude: Double,
        val longitude: Double,
    ) {
        fun isValid(): Boolean =
            latitude.isFinite() && latitude in -90.0..90.0 &&
                    longitude.isFinite() && longitude in -180.0..180.0
    }

    companion object {
        const val DEFAULT_ACTIVATION_RADIUS_METERS = 75.0
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private val logger = Logger.getLogger(ModelSelector::class.java.name)

        @Volatile
        private var sharedInstance: ModelSelector? = null

        /** Android's app-context-initialized equivalent of Swift's `shared`. */
        fun shared(context: Context): ModelSelector =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ModelSelector(
                    modelState = ModelService.shared(context).state,
                    testingEnabled = BuildConfig.DEBUG,
                    bundledTestModels = discoverBundledTestModels(context),
                    testSelectionStore = AndroidTestModelSelectionStore(context),
                )
                    .also { sharedInstance = it }
            }

        private fun discoverBundledTestModels(context: Context): List<BundledTestModel> =
            BundledModelAssetInstaller.discover(context)

        /** Pure Kotlin haversine distance keeps selector tests local/JVM-only. */
        private fun distanceMeters(from: Coordinate, to: Coordinate): Double {
            val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
            val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
            val fromLatitude = Math.toRadians(from.latitude)
            val toLatitude = Math.toRadians(to.latitude)

            val haversine =
                sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                        cos(fromLatitude) * cos(toLatitude) *
                        sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            val bounded = haversine.coerceIn(0.0, 1.0)
            val angularDistance = 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
            return EARTH_RADIUS_METERS * angularDistance
        }
    }
}

private class AndroidTestModelSelectionStore(context: Context) : TestModelSelectionStore {
    private val preferences = context.getSharedPreferences(
        "looksee_model_testing",
        Context.MODE_PRIVATE,
    )

    override fun readSelectedId(): String? = preferences.getString(SELECTED_ID_KEY, null)

    override fun writeSelectedId(value: String?) {
        preferences.edit().apply {
            if (value == null) remove(SELECTED_ID_KEY) else putString(SELECTED_ID_KEY, value)
        }.apply()
    }

    private companion object {
        const val SELECTED_ID_KEY = "selectedBundledTestModel"
    }
}
