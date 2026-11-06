package app.pianke.film

/** A non-OK `fe_err_t` from the C engine; thrown only by the JNI glue. */
class EngineException(val code: Int) : RuntimeException("film_engine error $code")

/** The film catalog cannot be used: films.bin failed to open, or films.json disagrees with it. */
class FilmCatalogBroken(cause: Throwable) : Exception(cause)
