package app.pianke.settings

enum class Framing { FOUR_THREE, SQUARE }

enum class FlashMode { AUTO, ON, OFF }

enum class GrainLevel(val scale: Float) { OFF(0f), LOW(0.5f), NORMAL(1f), HIGH(1.5f) }

enum class Lens { BACK, FRONT }

/** Contract K6: DataStore keys are these property names, enums are stored by `name`; keys may be added, never renamed. */
data class Settings(
    val filmId: String? = null,            // null: the first roll; an id, not an index, so reordering films.toml cannot shift it
    val framing: Framing = Framing.FOUR_THREE,
    val dateStamp: Boolean = true,
    val grid: Boolean = false,
    val grain: GrainLevel = GrainLevel.NORMAL,
    val halation: Boolean = true,
    val shutterSound: Boolean = true,
    val vibration: Boolean = true,
    val flash: FlashMode = FlashMode.AUTO,
    val lens: Lens = Lens.BACK,
    val rollBack: Int = 1,                 // number of the next frame, 1..36
    val rollFront: Int = 1,
)
