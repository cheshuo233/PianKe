package app.pianke.ui

import androidx.compose.runtime.Composable

/**
 * Spec 7: the only place that turns the CAMERA permission result into a [Permission]. Shows [content] when GRANTED,
 * else the "camera permission needed" notice with a button: request, or (DENIED_FOREVER) open the app settings page.
 * Reports every change through [onChange].
 */
@Composable
fun PermissionGate(permission: Permission, onChange: (Permission) -> Unit, content: @Composable () -> Unit) {
    TODO()
}
