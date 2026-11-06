package app.pianke.film

data class Film(val index: Int, val id: String, val name: String, val iso: Int, val process: String) {
    /** The label file naming rule lives only here. */
    val labelAsset: String get() = "labels/$id.png"
}
