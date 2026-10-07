# Keep usb-serial-for-android driver classes (loaded by reflection)
-keep class com.hoho.android.usbserial.** { *; }

# Keep Kotlin coroutine internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
