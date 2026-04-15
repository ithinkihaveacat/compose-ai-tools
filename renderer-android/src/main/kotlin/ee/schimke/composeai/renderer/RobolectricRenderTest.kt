package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Parameterized Robolectric test — one instance per preview entry in the manifest.
 *
 * Rendering strategy: we install a paused [BroadcastFrameClock] via
 * [WindowRecomposerPolicy] before calling [ComponentActivity.setContent], so
 * Compose's `withFrameNanos` never resumes and infinite animations (e.g.
 * `CircularProgressIndicator`, `rememberInfiniteTransition`) stay parked on
 * their initial frame. Without this, `ShadowLooper.idleMainLooper` cascades
 * re-posted Choreographer callbacks and a single `view.draw()` takes minutes.
 *
 * Robolectric is configured via robolectric.properties and system properties
 * set by the Gradle plugin. android.jar must be on the test classpath for the
 * runner to load (its class hierarchy references android.app.Application).
 *
 * TODO: Replace ParameterizedRobolectricTestRunner with SandboxBuilder + FixedConfiguration
 *  from org.robolectric:simulator. This would:
 *  - Remove the android.jar classpath requirement (sandbox provides Android classes)
 *  - Give explicit control over sandbox lifecycle and classpath filtering
 *  - Allow excluding android.jar from the render classpath (Robolectric provides its own)
 *  The tradeoff is more code (manual sandbox setup vs annotation-driven) and a dependency
 *  on the simulator module's API which is less documented than the test runner.
 *
 * System properties:
 *   composeai.render.manifest  — path to previews.json
 *   composeai.render.outputDir — directory for rendered PNGs
 */
/**
 * Loads the previews manifest and returns the subset assigned to `shardIndex`
 * out of `shardCount` shards. Generated shard subclasses delegate their
 * `@Parameters` method here (see the plugin's `generateShardTests` task).
 *
 * With `shardCount = 1`, returns every preview — that's the default single-class path.
 */
object PreviewManifestLoader {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun loadShard(shardIndex: Int, shardCount: Int): List<Array<Any>> {
        require(shardCount >= 1) { "shardCount must be >= 1" }
        require(shardIndex in 0 until shardCount) { "shardIndex must be in [0, $shardCount)" }
        val manifestPath = System.getProperty("composeai.render.manifest")
            ?: return emptyList()
        val file = File(manifestPath)
        if (!file.exists()) return emptyList()

        val manifest = json.decodeFromString<RenderManifest>(file.readText())
        return manifest.previews
            .withIndex()
            .filter { (i, _) -> i % shardCount == shardIndex }
            .map { (_, p) -> arrayOf<Any>(p) }
    }
}

/**
 * Rendering logic — driven by a single [RenderPreviewEntry]. Subclasses supply
 * the `@RunWith` + `@Parameters` wiring. [RobolectricRenderTest] is the default
 * single-class entry; the plugin generates `RobolectricRenderTest_ShardN` subclasses
 * when `composeAiPreview.shards > 1`.
 */
