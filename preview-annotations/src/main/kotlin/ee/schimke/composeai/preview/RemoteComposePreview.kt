package ee.schimke.composeai.preview

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class RemoteComposePreview(val formFactor: String, val params: Array<String> = [])
