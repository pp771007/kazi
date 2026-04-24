package tw.pp.kazi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tw.pp.kazi.ui.KaziApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KaziApplication).container
        setContent {
            KaziApp(container)
        }
    }
}
