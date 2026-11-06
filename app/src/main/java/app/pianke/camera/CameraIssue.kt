package app.pianke.camera

sealed interface CameraIssue {
    data object InUse : CameraIssue
    data object Disabled : CameraIssue
    data object Fatal : CameraIssue
}

/** The live readout on the LCD. */
data class Exposure(val exposureNs: Long, val aperture: Float)
