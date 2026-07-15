# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep WebView JS bridge
-keepclassmembers class * extends android.webkit.WebView {
    public *;
}

# Keep JSON
-keep class org.json.** { *; }