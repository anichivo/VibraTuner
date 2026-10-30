package com.personal.vibratuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.personal.vibratuner.theme.VibraTunerTheme
import com.personal.vibratuner.ui.navigation.MainScreenLayout

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Включение Edge-to-Edge перед setContent
        enableEdgeToEdge()

        // Отключение принудительного контрастного scrim на системной панели навигации (API 29+)
        com.personal.vibratuner.core.platform.PlatformCompat.disableNavigationBarContrastEnforced(window)

        setContent {
            VibraTunerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreenLayout()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Автодочитывание новых файлов из сохранённой папки импорта: старт
        // приложения и КАЖДЫЙ возврат в foreground. Сканирование уходит в
        // фоновый IO-скоуп приложения и не блокирует UI.
        (application as VibraTunerApplication).folderImport.requestRescan("app_foreground")
    }
}
