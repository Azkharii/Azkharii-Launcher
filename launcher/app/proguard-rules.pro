# No blanket keep rules: allow R8 to remove unreachable application code.
# Manifest entry points are kept by Android Gradle Plugin/R8.
# These two custom views are inflated by class name from activity_main.xml.
-keep public class com.cinematic.tvlauncher.carousel.AppCarouselView {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context);
}
-keep public class com.cinematic.tvlauncher.carousel.ContinueWatchingWheelView {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context);
}
# The layout invokes this Activity method through android:onClick.
-keepclassmembers class com.cinematic.tvlauncher.MainActivity {
    public void closeInputOverlayFromView(android.view.View);
}
