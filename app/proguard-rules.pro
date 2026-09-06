# Nothing project-specific to keep yet.
# HomeActivity is referenced from AndroidManifest.xml, so the manifest-derived
# keep rules that R8/AGP add automatically are sufficient.

# Shizuku only ever reaches this class via reflection in a separate
# process — invisible to R8's reachability analysis. The @Keep annotation
# on its Context constructor relies on androidx.annotation's consumer
# proguard rules; this is a defensive, explicit backstop for the whole
# class.
-keep class com.retro.launcher.lock.ShizukuLockService { *; }
