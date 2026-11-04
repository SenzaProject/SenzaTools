package android.content
open class Context { val contentResolver: android.content.ContentResolver = android.content.ContentResolver(); val cacheDir: java.io.File = java.io.File("/tmp") }
class ContentResolver {
    fun query(u: android.net.Uri, p: Array<String>, a: String?, b: Array<String>?, c: String?): android.database.Cursor? = null
    fun openInputStream(u: android.net.Uri): java.io.InputStream? = null
}
