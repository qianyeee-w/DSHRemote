plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.qianyeeew.dshremote"
    // ADR 0011：compileSdk 37（AndroidX 要求），targetSdk 35（决定运行时行为）
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.qianyeeew.dshremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    buildTypes {
        release {
            // release 签名和发布不在 M2 范围内（m2-android.md 第 7 节）
            isMinifyEnabled = false
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
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
