import java.util.Properties

plugins { id("com.android.application") }

// Signing is consumed only by Gradle; no secret value is printed or generated into the APK.
val repositoryDirectory = projectDir.parentFile
val guardSigningFile = repositoryDirectory.resolve("keystore.properties")
val guardSigningProperties = Properties()
if (guardSigningFile.exists()) {
    guardSigningFile.inputStream().use(guardSigningProperties::load)
}

android {
    namespace = "dev.stanislav.focusdeck.guard"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.stanislav.focusdeck.guard"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-audit"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("guardRelease") {
            guardSigningProperties.getProperty("storeFile")?.let {
                storeFile = repositoryDirectory.resolve(it)
                storePassword = guardSigningProperties.getProperty("storePassword")
                keyAlias = guardSigningProperties.getProperty("keyAlias")
                keyPassword = guardSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("guardRelease")
        }
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
