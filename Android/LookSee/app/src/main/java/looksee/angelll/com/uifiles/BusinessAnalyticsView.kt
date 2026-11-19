package looksee.angelll.com.uifiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.viewmodels.AuthViewModel
import kotlin.math.cos
import kotlin.math.sin

data class TimeSeriesPoint(val date: Date, val clicks: Int)

enum class AnalyticsChartMode { Trend, Distribution }

class BusinessAnalyticsViewModel : ViewModel() {
    var data by mutableStateOf<List<LandmarkAnalyticsData>>(emptyList())
    var isLoading by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)

    val totalViews: Int get() = data.sumOf { it.totalClicks }

    val topLandmark: String
        get() = if (totalViews > 0) data.maxByOrNull { it.totalClicks }?.label ?: "N/A" else "N/A"

    val activeLandmarksWithViews: List<LandmarkAnalyticsData>
        get() = data.filter { it.totalClicks > 0 }.sortedByDescending { it.totalClicks }

    val sortedLandmarks: List<LandmarkAnalyticsData>
        get() = data.sortedWith(compareByDescending<LandmarkAnalyticsData> { it.totalClicks }.thenBy { it.label })

    val timeSeriesData: List<TimeSeriesPoint>
        get() {
            val template = data.firstOrNull()?.dailyData ?: return emptyList()
            val merged = mutableMapOf<String, Int>()
            for (item in data) {
                for (point in item.dailyData) {
                    merged[point.date] = (merged[point.date] ?: 0) + point.clicks
                }
            }
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val formatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            return template.mapNotNull { point ->
                val exactDate = formatter.parse("${point.date}/$currentYear") ?: return@mapNotNull null
                TimeSeriesPoint(exactDate, merged[point.date] ?: 0)
            }.sortedBy { it.date }
        }

    fun fetchAnalytics(vm: AuthViewModel) {
        if (vm.userId.isEmpty()) return
        isLoading = true
        errorMessage = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = vm.fetchIdToken()
                val url = URL("https://d11vl3v9w133rh.cloudfront.net/analytics")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }

                val code = conn.responseCode
                val responseData = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }

                withContext(Dispatchers.Main) {
                    if (code in 200..299 && responseData != null) {
                        try {
                            val decoded = Gson().fromJson(responseData, AnalyticsResponse::class.java)
                            data = decoded.analytics
                            isLoading = false
                        } catch (_: Exception) {
                            errorMessage = "AWS sent back: $responseData"
                            isLoading = false
                        }
                    } else {
                        errorMessage = "Failed to load data. Status Code: $code"
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = e.localizedMessage
                    isLoading = false
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessAnalyticsView(
    vm: AuthViewModel,
    onBack: () -> Unit
) {
    val viewModel = remember { BusinessAnalyticsViewModel() }
    var chartMode by remember { mutableStateOf(AnalyticsChartMode.Trend) }

    val primaryColor = Color(0xFF387DFF)
    val secondaryGrouped = Color(0xFF1C1C1E)

    LaunchedEffect(Unit) {
        viewModel.fetchAnalytics(vm)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchAnalytics(vm) }, enabled = !viewModel.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (viewModel.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryColor)
                }
            } else if (viewModel.errorMessage != null) {
                ErrorStateView(viewModel.errorMessage!!)
            } else if (viewModel.data.isEmpty()) {
                AnalyticsEmptyStateView()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    KpiGrid(viewModel, primaryColor, secondaryGrouped)

                    ChartToggle(chartMode) { chartMode = it }

                    if (chartMode == AnalyticsChartMode.Trend) {
                        TrendChartSection(viewModel, primaryColor, secondaryGrouped)
                    } else {
                        DistributionChartSection(viewModel, secondaryGrouped)
                    }

                    LeaderboardSection(viewModel, primaryColor, secondaryGrouped)
                }
            }
        }
    }
}

