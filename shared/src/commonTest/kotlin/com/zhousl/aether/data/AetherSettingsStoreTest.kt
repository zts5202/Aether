package com.zhousl.aether.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class AetherSettingsStoreTest {
    @Test
    fun generalSettingsSurviveSaveAndReload() = runTest {
        val store = AetherSettingsStore(InMemoryPreferencesDataStore())
        val expected = AppSettings(
            piProviderId = "anthropic",
            providerConfigId = "provider-id",
            providerAuthMethod = ProviderAuthMethod.OAuth,
            apiKey = "secret",
            oauthCredentialJson = "{\"access\":\"token\"}",
            providerEnvironmentVariables = listOf(PiProviderEnvironmentVariable("REGION", "test")),
            baseUrl = "https://provider.example/v1",
            modelId = "chat-model",
            userAgent = "Aether-Test",
            customHeaders = listOf(LlmCustomHeader("X-Test", "value")),
            developerRoleUnsupported = true,
            language = AppLanguage.SimplifiedChinese,
            themeMode = AppThemeMode.Dark,
            systemPrompt = "Keep these instructions",
            reasoningEffort = "high",
            normalChatReasoningEffort = "low",
            llmInactivityReconnectTimeoutSeconds = 240,
            keepTasksRunningInBackground = false,
            notifyOnTaskCompletion = false,
            agentWorkspaceMode = AgentWorkspaceMode.PerSession,
            autoCleanOldCommandHistory = false,
            oldCommandHistoryRetentionHours = 48,
            termuxSetupCompleted = true,
            termuxSetupNoticeDismissed = true,
            termuxEnvironmentVariables = listOf(TermuxEnvironmentVariable("A", "B")),
            enabledRuntimeIds = setOf(LocalRuntimeId.Termux, LocalRuntimeId.Alpine),
            defaultRuntimeId = LocalRuntimeId.Alpine,
            alpineSetupCompleted = true,
            alpinePackageProfiles = mapOf(
                "browser" to PackageProfileState("browser", installed = true),
            ),
            alpineEnvironmentVariables = listOf(AlpineEnvironmentVariable("C", "D")),
            agentModeAuthorizationEnabled = true,
            agentModeAuthorizationMethod = AgentModeAuthorizationMethod.Root,
            defaultChatModelKey = "provider:chat",
            defaultTitleModelKey = "provider:title",
            defaultNamingModelKey = "provider:naming",
            defaultCompactingModelKey = "provider:compacting",
            defaultSelectedSkillIds = listOf("review", "research"),
            onboardingSeenVersion = CurrentOnboardingVersion,
            privacyPolicyAccepted = true,
            lastUpdateCheckAtMillis = 1234L,
        )

        store.saveGeneralSettings(expected)

        assertEquals(expected, store.load().appSettings)
    }

    @Test
    fun dedicatedKeysOverrideStaleSettingsJson() = runTest {
        val stale = AppSettings(
            language = AppLanguage.English,
            themeMode = AppThemeMode.Light,
            systemPrompt = "stale prompt",
            reasoningEffort = "low",
        )
        val dataStore = InMemoryPreferencesDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("app_settings_json") to serializeAppSettings(stale),
                stringPreferencesKey("language") to AppLanguage.SimplifiedChinese.storageValue,
                stringPreferencesKey("theme_mode") to AppThemeMode.Dark.storageValue,
                stringPreferencesKey("system_prompt") to "current prompt",
                stringPreferencesKey("reasoning_effort") to "high",
            ),
        )

        val loaded = AetherSettingsStore(dataStore).load().appSettings

        assertEquals(AppLanguage.SimplifiedChinese, loaded.language)
        assertEquals(AppThemeMode.Dark, loaded.themeMode)
        assertEquals("current prompt", loaded.systemPrompt)
        assertEquals("high", loaded.reasoningEffort)
    }

    @Test
    fun freshSettingsDoNotSelectBuiltInSkills() = runTest {
        val store = AetherSettingsStore(InMemoryPreferencesDataStore())

        assertEquals(emptyList(), store.load().appSettings.defaultSelectedSkillIds)
    }

    @Test
    fun erroneousBuiltInDefaultIsRemovedWithoutErasingLaterUserChoice() = runTest {
        val injected = listOf(CreateExtensionSkillId, "review")
        val store = AetherSettingsStore(
            InMemoryPreferencesDataStore(
                mutablePreferencesOf(
                    stringPreferencesKey("app_settings_json") to serializeAppSettings(
                        AppSettings(defaultSelectedSkillIds = injected),
                    ),
                    stringPreferencesKey("default_selected_skill_ids") to
                        """["$CreateExtensionSkillId","review"]""",
                    booleanPreferencesKey("built_in_skill_defaults_initialized_v1") to true,
                ),
            ),
        )

        val repaired = store.load().appSettings
        assertEquals(listOf("review"), repaired.defaultSelectedSkillIds)
        store.saveGeneralSettings(
            repaired.copy(
                defaultSelectedSkillIds = listOf("review", CreateExtensionSkillId),
            ),
        )

        assertEquals(
            listOf("review", CreateExtensionSkillId),
            store.load().appSettings.defaultSelectedSkillIds,
        )
    }
}

private class InMemoryPreferencesDataStore(
    initial: Preferences = mutablePreferencesOf(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
