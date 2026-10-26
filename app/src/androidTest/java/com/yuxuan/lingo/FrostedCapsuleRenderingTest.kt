package com.yuxuan.lingo

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.yuxuan.lingo.ui.FrostedCapsuleBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel checks use the production backdrop renderer, with no text or borders to obscure it. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class FrostedCapsuleRenderingTest {
    @get:Rule val compose = createComposeRule()

    private fun showBackdrop(pattern: DrawScope.() -> Unit) {
        compose.setContent {
            val source = rememberGraphicsLayer()
            var contentOrigin by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.size(320.dp, 200.dp).testTag("frostedFixture")) {
                Canvas(Modifier.matchParentSize()
                    .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                    .drawWithContent {
                        source.record { this@drawWithContent.drawContent() }
                        drawLayer(source)
                    }, onDraw = pattern)
                Box(Modifier.offset(30.dp, 68.dp).size(260.dp, 64.dp).clip(CircleShape)) {
                    FrostedCapsuleBackdrop(source, contentOrigin, Color.White)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun capsuleBlursRealStripesWhileTheSurroundingContentStaysSharp() {
        showBackdrop {
            val stripeWidth = 8.dp.toPx()
            repeat(40) { stripe ->
                drawRect(if (stripe % 2 == 0) Color.Black else Color.White,
                    topLeft = Offset(stripe * stripeWidth, 0f),
                    size = Size(stripeWidth, size.height))
            }
        }
        val pixels = compose.onNodeWithTag("frostedFixture").captureToImage().toPixelMap()
        val outside = (8..29).map { stripe -> pixels.grayAtDp(stripe * 8f + 4f, 20f) }
        val inside = (8..29).map { stripe -> pixels.grayAtDp(stripe * 8f + 4f, 100f) }

        assertTrue("Source stripes outside the pill must keep black pixels", outside.min() < .04f)
        assertTrue("Source stripes outside the pill must keep white pixels", outside.max() > .96f)
        assertTrue("Gaussian blur must remove the high-frequency stripe contrast inside the pill",
            inside.max() - inside.min() < .12f)
        assertEquals("A white 25% tint over blurred black/white should stay translucent gray",
            .625f, inside.average().toFloat(), .07f)
    }

    @Test fun capsuleRetainsUnderlyingColorsAtQuarterOpacityInsteadOfPaintingAnOpaquePanel() {
        showBackdrop {
            drawRect(Color.Black)
            drawRect(Color.White, topLeft = Offset(size.width / 2f, 0f),
                size = Size(size.width / 2f, size.height))
        }
        val pixels = compose.onNodeWithTag("frostedFixture").captureToImage().toPixelMap()
        // Both sample patches are on the capsule's center line, far from its curved
        // edge and the blurred seam, so antialiasing and blur kernels do not affect alpha.
        val blackUnderPill = pixels.grayAtDp(88f, 100f)
        val whiteUnderPill = pixels.grayAtDp(232f, 100f)
        assertEquals("25% white over black must retain three quarters of the original darkness",
            .25f, blackUnderPill, .055f)
        assertEquals("25% white over white remains white", 1f, whiteUnderPill, .035f)
        assertTrue("The backdrop must remain visible through the pill",
            whiteUnderPill - blackUnderPill > .65f)
        assertEquals("Outside the capsule no tint should be painted", 0f,
            pixels.grayAtDp(88f, 20f), .02f)
    }

    /** Average a tiny patch in grayscale to absorb pixel rounding without hiding contrast. */
    private fun PixelMap.grayAtDp(xDp: Float, yDp: Float): Float {
        val x = (xDp * width / 320f).toInt()
        val y = (yDp * height / 200f).toInt()
        var sum = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val color = this[x + dx, y + dy]
            sum += (color.red + color.green + color.blue) / 3f
        }
        return sum / 9f
    }
}
