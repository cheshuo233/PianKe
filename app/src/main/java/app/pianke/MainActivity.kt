package app.pianke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText

/** Placeholder: shows the number of films, or the broken-catalog notice. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = (application as PiankeApp).catalog
        val text = catalog?.films?.size?.toString() ?: getString(R.string.catalog_broken)
        setContent { BasicText(text) }
    }
}
