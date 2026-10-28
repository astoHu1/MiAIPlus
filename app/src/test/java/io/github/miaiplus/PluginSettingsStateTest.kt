package io.github.miaiplus

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class PluginSettingsStateTest {
    private val application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun clearBefore() { Config.prefs(application).edit().clear().commit() }
    @After fun clearAfter() {
        scope.cancel()
        Config.prefs(application).edit().clear().commit()
    }

    @Test fun defaultBuiltinPluginsRemainIndependentAndMcpStartsDisabled() {
        val state = PluginState(application, scope)
        assertEquals(listOf(PluginSettings.FOREGROUND_ID, PluginSettings.BACKGROUND_ID), state.entries.map { it.id() })
        assertTrue(state.entries.all { it.enabled() })
        assertEquals("2 个内置工具 · 0 个 MCP 工具", state.summary)
        assertFalse(Config(application).enabled)

        state.create()
        state.updateDraft(label = "Fixture MCP", endpoint = "https://example.com/mcp", bearerToken = "fixture-token")
        assertEquals(2, PluginSettings.load(application).entries().size)
        assertTrue(state.save())
        val remote = state.entries.first { it.kind() == "mcp" }
        assertFalse(remote.enabled())
        assertEquals("fixture-token", remote.bearerToken())
        assertEquals(PluginSettings.MCP_PROTOCOL_VERSION, remote.protocolVersion())
        assertTrue(state.setEnabled(remote.id(), true))
        assertFalse(Config(application).enabled)

        val reloaded = PluginState(application, scope)
        assertTrue(reloaded.entries.first { it.id() == remote.id() }.enabled())
        assertTrue(reloaded.setEnabled(PluginSettings.FOREGROUND_ID, false))
        assertFalse(reloaded.entries.first { it.id() == PluginSettings.FOREGROUND_ID }.enabled())
        assertTrue(reloaded.entries.first { it.id() == PluginSettings.BACKGROUND_ID }.enabled())
        assertTrue(reloaded.entries.first { it.id() == remote.id() }.enabled())
    }

    @Test fun editingConnectionClearsDiscoveredToolsButRenamingPreservesThem() {
        val state = PluginState(application, scope)
        state.create()
        state.updateDraft(label = "Fixture MCP", endpoint = "https://example.com/mcp", bearerToken = "fixture-token")
        assertTrue(state.save())
        val initial = requireNotNull(state.snapshot)
        val id = initial.entries().first { it.kind() == "mcp" }.id()
        val tool = PluginToolSpec(id, "mcp", PluginToolSpec.mcpName(id, "read_fixture"), "read_fixture",
            "Read fixture", "Read synthetic data", """{"type":"object"}""", 30000)
        PluginSettings.updateTools(application, id, initial.revision(), listOf(tool))
        assertTrue(state.reload())
        assertEquals("2 个内置工具 · 1 个 MCP 工具", state.summary)

        state.edit(id)
        state.updateDraft(label = "Renamed fixture")
        assertTrue(state.save())
        assertEquals(listOf(tool), state.entries.first { it.id() == id }.tools())
        state.edit(id)
        state.updateDraft(bearerToken = "replacement-fixture-token")
        assertTrue(state.save())
        assertTrue(state.entries.first { it.id() == id }.tools().isEmpty())
        assertEquals("replacement-fixture-token", PluginSettings.load(application).find(id).bearerToken())
    }

    @Test fun selectedProtocolPersistsAcrossEditingAndDiscardedSelectionIsNotSaved() {
        val state = PluginState(application, scope)
        state.create()
        assertEquals(PluginSettings.MCP_PROTOCOL_VERSION, state.draft?.protocolVersion)
        state.updateDraft(label = "Termux compatibility", endpoint = "http://127.0.0.1:18767/mcp",
            protocolVersion = "2025-06-18")
        assertTrue(state.save())
        val remote = state.entries.first { it.kind() == "mcp" }
        assertEquals("2025-06-18", remote.protocolVersion())

        val reloaded = PluginState(application, scope)
        reloaded.edit(remote.id())
        assertEquals("2025-06-18", reloaded.draft?.protocolVersion)
        reloaded.updateDraft(protocolVersion = PluginSettings.MCP_PROTOCOL_VERSION)
        reloaded.discardDraft()
        reloaded.edit(remote.id())
        assertEquals("2025-06-18", reloaded.draft?.protocolVersion)
        assertEquals("2025-06-18", PluginSettings.load(application).find(remote.id()).protocolVersion())
    }

    @Test fun invalidDraftDoesNotOverwriteStoredConfigAndCancellationDropsPrivateDraft() {
        val state = PluginState(application, scope)
        state.create()
        state.updateDraft(label = "Fixture MCP", endpoint = "https://example.com/mcp?token=private-fixture",
            bearerToken = "private-fixture")
        val before = PluginSettings.load(application)
        assertFalse(state.save())
        assertNotNull(state.draft)
        assertEquals(before, PluginSettings.load(application))
        assertFalse(state.error.orEmpty().contains("private-fixture"))
        state.discardDraft()
        assertNull(state.draft)
        assertNull(state.error)
        assertEquals(2, state.entries.size)
    }

    @Test fun deletingRemotePluginRemovesItsCredentialWithoutDeletingBuiltinPlugins() {
        val state = PluginState(application, scope)
        state.create()
        state.updateDraft(label = "Fixture MCP", endpoint = "https://example.test/mcp", bearerToken = "unique-fixture-token")
        assertTrue(state.save())
        val id = state.entries.first { it.kind() == "mcp" }.id()
        assertTrue(state.delete(id))
        assertEquals(2, state.entries.size)
        assertFalse(Config.prefs(application).getAll().toString().contains("unique-fixture-token"))
        assertFalse(state.delete(PluginSettings.FOREGROUND_ID))
        assertEquals(2, PluginSettings.load(application).entries().size)
    }

    @Test fun unreadableConfigurationNeverDisplaysAnEmptyOrStaleToolCount() {
        val state = PluginState(application, scope)
        assertEquals("2 个内置工具 · 0 个 MCP 工具", state.summary)
        Config.prefs(application).edit().putString(PluginSettings.PREFERENCE_KEY, "invalid fixture").commit()
        assertFalse(state.reload())
        assertNull(state.snapshot)
        assertEquals("插件配置不可读", state.summary)
        assertTrue(state.entries.isEmpty())
        assertEquals("插件配置不可读", PluginState(application, scope).summary)
        assertEquals("invalid fixture", Config.prefs(application).getString(PluginSettings.PREFERENCE_KEY, null))
    }

    @Test fun draftStringRepresentationRedactsConnectionAndCredentials() {
        val draft = McpPluginDraft(label = "Fixture MCP", endpoint = "https://private.example.test/mcp",
            bearerToken = "private-fixture-token")
        val text = draft.toString()
        assertFalse(text.contains(draft.endpoint))
        assertFalse(text.contains(draft.bearerToken))
        assertTrue(text.contains("endpoint=[redacted]"))
        assertTrue(text.contains("bearerToken=[redacted]"))
    }
}

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h873dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearPreferences() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        Config.prefs(state.getApplication<Application>()).edit().clear().commit()
    }

    @Test fun pluginPageShowsPeerBuiltinsAndKeepsCredentialDraftMaskedAcrossRecreation() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.onNodeWithText("插件", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("plugin-settings").assertExists()
        compose.onNodeWithText("前台 Phone Use").assertExists()
        compose.onNodeWithText("后台 Phone Use").assertExists()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        capture("settings-plugins-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        capture("settings-plugins-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("添加 MCP 插件").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-label").performTextInput("Fixture MCP")
        compose.onNodeWithTag("plugin-endpoint").performTextInput("https://example.com/mcp")
        compose.onNodeWithTag("plugin-token").performTextInput("synthetic-fixture-token")
        compose.onNodeWithTag("plugin-token").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertEquals(2, state.plugins.entries.size)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("plugin-token").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertEquals("synthetic-fixture-token", state.plugins.draft?.bearerToken)
        compose.onNodeWithText("保存", substring = false).performScrollTo().performClick()
        val remote = state.plugins.entries.first { it.kind() == "mcp" }
        assertFalse(remote.enabled())
        compose.onNodeWithText("Fixture MCP", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("启用 MCP 插件？").assertExists()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertFalse(state.plugins.entries.first { it.id() == remote.id() }.enabled())
        capture("settings-plugins-mcp")
    }

    @Test fun toolInspectionShowsSourceNameAndReadOnlyExpandableInputSchema() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        lateinit var tool: PluginToolSpec
        compose.runOnIdle {
            state.plugins.create()
            state.plugins.updateDraft(label = "Fixture MCP", endpoint = "https://example.test/mcp")
            assertTrue(state.plugins.save())
            val snapshot = requireNotNull(state.plugins.snapshot)
            val id = snapshot.entries().first { it.kind() == "mcp" }.id()
            tool = PluginToolSpec(id, "mcp", PluginToolSpec.mcpName(id, "read_fixture"), "read_fixture",
                "Read fixture", "Read synthetic data",
                """{"type":"object","properties":{"city":{"type":"string"}}}""", 30000)
            PluginSettings.updateTools(state.getApplication<Application>(), id, snapshot.revision(), listOf(tool))
            assertTrue(state.plugins.reload())
        }
        compose.onNodeWithText("插件", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("工具列表", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("来源名称：read_fixture").assertExists()
        compose.onNodeWithText("注入名称：${tool.name()}").assertExists()
        compose.onNodeWithTag("plugin-schema-${tool.name()}").assertDoesNotExist()
        compose.onNodeWithText("查看参数结构").performClick()
        compose.onNodeWithTag("plugin-schema-${tool.name()}").assertTextContains("\"city\"", substring = true)
        compose.onNodeWithText("收起参数结构").performClick()
        compose.onNodeWithTag("plugin-schema-${tool.name()}").assertDoesNotExist()
    }

    @Test fun mcpEditorSavesExplicitProtocolAndReopensSelection() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.onNodeWithText("插件", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("添加 MCP 插件").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-label").performTextInput("Termux compatibility")
        compose.onNodeWithTag("plugin-endpoint").performTextInput("http://127.0.0.1:18767/mcp")
        compose.onNodeWithText("MCP 协议版本").performScrollTo().performClick()
        compose.onNodeWithText("2025-06-18（兼容）", substring = false).performClick()
        assertEquals("2025-06-18", state.plugins.draft?.protocolVersion)
        compose.onNodeWithText("保存", substring = false).performScrollTo().performClick()
        val remote = state.plugins.entries.first { it.kind() == "mcp" }
        assertEquals("2025-06-18", remote.protocolVersion())

        compose.onNodeWithText("编辑", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("2025-06-18（兼容）", substring = false).assertExists()
        compose.activityRule.scenario.recreate()
        assertEquals("2025-06-18", state.plugins.draft?.protocolVersion)
        compose.onNodeWithText("MCP 协议版本").performScrollTo().performClick()
        compose.onNodeWithText("2025-11-25（默认）", substring = false).performClick()
        compose.onNodeWithText("取消", substring = false).performScrollTo().performClick()
        assertEquals("2025-06-18", state.plugins.entries.first { it.id() == remote.id() }.protocolVersion())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
