import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // 国内镜像加速本地构建；CI 上部分插件标记在镜像缺失，改用 -Pci=true 只走官方仓库
        if (gradle.startParameter.projectProperties["ci"] != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (gradle.startParameter.projectProperties["ci"] != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Diary"

include(":app")

include(":core:model")
include(":core:database")
include(":core:common")
include(":core:designsystem")
include(":core:font")
include(":core:fontui")
include(":core:richtext")
include(":core:backup")

include(":feature:diarylist")
include(":feature:editor")
include(":feature:reader")
include(":feature:category")
include(":feature:fontsetting")
include(":feature:export")
include(":feature:settings")
