import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

// Maven coordinate only. Unrelated to the plugin id in plugin.xml; changing it
// does not affect installs or updates.
group = "com.kaydenmiller"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build and test platform: IntelliJ IDEA Community 2026.1.x (build 261).
        // Deliberately NOT Rider: IDEA Community is what makes the platform test
        // fixtures (BasePlatformTestCase) resolvable. The plugin itself depends only
        // on com.intellij.modules.platform, so it loads in any IntelliJ-platform IDE.
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        ) {
            // ideaIC is no longer published as a downloadable installer after 2025.3,
            // but it is still published as a Maven artifact. Take the Maven route so the
            // platform stays genuine IDEA Community rather than the unified IDEA build.
            useInstaller = false
        }
        testFramework(TestFrameworkType.Platform)
    }
    // TestFrameworkType.Platform is JUnit 4 based and does not bring JUnit itself.
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild.unset() // no upper bound; see PLAN.md §3 / TASKS.md W-1
        }
    }

    pluginVerification {
        ides {
            // Verify against the real target IDE where one exists: on a machine with Rider
            // installed this is the local install, nothing is downloaded, and the verdict is
            // against the exact build the plugin is meant for.
            //
            // A continuous-integration runner has no Rider.app. The first CI run tried
            // `create(Rider, <local build number>)` and failed with
            //   Could not find com.jetbrains.intellij.rider:riderRD:261.23567.144
            // because `261.23567.144` is an *installer* build number, not a published Maven
            // coordinate. Rather than guess a second version string, the fallback verifies
            // against the platform this project already builds and tests against, which is
            // guaranteed to resolve because the build has just downloaded it.
            //
            // The fallback is a weaker check than the local one: it confirms the plugin is
            // compatible with the IntelliJ platform of that build, not with Rider
            // specifically. That is sound here only because the plugin declares
            // `com.intellij.modules.platform` as its single dependency and uses no
            // Rider-only API. If either of those ever stops being true, this has to change.
            val riderLocal = file(providers.gradleProperty("riderLocalPath").get())
            if (riderLocal.isDirectory) {
                local(riderLocal)
            } else {
                create(
                    providers.gradleProperty("platformType").get(),
                    providers.gradleProperty("platformVersion").get(),
                )
            }
        }
    }
}

// Launching the sandbox in the real target IDE. Separate from the build platform above.
intellijPlatformTesting {
    runIde {
        register("runRider") {
            localPath = file(providers.gradleProperty("riderLocalPath").get())
            // Optional: -PsandboxProject=/path opens that project on launch, so a
            // sandbox run can exercise the watcher against a real `.why/` root
            // instead of the welcome screen.
            task {
                providers.gradleProperty("sandboxProject").orNull?.let { args = listOf(it) }
            }
        }
    }
}

tasks.test {
    useJUnit()
}
