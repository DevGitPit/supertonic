package com.brahmadeo.supertonic.tts.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material 3 Expressive Shapes (often more rounded)
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp), // More rounded than standard M3
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)