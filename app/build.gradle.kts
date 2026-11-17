import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing key lives in keystore.properties (created by tools/make-keystore.ps1, never committed).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Where Feedback writes to (gradle.properties). Empty means the email app asks for an address.
val feedbackEmail = (findProperty("willbolt.feedbackEmail") as String?)?.trim().orEmpty()

/** Copies the repository's legal texts into the app's assets, so the app shows the same text GitHub and Play show. */
abstract class CopyLegal : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("legal")
        out.deleteRecursively()
        out.mkdirs()
        sources.files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

/**
 * Fails the build when the merged manifest of a release variant has a permission, activity, service, receiver or provider
 * that is not in the edition's allowlist (manifest-allowlist/<edition>.txt). Libraries add manifest entries on their own -
 * that is how ONNX Runtime's telemetry provider got in - so every new one has to be looked at before it is allowed.
 */
abstract class CheckManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowlist: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val android = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        val found = sortedSetOf<String>()
        for (tag in listOf("uses-permission", "permission", "activity", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as org.w3c.dom.Element).getAttributeNS(android, "name")
                if (name.isNotEmpty()) found += "$tag $name"
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(found.joinToString("\n") + "\n")

        val file = allowlist.get().asFile
        val allowed = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val unexpected = found - allowed
        val gone = allowed - found
        if (gone.isNotEmpty()) {
            logger.warn("These are in ${file.name} but no longer in the manifest, remove them:\n" + gone.joinToString("\n") { "  $it" })
        }
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "The merged manifest has entries that are not in ${file.name}:\n" + unexpected.joinToString("\n") { "  $it" } +
                    "\nFind out where each comes from (a library? the full edition's manifest?) and what it does. If it is wanted, " +
                    "add it to the allowlist; if not, remove it with tools:node=\"remove\" in the manifest.",
            )
        }
    }
}

android {
    namespace = "app.willbolt"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.willbolt"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.6.9"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "FEEDBACK_EMAIL", "\"$feedbackEmail\"")
    }

    // Two editions of one app. "play" is what the Play Store gets; "full" is the same app plus the parts Play does not
    // allow (guarding its own uninstall and switching its accessibility service back on over USB), for installs from
    // GitHub. They share one app ID and one signing key, so the full edition installs over the play one and back.
    flavorDimensions += "edition"
    productFlavors {
        create("play") { dimension = "edition" }
        create("full") { dimension = "edition" }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Installs next to the real app (own app ID, own data) so a phone can be used to try things without touching the
        // real settings. It is debuggable, so it is never to be released or kept on a phone.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The model is read straight from assets; storing it uncompressed keeps APK installs fast.
    androidResources {
        noCompress += listOf("onnx")
        // Willbolt is in English; this keeps the date and time pickers in English too, whatever the phone's language.
        localeFilters += listOf("en")
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    lint { abortOnError = false }

    testOptions { unitTests.isReturnDefaultValues = true }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val legal = tasks.register<CopyLegal>("copyLegal$cap") {
            sources.from(rootProject.file("PRIVACY.md"), rootProject.file("THIRD_PARTY_NOTICES.md"))
            outputDir.set(layout.buildDirectory.dir("generated/legal/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(legal, CopyLegal::outputDir)

        if (variant.buildType == "release") {
            val check = tasks.register<CheckManifest>("verifyManifest$cap") {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                allowlist.set(layout.projectDirectory.file("manifest-allowlist/${variant.flavorName}.txt"))
                report.set(layout.buildDirectory.file("reports/manifest/${variant.name}.txt"))
            }
            // Packaging an APK or a bundle of the variant checks its manifest first.
            tasks.matching { it.name == "package$cap" || it.name == "package${cap}Bundle" }.configureEach { dependsOn(check) }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    testImplementation("junit:junit:4.13.2")
}
