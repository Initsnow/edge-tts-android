plugins {
    alias(libs.plugins.android.application)
}

import java.net.HttpURLConnection
import java.net.URI
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

    defaultConfig {
        applicationId = "top.initsnow.edge_tts_android"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.3"

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

val downloadBeatriceLibs by tasks.registering {
    group = "build"
    description = "Downloads native Beatrice static libraries if missing."

    val targetUrls = mapOf(
        "aarch64-linux-android" to "https://huggingface.co/GokRack/beatrice-api-for-android/resolve/rc.0/arm64-v8a/libbeatrice.a",
        "x86_64-linux-android" to "https://huggingface.co/GokRack/beatrice-api-for-android/resolve/rc.0/x86_64/libbeatrice.a",
    )

    doLast {
        targetUrls.forEach { (target, urlString) ->
            val libDir = rustProjectDir.resolve("lib").resolve(target)
            val libFile = libDir.resolve("libbeatrice.a")
            if (!libFile.exists()) {
                libDir.mkdirs()
                logger.lifecycle("Downloading Beatrice static library for $target...")
                var currentUrl = urlString
                var conn: HttpURLConnection
                while (true) {
                    val url = URI(currentUrl).toURL()
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == 307
                        || code == 308
                    ) {
                        currentUrl = conn.getHeaderField("Location")
                        conn.disconnect()
                        continue
                    }
                    break
                }
                conn.inputStream.use { input ->
                    libFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()
                logger.lifecycle("Downloaded Beatrice static library: ${libFile.absolutePath}")
            }
        }
    }
}

val rustBuildJniLibs by tasks.registering {
    group = "build"
    description = "Build Rust JNI libraries for all supported Android ABIs."
    dependsOn(downloadBeatriceLibs)
    outputs.dir(rustLibOutputDir)

    doLast {
        val ndkDir = requireNotNull(resolvedNdkDir?.takeIf { it.isDirectory }) {
            "Could not find any NDK. Set ANDROID_NDK_HOME or install one under ${sdkDirFromProperties?.resolve("ndk")}."
        }
        val outputDirFile = rustLibOutputDir.get().asFile
        val minApi = android.defaultConfig.minSdk ?: 24
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        rustTargets.keys.forEach { abi ->
            providers.exec {
                workingDir(rustProjectDir)
                environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
                if (isWindows) {
                    val cmdList = mutableListOf(
                        "cargo",
                        "ndk",
                        "-t", abi,
                        "--platform", minApi.toString(),
                        "-o", outputDirFile.absolutePath,
                        "build"
                    )
                    if (rustProfileFlag.isNotBlank()) {
                        cmdList.add(rustProfileFlag)
                    }
                    commandLine(cmdList)
                } else {
                    val sanitizedOutputDir = outputDirFile.absolutePath.replace('\\', '/')
                    commandLine(
                        "bash",
                        "-lc",
                        buildString {
                            append("cargo ndk -t ")
                            append(abi)
                            append(" --platform ")
                            append(minApi)
                            append(" -o '")
                            append(sanitizedOutputDir)
                            append("' build")
                            if (rustProfileFlag.isNotBlank()) {
                                append(" ")
                                append(rustProfileFlag)
                            }
                        }
                    )
                }
            }.result.get().assertNormalExitValue()

            // Copy libc++_shared.so into jniLibs output directory for this ABI
            val abiTargetDir = outputDirFile.resolve(abi)
            abiTargetDir.mkdirs()
            val abiTripleMap = mapOf(
                "arm64-v8a" to listOf("aarch64-linux-android"),
                "armeabi-v7a" to listOf("armv7-linux-androideabi", "arm-linux-androideabi"),
                "x86_64" to listOf("x86_64-linux-android"),
            )
            var libcxxFile: File? = null
            val legacyPath = ndkDir.resolve("sources/cxx-stl/llvm-libc++/libs/$abi/libc++_shared.so")
            if (legacyPath.isFile) {
                libcxxFile = legacyPath
            } else {
                val prebuiltDir = ndkDir.resolve("toolchains/llvm/prebuilt")
                if (prebuiltDir.isDirectory) {
                    val hostDirs = prebuiltDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                    val triples = abiTripleMap[abi] ?: listOf(abi)
                    for (host in hostDirs) {
                        val sysrootUsrLib = host.resolve("sysroot/usr/lib")
                        for (triple in triples) {
                            val candidate = sysrootUsrLib.resolve(triple).resolve("libc++_shared.so")
                            if (candidate.isFile) {
                                libcxxFile = candidate
                                break
                            }
                        }
                        if (libcxxFile != null) break
                    }
                }
            }
            if (libcxxFile != null && libcxxFile.isFile) {
                val destFile = abiTargetDir.resolve("libc++_shared.so")
                libcxxFile.copyTo(destFile, overwrite = true)
                logger.lifecycle("Copied libc++_shared.so for $abi to ${destFile.absolutePath}")
            }
        }
    }
}

tasks.matching {
    it.name.contains("NativeLibs", ignoreCase = true) ||
    it.name.contains("JniLibFolders", ignoreCase = true)
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
