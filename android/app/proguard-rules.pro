# The EPUB reader's web page calls into the app through this bridge (mobile/books/reader/EpubBridge in EpubPage.kt):
# WebView needs the method's name and its @JavascriptInterface annotation at run time.
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class io.github.arkmind.seedy.mobile.books.reader.EpubBridge {
    @android.webkit.JavascriptInterface <methods>;
}
