import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.1.21"
}

group = "com.mock-server"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        // Bundled JSON plugin — provides the JsonSchemaProviderFactory API used to
        // associate the expectation schema with *.mockserver.json files.
        bundledPlugin("com.intellij.modules.json")
        // IntelliJ Platform test fixtures — provides BasePlatformTestCase, used by
        // MockServerSchemaWiringTest to assert, in a real headless IDE, that the
        // JSON-schema provider factory is wired up under the correct extension point.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Gson for building/parsing the small JSON payloads exchanged with the running
    // MockServer (OpenAPI spec wrapping, pretty-printing). Declared explicitly so
    // MockServerRestClient is unit-testable on the plain test classpath without the
    // IntelliJ platform; the platform also bundles a compatible Gson at runtime.
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
    // Validates what the plugin SENDS against the expectation JSON Schema the plugin BUNDLES — the
    // same schema MockServer itself validates against. Without this the LLM builder's output was only
    // ever asserted against itself, so it shipped a document the server rejects (issue #2455).
    // Same library and major version MockServer uses server-side, so the two agree on draft-07.
    testImplementation("com.networknt:json-schema-validator:3.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            // Open-ended compatibility: a null untilBuild omits the `until-build`
            // upper bound from plugin.xml entirely, so the plugin stays available in
            // current and future IDE builds (e.g. 261+) instead of being capped. The
            // plugin uses only stable, public platform APIs, so we don't pin an upper
            // bound pre-emptively — the Plugin Verifier (verifyPlugin) is the backstop
            // that catches any real incompatibility before release.
            //
            // NOTE: this MUST be null, not an empty string. An empty string makes the
            // plugin emit `until-build=""`, which JetBrains Marketplace rejects at
            // upload ("does not match the multi-part build number format"); a null
            // provider omits the attribute, which is what open-ended compatibility
            // requires. verifyPlugin/buildPlugin do NOT catch the empty-attribute
            // case — it only fails at the Marketplace publish step.
            untilBuild = provider { null }
        }
    }
    // Run by the `verifyPlugin` CI step (.buildkite/scripts/steps/jetbrains-verify.sh)
    // on every commit that touches the plugin, so internal/deprecated/incompatible
    // IntelliJ Platform API usages are caught before a Marketplace upload is rejected.
    pluginVerification {
        // Verify against JetBrains' recommended IDE set — the same range the
        // Marketplace checks, including the latest EAP (which is what surfaced the
        // PluginManagerCore internal-API and JBCefBrowser deprecated-API findings).
        ides {
            recommended()
        }
        // Fail the build on the Marketplace-blocking finding classes: internal API,
        // deprecated / scheduled-for-removal API, and binary compatibility problems.
        // (By default the verifier only fails on COMPATIBILITY_PROBLEMS + INVALID_PLUGIN,
        // so internal/deprecated usages would pass silently without this.)
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.INVALID_PLUGIN,
        )
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
