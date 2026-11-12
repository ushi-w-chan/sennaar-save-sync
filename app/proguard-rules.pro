# Shizuku starts this service by class name and invokes its constructor reflectively.
-keep class com.ushi.sennaar.SaveService {
    public <init>();
}
