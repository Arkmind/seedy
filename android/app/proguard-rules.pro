# The EPUB reader's web page calls into the app through this bridge (mobile/books/reader/EpubBridge in EpubPage.kt):
# WebView needs the method's name and its @JavascriptInterface annotation at run time.
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class io.github.arkmind.seedy.mobile.books.reader.EpubBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager (request notifications) builds its Room database by reflection at startup. Room 2.6's own rule keeps
# the class but, in R8 full mode, not its no-arg constructor: without this the release app crashes on launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
