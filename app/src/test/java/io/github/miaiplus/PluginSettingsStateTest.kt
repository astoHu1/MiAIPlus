package io.github.miaiplus

import android.app.Application
import android.content.pm.PackageInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import org.robolectric.Shadows.shadowOf

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

    @Test fun unconfiguredTermuxKeepsSetupInItsOwnPage() {
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithTag("tab-Plugins").performClick()
        termuxSwitch("termux-enabled").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("termux-setup").assertDoesNotExist()
        compose.onNodeWithTag("termux-settings").performScrollTo().performClick()
        compose.onNodeWithTag("termux-setup").assertHasNoClickAction()
        compose.onNodeWithText("请先安装 Termux").assertIsDisplayed()
        compose.onNodeWithTag("termux-start").assertHasNoClickAction()
        termuxSwitch("termux-autostart").assertIsNotEnabled()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        installTermuxFixture(app)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("自动配置").assertIsDisplayed()
        compose.onNodeWithTag("termux-setup").assertHasClickAction()
        compose.onNodeWithTag("termux-start").assertHasNoClickAction()
    }

    @Test fun configuredTermuxOptionsStayInSubpageAndPersistAcrossRecreation() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val app = state.getApplication<Application>()
        compose.runOnIdle {
            installTermuxFixture(app)
            val id = PluginSettings.TERMUX_ID
            val tools = listOf("run", "run_digest", "out_read").map { name ->
                PluginToolSpec(id, "mcp", PluginToolSpec.mcpName(id, name), name, name, "Fixture",
                    """{"type":"object"}""", 60_000)
            }
            PluginSettings.saveTermux(app, PluginSettings.load(app).revision(),
                PluginSettings.Entry(id, PluginSettings.TERMUX_KIND, "Termux MCP", true,
                    "http://127.0.0.1:18872/mcp", "d".repeat(64), TermuxMcp.PROTOCOL, tools))
            assertTrue(state.plugins.reload())
        }
        compose.onNodeWithTag("tab-Plugins").performClick()
        termuxSwitch("termux-enabled").assertIsOn()
        for (tag in listOf("termux-autostart", "termux-start", "termux-setup")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.onNodeWithTag("termux-settings").performScrollTo().performClick()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.onNodeWithTag("termux-enabled").assertDoesNotExist()
        compose.onNodeWithTag("termux-start").assertHasClickAction()
        termuxSwitch("termux-autostart").assertIsOff()
        compose.onNodeWithTag("termux-autostart").performClick()
        termuxSwitch("termux-autostart").assertIsOn()
        assertTrue(PluginSettings.termuxAutoStart(app))

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("termux-settings-page").assertExists()
        termuxSwitch("termux-autostart").assertIsOn()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("termux-enabled").performScrollTo().performClick()
        termuxSwitch("termux-enabled").assertIsOff()
        compose.onNodeWithTag("termux-settings").performScrollTo().performClick()
        termuxSwitch("termux-autostart").assertIsOn().assertIsNotEnabled()
    }

    private fun termuxSwitch(tag: String) = compose.onNode(
        hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        useUnmergedTree = true,
    )

    private fun installTermuxFixture(app: Application) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
    }

    @Test fun mcpEditorKeepsSelectionWhenRevealingTokenAndDropsCancelledInput() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            state.plugins.create()
            state.plugins.updateDraft(label = "Fixture MCP", endpoint = "https://example.test/mcp",
                bearerToken = "fixture-token")
            assertTrue(state.plugins.save())
        }
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onNodeWithText("Fixture MCP", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("mcp-plugin-page").assertExists()
        compose.onNodeWithText("编辑连接", substring = false).performScrollTo().performClick()
        val token = compose.onNodeWithTag("plugin-token").performScrollTo()
        token.performTextInput("-123")
        token.performTextInputSelection(androidx.compose.ui.text.TextRange(8, 13))
        compose.onNodeWithContentDescription("显示 Token").performClick()
        compose.onNodeWithContentDescription("隐藏 Token").assertExists()
        token.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(8, 13)))
        token.performTextInput("changed")
        token.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText,
            androidx.compose.ui.text.AnnotatedString("fixture-changed-123")))
        compose.onNodeWithText("取消", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("编辑连接", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("plugin-token").assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText,
            androidx.compose.ui.text.AnnotatedString("fixture-token")))
        compose.onNodeWithTag("plugin-token").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("取消", substring = false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("add-mcp-plugin").performClick()
        for (tag in listOf("plugin-label", "plugin-endpoint", "plugin-token")) {
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText,
                androidx.compose.ui.text.AnnotatedString("")))
        }
        compose.runOnIdle {
            assertEquals("fixture-token", state.plugins.entries.first { it.kind() == "mcp" }.bearerToken())
        }
    }

    @After fun clearPreferences() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        Config.prefs(state.getApplication<Application>()).edit().clear().commit()
    }

    @Test fun mcpCredentialDraftStaysMaskedAcrossRecreationAndEnablingRequiresConfirmation() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onNodeWithTag("add-mcp-plugin").performClick()
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
        compose.onNodeWithTag("page-Mcp").assertExists()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.onNodeWithTag("mcp-enabled").performScrollTo().performClick()
        compose.onNodeWithText("启用 MCP 插件？").assertExists()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertFalse(state.plugins.entries.first { it.id() == remote.id() }.enabled())
    }

    @Test fun mcpEditorSavesExplicitProtocolAndReopensSelection() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onNodeWithTag("add-mcp-plugin").performClick()
        compose.onNodeWithTag("plugin-label").performTextInput("Termux compatibility")
        compose.onNodeWithTag("plugin-endpoint").performTextInput("http://127.0.0.1:18767/mcp")
        compose.onNodeWithText("协议版本").performScrollTo().performClick()
        compose.onNodeWithText("2025-06-18（兼容）", substring = false).performClick()
        assertEquals("2025-06-18", state.plugins.draft?.protocolVersion)
        compose.onNodeWithText("保存", substring = false).performScrollTo().performClick()
        val remote = state.plugins.entries.first { it.kind() == "mcp" }
        assertEquals("2025-06-18", remote.protocolVersion())

        compose.onNodeWithTag("page-Mcp").assertExists()
        compose.onNodeWithText("编辑连接", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("2025-06-18（兼容）", substring = false).assertExists()
        compose.activityRule.scenario.recreate()
        assertEquals("2025-06-18", state.plugins.draft?.protocolVersion)
        compose.onNodeWithText("协议版本").performScrollTo().performClick()
        compose.onNodeWithText("2025-11-25（默认）", substring = false).performClick()
        compose.onNodeWithText("取消", substring = false).performScrollTo().performClick()
        assertEquals("2025-06-18", state.plugins.entries.first { it.id() == remote.id() }.protocolVersion())
    }
}
