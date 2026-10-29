# The EPUB reader's web page calls into the app through this bridge (mobile/books/reader/EpubPage.kt).
-keepattributes JavascriptInterface
-keepclassmembers class io.github.arkmind.seedy.mobile.books.reader.EpubBridge {
    @android.webkit.JavascriptInterface <methods>;
}
