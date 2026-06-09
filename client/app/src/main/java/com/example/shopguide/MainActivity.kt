package com.example.shopguide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 紫色主调，参考演示稿配色：主色偏冷紫，AI 气泡用浅紫，背景用近白浅紫
private val ShopGuideColors = lightColorScheme(
    primary = Color(0xFF7B61FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE7FF),
    onPrimaryContainer = Color(0xFF2D1F6F),
    secondary = Color(0xFF8E80E8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E0FF),
    onSecondaryContainer = Color(0xFF2D1F6F),
    tertiary = Color(0xFFB8A7FF),
    tertiaryContainer = Color(0xFFEDE7FF),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1B2E),
    surfaceVariant = Color(0xFFF3F0FA),
    onSurfaceVariant = Color(0xFF4B4757),
    background = Color(0xFFF7F5FC),
    onBackground = Color(0xFF1F1B2E),
    outline = Color(0xFFE2DCF4),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BASE_URL 从 SharedPreferences 读，需在第一次访问 Config.BASE_URL 之前完成
        Config.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = ShopGuideColors) {
                ChatScreen()
            }
        }
    }
}
