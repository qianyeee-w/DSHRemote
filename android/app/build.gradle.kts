plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

/**
 * 版本号只在 gradle.properties 的 dshremote.version 中定义（m3-release.md T2），App 和插件共用，发布时 CI 检查它和标签一致。
 * 格式 X.Y.Z 或 X.Y.Z-rc.N（Y、Z ≤ 99，N 为 1–98）。
 * versionCode = X * 1000000 + Y * 10000 + Z * 100 + (rc 的 N，正式版为 99)：rc 早于正式版，正式版早于下一个版本。
 */
fun releaseVersionCode(version: String): Int {
    val match =
        Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-rc\.([1-9]\d?))?$""").matchEntire(version)
            ?: throw GradleException("dshremote.version 格式不对：$version，应为 X.Y.Z 或 X.Y.Z-rc.N（Y、Z ≤ 99）")
    val (major, minor, patch, rc) = match.destructured
    val build = if (rc.isEmpty()) 99 else rc.toInt()
    if (build > 98 && rc.isNotEmpty()) throw GradleException("dshremote.version 的 rc 编号最大为 98：$version")
    if (major.toInt() > 2000) throw GradleException("dshremote.version 的主版本号太大：$version")
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + build
}

val releaseVersion = providers.gradleProperty("dshremote.version").get()
val debugApplicationIdSuffix = providers.gradleProperty("dshremote.debugApplicationIdSuffix").orElse(".debug").get()

/**
 * release 包的签名（m3-release.md T2）：只从环境变量读取，密钥文件和密码不进仓库。
 * 密钥是 PKCS12 格式，密钥密码和密钥库密码相同。缺少任何一项时，打包 release 直接失败（见 checkReleaseSigning），
 * 不会悄悄生成未签名或用调试密钥签名的包。
 */
val releaseSigningEnv = listOf("DSHREMOTE_KEYSTORE", "DSHREMOTE_KEYSTORE_PASSWORD", "DSHREMOTE_KEY_ALIAS")
val releaseSigning = releaseSigningEnv.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val missingReleaseSigning = releaseSigning.filterValues { it == null }.keys.toList()

// 本地单元测试使用同版本的 JVM 存储后端。Android 后端在 mockable android.jar 中走旧版 renameTo
// 路径，在 Windows 上不能覆盖目标文件；真机与 APK 仍使用 Android 后端。
val jvmDataStore = "androidx.datastore:datastore-core-jvm:${libs.versions.datastore.get()}"
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.datastore:datastore-core")).using(module(jvmDataStore))
        substitute(module("androidx.datastore:datastore-core-android")).using(module(jvmDataStore))
    }
}

android {
    namespace = "io.github.qianyeeew.dshremote"
    // ADR 0011：compileSdk 37（AndroidX 要求），targetSdk 35（决定运行时行为）
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.qianyeeew.dshremote"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode(releaseVersion)
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // JVM 单元测试和真机测试共用的测试数据与工具（例如插件生成的证书，用于跨实现的指纹一致性测试）
    sourceSets {
        getByName("test") {
            kotlin.directories.add("src/sharedTest/java")
            resources.directories.add("src/sharedTest/resources")
        }
        getByName("androidTest") {
            kotlin.directories.add("src/sharedTest/java")
            resources.directories.add("src/sharedTest/resources")
        }
    }

    signingConfigs {
        if (missingReleaseSigning.isEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getValue("DSHREMOTE_KEYSTORE")!!)
                storeType = "pkcs12"
                storePassword = releaseSigning.getValue("DSHREMOTE_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("DSHREMOTE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("DSHREMOTE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 和 release 包同时装在手机上，互不覆盖；应用名见 src/debug/res（「DSHRemote 调试」）
            // 需要保留另一台电脑签名的调试包时，可用 Gradle 属性指定隔离的测试包名。
            applicationIdSuffix = debugApplicationIdSuffix
            versionNameSuffix = "-debug"
        }
        release {
            // 不开启 R8（m3-release.md 第 1 节）：避免破坏依赖反射的代码，v1 不在意包的大小
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // 对 dev-server 的集成测试只由下面的 devServerTest 任务运行
            it.exclude("**/*DevServerTest.class")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        disable +=
            setOf(
                // targetSdk 有意停在 35（ADR 0011），升级要单独写 ADR
                "OldTargetApi",
                "ExpiredTargetSdkVersion",
                // 依赖升级单独提交，不让 lint 因为「有新版本」而失败
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
            )
    }
}

// 对插件 dev-server 的集成测试（m2-android.md T5）。只在本地运行：先在 plugin/ 下 npm run dev-server
tasks.register<Test>("devServerTest") {
    description = "对插件的 dev-server 跑集成测试（需要先启动 dev-server）"
    group = "verification"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    include("**/*DevServerTest.class")
    systemProperty("dshremote.pluginDir", rootProject.projectDir.resolve("../plugin").canonicalPath)
    // 结果取决于外部的 dev-server，每次都要真的运行
    outputs.upToDateWhen { false }
}

// 打包 release 之前检查签名配置齐全。只挂在打包任务上，test、lint 等其他任务不受影响
val checkReleaseSigning =
    tasks.register("checkReleaseSigning") {
        description = "检查 release 签名所需的环境变量"
        group = "verification"
        val missing = missingReleaseSigning
        doLast {
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "缺少 release 签名所需的环境变量：${missing.joinToString()}。" +
                        "release 包只能用正式发布密钥签名，见 docs/plans/m3-release.md T2",
                )
            }
        }
    }
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    dependsOn(checkReleaseSigning)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.markdown.renderer.m3)
    debugImplementation(libs.compose.ui.tooling)
    // Compose 界面测试需要的空 Activity（只进 debug 包）
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
