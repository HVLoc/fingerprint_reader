// example/android/build.gradle.kts  (PROJECT level)
allprojects {
    repositories {
        maven { url = uri("$rootDir/../../vendor-repo") }  // trỏ đến vendor-repo ở root plugin
        google()
        mavenCentral()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
