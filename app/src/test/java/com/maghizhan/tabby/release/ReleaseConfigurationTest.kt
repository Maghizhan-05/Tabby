package com.maghizhan.tabby.release

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the upload-build contract down without requiring a real keystore in CI. */
class ReleaseConfigurationTest {

    private val root: Path = Path.of(System.getProperty("user.dir"))
    private val buildScript: String = root.resolve("build.gradle.kts").readText()
    private val ignoreRules: String = root.parent.resolve(".gitignore").readText()

    @Test
    fun `release signing is sourced from a gitignored properties file`() {
        assertTrue(buildScript.contains("keystore.properties"))
        assertTrue(buildScript.contains("storeFile"))
        assertTrue(buildScript.contains("storePassword"))
        assertTrue(buildScript.contains("keyAlias"))
        assertTrue(buildScript.contains("keyPassword"))
        assertTrue(buildScript.contains("signingConfig = signingConfigs.getByName(\"release\")"))
        assertTrue(ignoreRules.lineSequence().any { it.trim() == "keystore.properties" })
        assertFalse(Files.exists(root.parent.resolve("keystore.properties")))
    }

    @Test
    fun `release build fails clearly rather than silently producing an unsigned bundle`() {
        assertTrue(buildScript.contains("Missing keystore.properties"))
        assertTrue(buildScript.contains("requestedTasks.any"))
    }

    @Test
    fun `version metadata has one documented bump point`() {
        assertTrue(buildScript.contains("Release version — bump both values here before every Play upload"))
        assertTrue(buildScript.contains("versionCode = 1"))
        assertTrue(buildScript.contains("versionName = \"1.0.0\""))
    }

    @Test
    fun `release build has an advertising id manifest tripwire`() {
        assertTrue(buildScript.contains("assertNoAdvertisingId"))
        assertTrue(buildScript.contains("com.google.android.gms.permission.AD_ID"))
        assertTrue(buildScript.contains("Advertising ID permission detected"))
    }
}
