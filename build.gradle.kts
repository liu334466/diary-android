plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * 所有子模块统一接入 ktlint：`gradle ktlintCheck` 做静态检查，`gradle ktlintFormat` 自动修复。
 * 跳过构建产物与 KSP/Room 生成的代码，只检查手写源码。
 */
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        filter {
            exclude { element -> element.file.path.contains("/build/") }
        }
    }

    // 单测失败时打印具体用例与堆栈，便于在 CI 日志里定位
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat =
                org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
