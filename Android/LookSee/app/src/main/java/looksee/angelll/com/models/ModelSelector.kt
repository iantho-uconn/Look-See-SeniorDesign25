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
    val id: String get() = releaseIdentifier
    val releaseIdentifier: String get() = "$clusterId|$modelVersion"
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
    val id: String get() = "$clusterId|$modelVersion|${modelFile.name}"
    val isInstalled: Boolean get() = modelFile.isFile && (manifestFile == null || manifestFile.isFile)
}

internal interface TestModelSelectionStore {
    fun readSelectedId(): String?
    fun writeSelectedId(value: String?)
}

private object EmptyTestModelSelectionStore : TestModelSelectionStore {
    override fun readSelectedId(): String? = null
    override fun writeSelectedId(value: String?) = Unit
}

class ModelSelector internal constructor(
    modelState: StateFlow<ModelState>,
    private val activationRadiusMeters: Double = DEFAULT_ACTIVATION_RADIUS_METERS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val testingEnabled: Boolean = false,
    bundledTestModels: List<BundledTestModel> = emptyList(),
    private val testSelectionStore: TestModelSelectionStore = EmptyTestModelSelectionStore,
) : AutoCloseable {
    constructor(modelService: ModelService) : this(modelState = modelService.state)

    private val selectionLock = Any()
    private val selectorScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _activeRelease = MutableStateFlow<ActiveModelRelease?>(null)
    val activeRelease: StateFlow<ActiveModelRelease?> = _activeRelease.asStateFlow()

    private val _activeClusterId = MutableStateFlow<String?>(null)
    val activeClusterId: StateFlow<String?> = _activeClusterId.asStateFlow()

    val activeModelVersion: String? get() = activeRelease.value?.modelVersion
    val activeClassCount: Int? get() = activeRelease.value?.classCount

    private val _selectedTestModelId = MutableStateFlow<String?>(null)
    val selectedTestModelId: StateFlow<String?> = _selectedTestModelId.asStateFlow()

    val availableTestModels: List<BundledTestModel> = if (testingEnabled) {
        bundledTestModels.filter(BundledTestModel::isInstalled).sortedBy(BundledTestModel::displayName)
    } else {
        emptyList()
    }

    val activeDisplayName: String get() = activeRelease.value?.displayName ?: "No model loaded"

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

    fun updateUserLocation(latitude: Double, longitude: Double) {
        val location = Coordinate(latitude = latitude, longitude = longitude)
        if (!location.isValid()) return

        synchronized(selectionLock) {
            latestUserLocation = location
            if (reactivateSelectedTestModelIfNeeded()) return
            chooseBestRelease(location)
        }
    }

    fun selectTestModel(testModel: BundledTestModel): Boolean = synchronized(selectionLock) {
        if (!testingEnabled || testModel !in availableTestModels || !testModel.isInstalled) return@synchronized false
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
                    if (!reactivateSelectedTestModelIfNeeded()) {
                        latestUserLocation?.let(::chooseBestRelease) ?: chooseDefaultRelease()
                    }
                }
                ModelState.NotLoaded -> {
                    models = emptyList()
                    if (!reactivateSelectedTestModelIfNeeded()) {
                        clearActiveRelease("ModelService is not loaded")
                    }
                }
                ModelState.Loading -> {}
                is ModelState.Failed -> {
                    logger.warning("ModelService failed; keeping active release: ${state.message}")
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
                if (objectLocation.clusterId != model.clusterId) return@objectLoop
                val objectCoordinate = Coordinate(latitude = objectLocation.lat, longitude = objectLocation.lon)
                if (!objectCoordinate.isValid()) return@objectLoop

                val distance = distanceMeters(userLocation, objectCoordinate)
                if (distance <= activationRadiusMeters && distance < closestDistance) {
                    closestDistance = distance
                    closestModel = model
                }
            }
        }

        closestModel?.let { model ->
            activate(model = model, reason = "closest object is ${"%.1f".format(closestDistance)}m away")
            return
        }

        val current = activeRelease.value
        if (current != null && models.any { it.clusterId == current.clusterId && it.modelVersion == current.modelVersion && isCompleteRelease(it) }) {
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
        activate(model = fallback, reason = "defaulting to first complete loaded release")
    }

    private fun activate(model: ModelInfo, reason: String) {
        val candidate = makeActiveRelease(model) ?: return
        if (candidate == activeRelease.value) return
        _activeRelease.value = candidate
        _activeClusterId.value = candidate.clusterId
    }

    private fun restoreTestSelection() {
        if (!testingEnabled) return
        val savedId = testSelectionStore.readSelectedId()
        val selected = savedId?.let { id -> availableTestModels.firstOrNull { it.id == id } } ?: availableTestModels.singleOrNull() ?: return
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
        }
        return true
    }

    private fun clearActiveRelease(reason: String) {
        if (activeRelease.value == null && activeClusterId.value == null) return
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

    override fun close() {
        selectorScope.cancel()
    }

    private data class Coordinate(val latitude: Double, val longitude: Double) {
        fun isValid(): Boolean = latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0
    }

    companion object {
        // 🚀 THE FIX: Expanded to 50 Kilometers so models load instantly for your area!
        const val DEFAULT_ACTIVATION_RADIUS_METERS = 50000.0
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private val logger = Logger.getLogger(ModelSelector::class.java.name)

        @Volatile
        private var sharedInstance: ModelSelector? = null

        fun shared(context: Context): ModelSelector =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ModelSelector(
                    modelState = ModelService.shared(context).state,
                    testingEnabled = BuildConfig.DEBUG,
                    bundledTestModels = discoverBundledTestModels(context),
                    testSelectionStore = AndroidTestModelSelectionStore(context),
                ).also { sharedInstance = it }
            }

        private fun discoverBundledTestModels(context: Context): List<BundledTestModel> {
            val assets = BundledModelAssetInstaller.discover(context)
            val manual = ManualModelDiscovery.discover(context)
            return (assets + manual).sortedBy(BundledTestModel::displayName)
        }

        private fun distanceMeters(from: Coordinate, to: Coordinate): Double {
            val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
            val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
            val fromLatitude = Math.toRadians(from.latitude)
            val toLatitude = Math.toRadians(to.latitude)

            val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                    cos(fromLatitude) * cos(toLatitude) *
                    sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            val bounded = haversine.coerceIn(0.0, 1.0)
            val angularDistance = 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
            return EARTH_RADIUS_METERS * angularDistance
        }
    }
}

private class AndroidTestModelSelectionStore(context: Context) : TestModelSelectionStore {
    private val preferences = context.getSharedPreferences("looksee_model_testing", Context.MODE_PRIVATE)
    override fun readSelectedId(): String? = preferences.getString(SELECTED_ID_KEY, null)
    override fun writeSelectedId(value: String?) {
        preferences.edit().apply {
            if (value == null) remove(SELECTED_ID_KEY) else putString(SELECTED_ID_KEY, value)
        }.apply()
    }
    private companion object { const val SELECTED_ID_KEY = "selectedBundledTestModel" }
}