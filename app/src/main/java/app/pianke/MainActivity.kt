package app.pianke

import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pianke.camera.CameraController
import app.pianke.camera.PreviewRenderer
import app.pianke.ui.CameraScreen
import app.pianke.ui.MenuScreen
import app.pianke.ui.PermissionGate
import app.pianke.ui.ShooterViewModel

/**
 * Owns the Activity-scoped [CameraController] and [PreviewRenderer] (H2) and the orientation listener, and routes
 * between the broken-catalog notice, the permission gate, the shooting screen and the MENU.
 */
class MainActivity : ComponentActivity() {
    private val camera = CameraController(this, this)
    private val renderer = PreviewRenderer()

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
        enableEdgeToEdge()
        val app = application as PiankeApp
        setContent {
            if (app.catalog == null) {
                BrokenCatalogNotice()
            } else {
                val viewModel = viewModel<ShooterViewModel>(factory = ShooterViewModel.factory(app))
                val state by viewModel.state.collectAsState()
                var menuOpen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(viewModel) { viewModel.drive(camera, renderer) }
                PermissionGate(state.permission, viewModel::onPermission) {
                    if (menuOpen) {
                        MenuScreen(state.settings, viewModel::updateSettings) { menuOpen = false }
                    } else {
                        CameraScreen(state, viewModel, renderer, { rotation }) { menuOpen = true }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
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
    BasicText(stringResource(R.string.catalog_broken))
}
