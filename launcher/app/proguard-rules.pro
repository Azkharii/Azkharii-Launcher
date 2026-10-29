# No blanket keep rules: allow R8 to remove unreachable application code.
# AndroidManifest entry points and AndroidX dependency consumer rules are handled by R8.
# This method is referenced by the XML android:onClick attribute and must retain its exact name.
-keepclassmembers class com.cinematic.tvlauncher.MainActivity {
    public void closeInputOverlayFromView(android.view.View);
}