@Composable
private fun KpiGrid(viewModel: BusinessAnalyticsViewModel, primaryColor: Color, bg: Color) {
    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(20.dp))
                .background(bg, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TOTAL VIEWS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Text("${viewModel.totalViews}", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(32.dp), tint = primaryColor.copy(alpha = 0.2f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(title = "LANDMARKS", value = "${viewModel.data.size}", icon = Icons.Default.Place, color = Color(0xFFAF52DE), bg = bg, modifier = Modifier.weight(1f))
            KpiCard(title = "TOP PERFORMER", value = viewModel.topLandmark, icon = Icons.Default.Star, color = Color(0xFFFF9F0A), bg = bg, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KpiCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, bg: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .background(bg, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        }
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChartToggle(selectedMode: AnalyticsChartMode, onModeSelect: (AnalyticsChartMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(Color(0xFF2C2C2E), RoundedCornerShape(8.dp))
            .padding(2.dp)
    ) {
        val trendBg = if (selectedMode == AnalyticsChartMode.Trend) Color(0xFF3A3A3C) else Color.Transparent
        val distBg = if (selectedMode == AnalyticsChartMode.Distribution) Color(0xFF3A3A3C) else Color.Transparent

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.dp))
                .background(trendBg)
                .clickable { onModeSelect(AnalyticsChartMode.Trend) }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Over Time", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.dp))
                .background(distBg)
                .clickable { onModeSelect(AnalyticsChartMode.Distribution) }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Breakdown", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

@Composable
private fun TrendChartSection(viewModel: BusinessAnalyticsViewModel, primaryColor: Color, bg: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Engagement Over Time (30 Days)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(24.dp))
                .background(bg, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (viewModel.totalViews == 0) {
                NoDataChartPlaceholder()
            } else {
                SimpleLineChart(viewModel.timeSeriesData, primaryColor)
            }
        }
    }
}

@Composable
private fun DistributionChartSection(viewModel: BusinessAnalyticsViewModel, bg: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Views by Landmark",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(24.dp))
                .background(bg, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (viewModel.activeLandmarksWithViews.isEmpty()) {
                NoDataChartPlaceholder()
            } else {
                DonutChartWithLegend(
                    landmarks = viewModel.activeLandmarksWithViews,
                    totalViews = viewModel.totalViews
                )
            }
        }
    }
}

@Composable
private fun SimpleLineChart(data: List<TimeSeriesPoint>, color: Color) {
    if (data.isEmpty()) return
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.US) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(195.dp)) {
            val maxViews = data.maxOf { it.clicks }.coerceAtLeast(1)
            val w = size.width
            val h = size.height

            val path = Path()
            val areaPath = Path()

            data.forEachIndexed { i, point ->
                val x = if (data.size > 1) (i.toFloat() / (data.size - 1)) * w else w / 2f
                val y = h - (point.clicks.toFloat() / maxViews) * h
                if (i == 0) {
                    path.moveTo(x, y)
                    areaPath.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                    areaPath.lineTo(x, y)
                }
            }

            areaPath.lineTo(w, h)
            areaPath.lineTo(0f, h)
            areaPath.close()

            drawPath(areaPath, Brush.verticalGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent)))
            drawPath(path, color, style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // X-Axis Date Marks (5 desired marks matching iOS .chartXAxis)
        if (data.size >= 2) {
            val indices = remember(data.size) {
                (0 until 5).map { step -> ((step / 4f) * (data.size - 1)).toInt() }.distinct()
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                indices.forEach { idx ->
                    Text(
                        text = dateFormat.format(data[idx].date),
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

// Ported from iOS 17 SectorMark Donut Chart (innerRadius: 0.618, percentage labels > 10%, bottom legend)
@Composable
private fun DonutChartWithLegend(
    landmarks: List<LandmarkAnalyticsData>,
    totalViews: Int
) {
    val palette = listOf(
        Color(0xFF387DFF),
        Color(0xFFAF52DE),
        Color(0xFFFF9F0A),
        Color(0xFF32D74B),
        Color(0xFFFF453A),
        Color(0xFF64D2FF),
        Color(0xFFFFD60A)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Canvas(modifier = Modifier.size(190.dp)) {
            val diameter = size.minDimension
            val strokeWidth = diameter * (1f - 0.618f) / 2f
            val arcDiameter = diameter - strokeWidth
            val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
            val labelRadius = arcDiameter / 2f

            var startAngle = -90f
            val gapDegrees = if (landmarks.size > 1) 2f else 0f

            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
            }

            landmarks.forEachIndexed { index, landmark ->
                val fraction = landmark.totalClicks.toFloat() / totalViews.coerceAtLeast(1).toFloat()
                val sweep = (fraction * 360f - gapDegrees).coerceAtLeast(1f)
                val sliceColor = palette[index % palette.size]

                drawArc(
                    color = sliceColor,
                    startAngle = startAngle + gapDegrees / 2f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcDiameter, arcDiameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )

                if (fraction > 0.10f) {
                    val midAngleRad = Math.toRadians((startAngle + gapDegrees / 2f + sweep / 2f).toDouble())
                    val cx = center.x + (labelRadius * cos(midAngleRad)).toFloat()
                    val cy = center.y + (labelRadius * sin(midAngleRad)).toFloat() - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    drawContext.canvas.nativeCanvas.drawText(
                        "${(fraction * 100).toInt()}%",
                        cx,
                        cy,
                        textPaint
                    )
                }

                startAngle += fraction * 360f
            }
        }

        // Bottom Chart Legend
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            landmarks.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowItems.forEach { item ->
                        val colorIdx = landmarks.indexOf(item)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(palette[colorIdx % palette.size], CircleShape)
                            )
                            Text(
                                text = item.label.ifEmpty { "Untitled" },
                                fontSize = 12.sp,
                                color = Color.LightGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoDataChartPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF2C2C2E), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            Text("No views recorded yet.", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        }
    }
}

@Composable
private fun LeaderboardSection(viewModel: BusinessAnalyticsViewModel, primaryColor: Color, bg: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "All Landmarks",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(20.dp))
                .background(bg, RoundedCornerShape(20.dp))
        ) {
            viewModel.sortedLandmarks.forEachIndexed { index, landmark ->
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "${index + 1}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(
                        if (landmark.label.isEmpty()) "Untitled Landmark" else landmark.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                if (landmark.totalClicks > 0) primaryColor.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                                CircleShape
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${landmark.totalClicks}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (landmark.totalClicks > 0) primaryColor else Color.Gray
                        )
                    }
                }
                if (index < viewModel.sortedLandmarks.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(start = 60.dp), color = Color.White.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@Composable
private fun ErrorStateView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 40.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(32.dp))
        Text(message, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AnalyticsEmptyStateView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.BarChart, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(42.dp))
        Text("No landmarks found.", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Text(
            "Add landmarks to your business account to start tracking engagement.",
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

data class AnalyticsResponse(
    @SerializedName("analytics") val analytics: List<LandmarkAnalyticsData> = emptyList()
)

data class LandmarkAnalyticsData(
    @SerializedName("landmarkId") val landmarkId: String = "",
    @SerializedName("label") val label: String = "",
    @SerializedName("totalClicks") val totalClicks: Int = 0,
    @SerializedName("dailyData") val dailyData: List<DailyClickPoint> = emptyList()
) {
    val id: String get() = landmarkId
}

data class DailyClickPoint(
    @SerializedName("date") val date: String = "",
    @SerializedName("clicks") val clicks: Int = 0
) {
    val id: String get() = date
}