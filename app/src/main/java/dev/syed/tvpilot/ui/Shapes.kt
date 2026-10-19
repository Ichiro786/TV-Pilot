package dev.syed.tvpilot.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/** A shape partway between two Material shapes, for buttons that morph when pressed or busy. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object PilotShapes {
    val micMorph: Morph by lazy { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst) }

    /** Shapes handed out to app tiles in turn, so the row looks playful. */
    val appTiles: List<RoundedPolygon> by lazy {
        listOf(
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Sunny,
            MaterialShapes.Pentagon,
            MaterialShapes.Flower,
            MaterialShapes.Cookie6Sided,
            MaterialShapes.Arch,
            MaterialShapes.Gem,
        )
    }
}
