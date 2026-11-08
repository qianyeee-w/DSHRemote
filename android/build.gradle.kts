// 各模块的插件在这里声明版本，模块内只应用不写版本（版本统一在 gradle/libs.versions.toml）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}
