package com.livevip.app.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.livevip.app.engine.LiveStreamingEngine

/**
 * The preview IS the editing surface: one finger drags, pinch zooms,
 * double tap resets. It renders the real decoded frames through the compositor.
 */
@Composable
fun PreviewSurface(engine: LiveStreamingEngine, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                engine.updateComposition { state ->
                    state.copy(
                        scale = state.scale * zoom,
                        translationX = state.translationX + pan.x / size.width * 2f,
                        translationY = state.translationY - pan.y / size.height * 2f
                    )
                }
            }
        }.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { engine.updateComposition { it.reset() } })
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                            engine.attachPreviewSurface(h.surface, w, ht)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.detachPreviewSurface()
                        }
                    })
                }
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { engine.detachPreviewSurface() }
    }
}
