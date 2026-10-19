pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "deepseek-harness-android"

// App 侧（Java）：MainActivity / 引擎保活服务 / 无障碍 / 悬浮窗 / 自建环境安装器等
include(":android-app")
