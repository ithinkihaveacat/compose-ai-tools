package ee.schimke.composeai.preview

import androidx.compose.ui.tooling.preview.Preview

@Preview(device = "id:wearos_large_round") // Default to large round for widgets
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class WearWidgetPreview(
  val frame: String = "small",
  val title: String = "Wear Widget",
  val icon: String = "🤖",
)
