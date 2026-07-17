# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep WebView JS bridge (PrintBridge exposto como window.AndroidPrint)
-keepclassmembers class com.jarvis.kiosk.PrintBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * extends android.webkit.WebView {
    public *;
}

# Keep JSON
-keep class org.json.** { *; }

# Keep GSON
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Sunmi Printer SDK
-keep class com.sunmi.peripheral.printer.** { *; }
-keep class woyou.aidlservice.jiuiv5.** { *; }

# Keep USB Printer Manager (ESC/POS)
-keep class com.jarvis.kiosk.UsbPrinterManager { *; }
-keep class com.jarvis.kiosk.PrintRouter { *; }