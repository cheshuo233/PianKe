package app.pianke.settings

object Roll {
    const val FRAMES = 36

    /** The number of the frame after [frame]: 36 wraps to 1. A result of 1 means the roll was rewound. */
    fun next(frame: Int): Int = if (frame >= FRAMES) 1 else frame + 1
}

/** The roll counter of the current lens: the one place the lens picks rollBack or rollFront. */
val Settings.frame: Int
    get() = if (lens == Lens.BACK) rollBack else rollFront

fun Settings.withFrame(frame: Int): Settings =
    if (lens == Lens.BACK) copy(rollBack = frame) else copy(rollFront = frame)
