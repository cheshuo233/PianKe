package app.pianke.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.pianke.R

/**
 * Spec 7: the only place that turns the CAMERA permission result into a [Permission]. Shows [content] when GRANTED,
 * else the "camera permission needed" notice with a button: request, or (DENIED_FOREVER) open the app settings page.
 * Reports every change through [onChange]. The system's answer is also re-read on every resume, so coming back from
 * the settings page leads straight into the viewfinder.
 */
@Composable
fun PermissionGate(permission: Permission, onChange: (Permission) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun granted() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun rationale() = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

    // The rationale flag before the request: true only after an earlier denial. Dismissing the very first dialog
    // leaves it false before and after, and must not count as "don't ask again".
    var deniedBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onChange(
            when {
                ok -> Permission.GRANTED
                deniedBefore && !rationale() -> Permission.DENIED_FOREVER
                else -> Permission.DENIED
            },
        )
    }
    fun request() {
        deniedBefore = rationale()
        launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!granted()) request() }
    LifecycleResumeEffect(Unit) {
        if (granted()) onChange(Permission.GRANTED)
        onPauseOrDispose {}
    }

    if (permission == Permission.GRANTED) {
        content()
    } else {
        Box(Modifier.bodyBackground().windowInsetsPadding(WindowInsets.systemBars), contentAlignment = Alignment.Center) {
            if (permission != Permission.UNKNOWN) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    BasicText(
                        stringResource(R.string.permission_needed),
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink),
                    )
                    val forever = permission == Permission.DENIED_FOREVER
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier.bezelButton(shape).clip(shape).clickable(role = Role.Button) {
                            if (forever) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            } else {
                                request()
                            }
                        }.padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        BasicText(
                            stringResource(if (forever) R.string.permission_open_settings else R.string.permission_grant),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink),
                        )
                    }
                }
            }
        }
    }
}
