# R8 is off for release builds for now (see app/build.gradle.kts).
# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.cockpitmanager.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
