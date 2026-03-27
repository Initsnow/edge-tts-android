plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties

val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

val rustLibOutputDir = layout.buildDirectory.dir("rustJniLibs")
val rustProjectDir = rootProject.file("rust")
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}
val sdkDirFromProperties = localProperties.getProperty("sdk.dir")?.let(::File)
fun configValue(propertyName: String, envName: String): String? {
    return providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(envName)
}

val releaseKeystorePath = configValue(
    "android.release.keystore.path",
    "ANDROID_RELEASE_KEYSTORE_PATH"
)
val releaseStorePassword = configValue(
    "android.release.store.password",
    "ANDROID_RELEASE_STORE_PASSWORD"
)
val releaseKeyAlias = configValue(
    "android.release.key.alias",
    "ANDROID_RELEASE_KEY_ALIAS"
)
val releaseKeyPassword = configValue(
    "android.release.key.password",
    "ANDROID_RELEASE_KEY_PASSWORD"
)
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank()
        && !releaseStorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()
        && !releaseKeyPassword.isNullOrBlank()
val resolvedNdkDir = run {
    val envNdk = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
    if (envNdk?.isDirectory == true) {
        envNdk
    } else {
        val ndkRoot = sdkDirFromProperties?.resolve("ndk")
        ndkRoot
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
    }
}
val rustProfileFlag = if (
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("Release", ignoreCase = true)
    }
) {
    "--release"
} else {
    ""
}

android {
    namespace = "top.initsnow.edge_tts_android"
    compileSdk = 36 // 保持你要求的 36

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "top.initsnow.edge_tts_android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rustLibOutputDir)
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include(*rustTargets.keys.toTypedArray())
            isUniversalApk = false
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

val rustBuildJniLibs by tasks.registering {
    group = "build"
    description = "Build Rust JNI libraries for all supported Android ABIs."
    outputs.dir(rustLibOutputDir)

    doLast {
        val ndkDir = requireNotNull(resolvedNdkDir?.takeIf { it.isDirectory }) {
            "Could not find any NDK. Set ANDROID_NDK_HOME or install one under ${sdkDirFromProperties?.resolve("ndk")}."
        }
        val outputDir = rustLibOutputDir.get().asFile.absolutePath
        val minApi = android.defaultConfig.minSdk ?: 24
        rustTargets.keys.forEach { abi ->
            providers.exec {
                workingDir(rustProjectDir)
                environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
                commandLine(
                    "bash",
                    "-lc",
                    buildString {
                        append("cargo ndk -t ")
                        append(abi)
                        append(" --platform ") // 修正：从 -p 改为 --platform，避免被误认为 cargo 的 -p (package)
                        append(minApi)
                        append(" -o ")
                        append(outputDir)
                        append(" build")
                        if (rustProfileFlag.isNotBlank()) {
                            append(" ")
                            append(rustProfileFlag)
                        }
                    }
                )
            }.result.get().assertNormalExitValue()
        }
    }
}

tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(rustBuildJniLibs)
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.activity)
    implementation(libs.material)
    implementation(libs.preference)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
