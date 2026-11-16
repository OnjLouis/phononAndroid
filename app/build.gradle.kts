import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins { id("com.android.application") version "8.7.3" }

val staged = file(providers.gradleProperty("phononStaging").get())
android {
    namespace = "me.onj.phonon.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "me.onj.phonon.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0"
        testInstrumentationRunner = "me.onj.phonon.android.EngineProbe"
        ndk { abiFilters += "arm64-v8a" }
        val key = providers.gradleProperty("phononUpdateKey").orElse("").get()
        require(key.matches(Regex("[A-Za-z0-9+/=]*")))
        buildConfigField("String", "UPDATE_KEY", "\"$key\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes { debug { applicationIdSuffix = if (providers.gradleProperty("phononLab").isPresent) ".lab" else ".test" } }
    sourceSets["main"].assets.srcDir(staged.resolve("assets"))
    sourceSets["main"].jniLibs.srcDir(staged.resolve("jniLibs"))
    sourceSets["main"].res.srcDir(staged.resolve("res"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation("androidx.core:core:1.15.0") }

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val key = providers.gradleProperty("phononUpdateKey").orNull
            ?: error("Release requires the Phonon Android update verification key.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(key))
        ) as RSAPublicKey
        require(publicKey.modulus.bitLength() >= 3072) { "Update verification key is too small." }
    }
}
