# ProGuard / R8 rules for Astra Maps.

# --- BRouter -------------------------------------------------------------------
# Stock BRouter cycling profiles carry no `---model:` tag, so RoutingContext
# resolves to StdModel (or KinematicModel). These types are reached reflectively
# in places, so keep them and their members.
#
# NOTE: no keep rules are needed for the *stock* profiles because they never
# instantiate a custom model. If the vendored `RoutingContext.setModel` patch
# (see third_party/brouter/PATCHES.md) lands, this rule set may be relaxed
# again — keep it until the release build has been verified to link.
-keep class btools.router.KinematicModel { <init>(); }
-keep class btools.router.KinematicNoCostModel { <init>(); }
-keep class btools.router.StdModel { <init>(); }
-keep class btools.router.OsmPathModel { <init>(); }

# Rd5DiffTool / Rd5DiffManager are invoked reflectively-ish via the delta
# updater and are easy for R8 to rename.
-keep class btools.mapaccess.Rd5DiffTool { public static *; }
-keep class btools.mapaccess.Rd5DiffManager { public static *; }

# BRouter reads .rd5 / .brf files reflectively by name.
-keep class btools.router.RoutingContext { *; }

# --- kotlinx.serialization ------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.astramaps.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.astramaps.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.astramaps.app.**$$serializer { *; }

# --- MapLibre -------------------------------------------------------------------
# MapLibre loads the style JSON and resolves layers/sources by name at runtime.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
