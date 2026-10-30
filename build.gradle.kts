plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
// Keep generated Gradle output in the workspace cleanup area from the start.
// The APK is never copied back into the project tree.
subprojects {
    layout.buildDirectory.set(file("E:/ddd/delete/dev-1-new-build"))
}
