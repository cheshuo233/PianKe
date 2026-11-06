package app.pianke

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pianke.camera.CameraController
import app.pianke.camera.PreviewRenderer
import app.pianke.film.FilmCatalog
import app.pianke.ui.CameraScreen
import app.pianke.ui.Ink
import app.pianke.ui.LocalEdgeMargin
import app.pianke.ui.MenuScreen
import app.pianke.ui.PermissionGate
import app.pianke.ui.REFERENCE_HEIGHT_DP
import app.pianke.ui.REFERENCE_WIDTH_DP
import app.pianke.ui.ShooterViewModel
import app.pianke.ui.bodyBackground

/**
 * Owns the Activity-scoped [CameraController], [PreviewRenderer] and GLSurfaceView (H2, H6) and the orientation
 * listener, scales the whole UI to fit the 390 x 866 dp reference, and routes between the broken-catalog notice, the
 * permission gate and the shooting screen with the MENU as an overlay (the GL view is never destroyed).
 */
class MainActivity : ComponentActivity() {
    private val camera = CameraController(this, this)
    private val renderer = PreviewRenderer()
    private val preview by lazy { GLSurfaceView(this) }

    /** Latest device orientation as a Surface.ROTATION_*; read at shutter time. */
    private var rotation = Surface.ROTATION_0

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN) rotation = surfaceRotation(degrees)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        renderer.attach(preview)
        val app = application as PiankeApp
        setContent {
            // The one place that defines the scale: the 390 x 866 dp board fits the area left by the system bars.
            BoxWithConstraints(Modifier.bodyBackground()) {
                val density = LocalDensity.current
                val bars = WindowInsets.systemBars
                val availableWidth = constraints.maxWidth - bars.getLeft(density, LayoutDirection.Ltr) - bars.getRight(density, LayoutDirection.Ltr)
                val availableHeight = constraints.maxHeight - bars.getTop(density) - bars.getBottom(density)
                val scale = minOf(
                    availableWidth / REFERENCE_WIDTH_DP,
                    availableHeight / REFERENCE_HEIGHT_DP,
                )
                // Height-limited screens are wider than the board: the film row reaches the physical right edge.
                val edge = ((constraints.maxWidth / scale - REFERENCE_WIDTH_DP) / 2).coerceAtLeast(0f).dp
                CompositionLocalProvider(
                    LocalDensity provides Density(scale, density.fontScale),
                    LocalEdgeMargin provides edge,
                ) {
                    // Height-limited screens leave side margins; the board stays 390 wide and centred.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.width(REFERENCE_WIDTH_DP.dp).fillMaxHeight()) {
                            val catalog = app.catalog
                            if (catalog == null) BrokenCatalogNotice() else Shooter(app, catalog)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Shooter(app: PiankeApp, catalog: FilmCatalog) {
        val viewModel = viewModel {
            ShooterViewModel(app, catalog, app.settingsStore, app.feedback, app.pendingStore, app.developer!!, app.queue!!)
        }
        val state by viewModel.state.collectAsState()
        var menuOpen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(camera) { viewModel.drive(camera, renderer) }
        PermissionGate(state.permission, viewModel::onPermission) {
            Box {
                CameraScreen(state, viewModel, preview, { rotation }) { menuOpen = true }
                if (menuOpen) MenuScreen(state.settings, app.feedback.shutterSoundForced, viewModel::updateSettings) { menuOpen = false }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()
        preview.onResume()
    }

    override fun onPause() {
        preview.onPause()
        super.onPause()
    }

    override fun onStop() {
        orientationListener.disable()
        super.onStop()
    }
}

/** Hand-off H5, the only place the OrientationEventListener degrees become a Surface.ROTATION_*. */
private fun surfaceRotation(degrees: Int): Int = when (degrees) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

@Composable
private fun BrokenCatalogNotice() {
    Box(Modifier.bodyBackground(), contentAlignment = Alignment.Center) {
        BasicText(stringResource(R.string.catalog_broken), style = TextStyle(fontSize = 18.sp, color = Ink))
    }
}
