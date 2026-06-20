package com.fesu.renjana.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ── Corner Radius System ─────────────────────────────────────
// Large  = 20dp (hero cards, bottom sheets, dialogs)
// Medium = 16dp (instance cards, app cards, FAB)
// Small  = 12dp (chips, buttons, small cards, text fields)
// Extra  = 28dp (pill shapes, bottom bar items)

val RenjanaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
