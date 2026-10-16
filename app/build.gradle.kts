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
val resolvedSdkDir = localProperties.getProperty("sdk.dir")?.let(::File)
    ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(::File)
    ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
val resolvedNdkDir = run {
    val envNdk = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
    if (envNdk?.isDirectory == true) {
        envNdk
    } else {
        val ndkRoot = resolvedSdkDir?.resolve("ndk")
        ndkRoot
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
    }
}

fun findExecutableOnPath(name: String): File? {
    val pathEnv = System.getenv("PATH") ?: System.getenv("Path") ?: return null
    val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
    val extensions = if (isWin) listOf(".exe", ".cmd", ".bat", "") else listOf("")
    for (dirStr in pathEnv.split(File.pathSeparator)) {
        if (dirStr.isBlank()) continue
        val dir = File(dirStr)
        if (!dir.isDirectory) continue
        for (ext in extensions) {
            val candidate = dir.resolve("$name$ext")
            if (candidate.isFile && (isWin || candidate.canExecute())) {
                return candidate
            }
        }
    }
    return null
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
        versionCode = 6
        versionName = "0.1.6"

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
            "Could not find any NDK. Set ANDROID_NDK_HOME or install one under ${resolvedSdkDir?.resolve("ndk")}."
        }
        val outputDirFile = rustLibOutputDir.get().asFile
        val minApi = android.defaultConfig.minSdk ?: 24
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows

        // Android CMake toolchain wrapper to ensure ANDROID_ABI and ANDROID_PLATFORM are correctly passed
        val ndkToolchainFile = ndkDir.resolve("build/cmake/android.toolchain.cmake")
        val toolchainWrapperFile = rustProjectDir.resolve("target/android_toolchain_wrapper.cmake")
        toolchainWrapperFile.parentFile.mkdirs()
        toolchainWrapperFile.writeText(
            """
            # Generated by Gradle task rustBuildJniLibs
            if(NOT DEFINED ANDROID_ABI AND DEFINED ENV{ANDROID_ABI})
                set(ANDROID_ABI "${'$'}ENV{ANDROID_ABI}" CACHE STRING "")
            endif()
            if(NOT DEFINED ANDROID_PLATFORM AND DEFINED ENV{ANDROID_PLATFORM})
                set(ANDROID_PLATFORM "${'$'}ENV{ANDROID_PLATFORM}" CACHE STRING "")
            endif()
            if(NOT DEFINED CMAKE_POLICY_VERSION_MINIMUM)
                set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "")
            endif()
            if(EXISTS "${ndkToolchainFile.absolutePath.replace('\\', '/')}")
                include("${ndkToolchainFile.absolutePath.replace('\\', '/')}")
            elseif(DEFINED ENV{ANDROID_NDK_HOME})
                include("${'$'}ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
            endif()
            """.trimIndent()
        )
        val cmakeToolchainPath = toolchainWrapperFile.absolutePath.replace('\\', '/')

        // Resolve CMake and Ninja executables for audiopus_sys native build
        val candidateCmakeFiles = mutableListOf<File>()
        System.getenv("CMAKE")?.takeIf { it.isNotBlank() }?.let { candidateCmakeFiles.add(File(it)) }

        val sdkCmakeRoot = resolvedSdkDir?.resolve("cmake")
        // 1. Android SDK CMake 3.x
        if (sdkCmakeRoot?.isDirectory == true) {
            val sdk3xDirs = sdkCmakeRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("3.") } ?: emptyList()
            for (dir in sdk3xDirs.sortedByDescending { it.name }) {
                candidateCmakeFiles.add(dir.resolve("bin/cmake.exe"))
                candidateCmakeFiles.add(dir.resolve("bin/cmake"))
            }
        }

        // 2. Visual Studio CMake (typically 3.x on Windows)
        if (isWindows) {
            val vsRoots = listOf(
                File("C:/Program Files/Microsoft Visual Studio/2022"),
                File("C:/Program Files (x86)/Microsoft Visual Studio/2019"),
                File("C:/Program Files/Microsoft Visual Studio/2019")
            )
            for (vsRoot in vsRoots) {
                if (vsRoot.isDirectory) {
                    vsRoot.listFiles()?.filter { it.isDirectory }?.forEach { edition ->
                        candidateCmakeFiles.add(edition.resolve("Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe"))
                    }
                }
            }
        }

        // 3. System PATH CMake (e.g. /usr/bin/cmake on Linux / CI)
        findExecutableOnPath("cmake")?.let { candidateCmakeFiles.add(it) }

        // 4. Fallback to any other Android SDK CMake (e.g. 4.x or other)
        if (sdkCmakeRoot?.isDirectory == true) {
            val otherDirs = sdkCmakeRoot.listFiles()?.filter { it.isDirectory && !it.name.startsWith("3.") } ?: emptyList()
            for (dir in otherDirs.sortedByDescending { it.name }) {
                candidateCmakeFiles.add(dir.resolve("bin/cmake.exe"))
                candidateCmakeFiles.add(dir.resolve("bin/cmake"))
            }
        }

        val resolvedCmake = candidateCmakeFiles.firstOrNull { it.isFile }

        val candidateNinjaFiles = mutableListOf<File>()
        System.getenv("NINJA")?.takeIf { it.isNotBlank() }?.let { candidateNinjaFiles.add(File(it)) }
        findExecutableOnPath("ninja")?.let { candidateNinjaFiles.add(it) }
        if (resolvedCmake != null) {
            candidateNinjaFiles.add(resolvedCmake.parentFile.resolve("ninja.exe"))
            candidateNinjaFiles.add(resolvedCmake.parentFile.resolve("ninja"))
            candidateNinjaFiles.add(resolvedCmake.parentFile.parentFile.resolve("Ninja/ninja.exe"))
        }
        if (sdkCmakeRoot?.isDirectory == true) {
            sdkCmakeRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                candidateNinjaFiles.add(dir.resolve("bin/ninja.exe"))
                candidateNinjaFiles.add(dir.resolve("bin/ninja"))
            }
        }
        if (isWindows) {
            val vsRoots = listOf(
                File("C:/Program Files/Microsoft Visual Studio/2022"),
                File("C:/Program Files (x86)/Microsoft Visual Studio/2019"),
                File("C:/Program Files/Microsoft Visual Studio/2019")
            )
            for (vsRoot in vsRoots) {
                if (vsRoot.isDirectory) {
                    vsRoot.listFiles()?.filter { it.isDirectory }?.forEach { edition ->
                        candidateNinjaFiles.add(edition.resolve("Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe"))
                    }
                }
            }
        }

        val resolvedNinja = candidateNinjaFiles.firstOrNull { it.isFile }

        val extraPathDirs = listOfNotNull(resolvedCmake?.parentFile, resolvedNinja?.parentFile).distinct()
        val pathSeparator = File.pathSeparator
        val systemPath = System.getenv("PATH") ?: System.getenv("Path") ?: ""
        val newPath = (extraPathDirs.map { it.absolutePath } + listOf(systemPath))
            .filter { it.isNotBlank() }
            .joinToString(pathSeparator)

        rustTargets.keys.forEach { abi ->
            exec {
                workingDir(rustProjectDir)
                environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
                environment("CMAKE_TOOLCHAIN_FILE", cmakeToolchainPath)
                environment("ANDROID_ABI", abi)
                environment("ANDROID_PLATFORM", "android-$minApi")
                environment("LIBOPUS_STATIC", "1")
                environment("OPUS_STATIC", "1")
                environment("CMAKE_GENERATOR", "Ninja")
                if (resolvedCmake != null) {
                    environment("CMAKE", resolvedCmake.absolutePath)
                }
                if (extraPathDirs.isNotEmpty()) {
                    environment("PATH", newPath)
                    if (isWindows) {
                        environment("Path", newPath)
                    }
                }
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
                    val cargoExe = findExecutableOnPath("cargo")
                    if (cargoExe != null) {
                        val cmdList = mutableListOf(
                            cargoExe.absolutePath,
                            "ndk",
                            "-t", abi,
                            "--platform", minApi.toString(),
                            "-o", sanitizedOutputDir,
                            "build"
                        )
                        if (rustProfileFlag.isNotBlank()) {
                            cmdList.add(rustProfileFlag)
                        }
                        commandLine(cmdList)
                    } else {
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
                }
            }

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