// SDK 34 is the highest API where Robolectric 4.16.1 still shadows
// `ShadowNativeImageReaderSurfaceImage.nativeCreatePlanes`. Above that, the
// HardwareRenderingScreenshot path returns an Image with `planes[0] == null`.
@Config(sdk = [35], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RobolectricRenderTestBase(private val preview: RenderPreviewEntry) {

    companion object {
        private const val DEFAULT_WIDTH = 400
        private const val DEFAULT_HEIGHT = 800
        private const val DENSITY = 2.0f
    }

    private val widthDp: Int = preview.params.widthDp?.takeIf { it > 0 } ?: DEFAULT_WIDTH
    private val heightDp: Int = preview.params.heightDp?.takeIf { it > 0 } ?: DEFAULT_HEIGHT
    private val widthPx: Int = (widthDp * DENSITY).toInt()
    private val heightPx: Int = (heightDp * DENSITY).toInt()
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun renderPreview() {
        composeTestRule.setContent {
            println("Entering setContent composition")
            CompositionLocalProvider(LocalInspectionMode provides true) {
                val clazz = Class.forName(preview.className)
                val composableMethod = clazz.getDeclaredComposableMethod(preview.functionName)
                val bgColor = when {
                    preview.params.backgroundColor != 0L -> Color(preview.params.backgroundColor.toInt())
                    preview.params.showBackground -> Color.White
                    else -> Color.Black
                }
                val body: @Composable () -> Unit = {
                    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                        InvokeComposable(composableMethod, null)
                    }
                }
                val wrapperFqn = preview.params.wrapperClassName
                if (wrapperFqn != null) {
                    InvokeWrappedComposable(wrapperFqn, body)
                } else {
                    body()
                }
            }
        }

        composeTestRule.waitForIdle()

        val outputDir = File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
        val outputFile = File(outputDir, "${preview.id}.png")
        outputFile.parentFile?.mkdirs()

        val tempFile = File.createTempFile("roborazzi", ".png")
        val rootNode = composeTestRule.onAllNodes(androidx.compose.ui.test.isRoot())[0]
        rootNode.captureRoboImage(tempFile.absolutePath)
        val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
        tempFile.delete()

        val finalBitmap = if (isRoundDevice(preview.params.device) && preview.params.showSystemUi) {
            val clipped = applyCircularClip(bitmap)
            bitmap.recycle()
            clipped
        } else {
            bitmap
        }

        FileOutputStream(outputFile).use { fos ->
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        finalBitmap.recycle()
    }

    /**
     * Captures the view tree using Robolectric's hardware screenshot path directly,
     * bypassing [android.view.PixelCopy] so we skip its `canHaveDisplayList()` gate —
     * that check reads `mAttachInfo.mThreadedRenderer`, which Robolectric doesn't
     * populate even under `GraphicsMode.NATIVE`. When the gate fails, `PixelCopy`
     * silently falls back to `view.draw(Canvas)`, which under NATIVE graphics renders
     * Compose's RenderNode-recorded fills as 1-pixel outlines (pure red at the edge,
     * white interior — verified empirically in this project's history).
     *
     * `HardwareRenderingScreenshot.takeScreenshot` internally fetches the
     * `ShadowViewRootImpl`'s `ThreadedRenderer`, points it at an `ImageReader` surface,
     * calls `updateDisplayListIfDirty()` + `syncAndDraw()`, and copies the `Image`'s
     * pixel buffer into the destination bitmap. That is the same path
     * `androidx.compose.ui.test.captureToImage()` and Roborazzi use, minus the
     * `test_config.properties` plumbing overhead.
     */
    private fun captureHardware(view: View, width: Int, height: Int): Bitmap {
        val tempFile = File.createTempFile("roborazzi", ".png")
        view.captureRoboImage(tempFile)
        val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
        tempFile.delete()
        return bitmap
    }

    private fun findComposeView(view: View): View? {
        println("Visiting view: ${view.javaClass.name} (simpleName: ${view.javaClass.simpleName})")
        if (view.javaClass.simpleName == "ComposeView") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun configureDisplay(activity: ComponentActivity) {
        @Suppress("DEPRECATION")
        val shadowDisplay = Shadows.shadowOf(activity.windowManager.defaultDisplay)
        shadowDisplay.setWidth(widthPx)
        shadowDisplay.setHeight(heightPx)
        val config = activity.resources.configuration
        config.screenWidthDp = widthDp
        config.screenHeightDp = heightDp
        config.densityDpi = (DENSITY * 160).toInt()
        config.fontScale = preview.params.fontScale
        if (preview.params.uiMode != 0) config.uiMode = preview.params.uiMode
        preview.params.locale?.let { config.setLocale(java.util.Locale.forLanguageTag(it)) }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }
}

@Composable
private fun InvokeComposable(
    composableMethod: ComposableMethod,
    instance: Any?,
) {
    composableMethod.invoke(currentComposer, instance)
}

/**
 * Reflectively instantiates the `PreviewWrapperProvider` identified by [wrapperFqn]
 * and invokes its `Wrap(content)` composable around [body].
 *
 * `PreviewWrapperProvider.Wrap(content: @Composable () -> Unit)` compiles to
 * `Wrap(Function2, Composer, int)` at the bytecode level — [getDeclaredComposableMethod]
 * handles the synthetic Composer/changed args, so we look the method up by the
 * content parameter's JVM type.
 */
@Composable
private fun InvokeWrappedComposable(
    wrapperFqn: String,
    body: @Composable () -> Unit,
) {
    val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
    resolved.first.invoke(currentComposer, resolved.second, body)
}

internal fun resolveWrapper(wrapperFqn: String): Pair<ComposableMethod, Any> {
    val cls = Class.forName(wrapperFqn)
    val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
    // Wrap(Function2, Composer, int). getDeclaredComposableMethod handles the
    // synthetic Composer/changed tail, so we look up by the content param's JVM type.
    val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
    return method to instance
}

/**
 * Default single-shard entry. Runs every preview in the manifest in one JVM,
 * reusing the sandbox across all parameter values. Generated shard subclasses
 * (see the plugin's `generateShardTests` task) replace this class when
 * `composeAiPreview.shards > 1`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RobolectricRenderTest(preview: RenderPreviewEntry) : RobolectricRenderTestBase(preview) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> = PreviewManifestLoader.loadShard(0, 1)
    }
}
