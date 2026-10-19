plugins {
    alias(libs.plugins.android.application)
}

// App 侧是纯 Java，没有 Kotlin，所以只挂 AGP。
//
// ⚠️ CodeAssist 的 gradle-compat 构建**只静态解析 Gradle 文件、不执行脚本**。
//    证据：android-app/module.toml 里只有它自己 schema 认识的字段
//    （namespace / compileSdk / minSdk / targetSdk / manifest / versionCode /
//    buildTypes / dependencies），且 buildTypes 只有我这里显式声明的 release
//    —— 如果 Gradle 真跑过，AGP 自带的 debug 也会在。所以：
//      · 任务注册、doLast 检查、afterEvaluate 之类**在这边不会生效**
//      · 别写「看着像门禁、实际不跑」的代码
android {
    namespace = "com.dsh.yaya"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dsh.yaya"

        // 与装机 APK 的清单保持一致：minSdk 24（Android 7.0），targetSdk 28。
        // ⚠️ targetSdk 不要随手往上调：本项目刻意停在 28（requestLegacyExternalStorage、
        //    file:// 分享限制、MANAGE_EXTERNAL_STORAGE 行为都跟着变），
        //    调高会让「导出/备份/分享日志/安装 APK」这些路径在真机上行为不一致。
        minSdk = 24
        targetSdk = 28

        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // 保持与装机包一致：不混淆、不裁剪（代码里有大量反射与进程间调用）。
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        // 防御性打开：AGP 8 默认不再生成 BuildConfig，代码里若引用 BuildConfig.DEBUG
        // 会在编译期报「找不到符号」。
        buildConfig = true
    }

    lint {
        // 这个工程的清单里有 MANAGE_EXTERNAL_STORAGE / PACKAGE_USAGE_STATS 等
        // 需要 tools:ignore 的权限，lint 报错不该挡住编译。
        abortOnError = false
        checkReleaseBuilds = false
    }

    androidResources {
        // payload.zip 内部有 2 万多个文件，这里不压缩：手机上重压 95MB 又慢又容易
        // OOM，而 APK 体积也省不下多少。
        //
        // ⚠️ 这条只有真实 AGP 会认（module.toml 的 schema 里没有 noCompress），
        //    CodeAssist 的构建大概率忽略它。忽略也不影响功能：assets 压不压缩，
        //    getAssets().open() 都能正常读（AssetManager 自己会解压）。
        noCompress += "zip"
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.shizuku.aidl)
}

// ---------------------------------------------------------------------------
// 引擎侧资产（src/main/assets/）的名字来自代码里真实的 getAssets().open(...)，
// 不是猜的：
//   MainActivity.java:2958/4950/5167/5205/9065 → payload.zip（引擎本体，约 95MB）
//   MainActivity.java:2920                     → rish_shizuku.dex
//   MainActivity.java:3003/3020                → vscreen_shizuku.jar
//
// 缺了它们，APK **照样能编、能装、能启动**，直到启动引擎才报
// 「payload.zip 引擎启动失败」—— 这个报错是准确的提示，不是 bug，别去代码里找。
//
// payload.zip（约 95MB）以及 .dex / .jar 直接提交在仓库里，.gitignore 未排除它们，
// 克隆后无需额外导出引擎资产。
//
// 这里**故意不写 Gradle 的 check 任务**：如上所述，CodeAssist 不执行 Gradle 脚本，
// 写了也不会跑。
// ---------------------------------------------------------------------------
