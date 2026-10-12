package com.zhousl.aether.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryPersistenceTest {
    @Test
    fun everyAppSettingSurvivesRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)
        val original = repository.settings.first()
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
            reasoningEffort = "high",
            systemPrompt = "Persist every setting",
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
            phoneUseShowCommentary = false,
            phoneUseShowEdgeGlow = false,
            language = AppLanguage.SimplifiedChinese,
            themeMode = AppThemeMode.Dark,
            defaultChatModelKey = "provider:chat",
            defaultTitleModelKey = "provider:title",
            defaultNamingModelKey = "provider:naming",
            defaultCompactingModelKey = "provider:compacting",
            defaultSelectedSkillIds = listOf("review", "research"),
            onboardingSeenVersion = CurrentOnboardingVersion,
            onboardingCompletedVersion = CurrentOnboardingVersion,
            privacyPolicyAccepted = true,
            lastUpdateCheckAtMillis = 1234L,
        )

        try {
            repository.writeAllSettings(expected)

            assertEquals(expected, SettingsRepository(context).settings.first())
        } finally {
            repository.writeAllSettings(original)
        }
    }

    @Test
    fun criticalSelectionsSurviveRepositoryRecreationAndUnrelatedWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)
        val original = repository.settings.first()

        try {
            repository.updateDefaultModelKeys(
                chat = "provider:chat",
                title = "provider:title",
                naming = "provider:naming",
                compacting = "provider:compacting",
            )
            repository.updateAgentModeAuthorization(
                enabled = true,
                method = AgentModeAuthorizationMethod.Root,
            )

            // A general-settings snapshot may still contain older critical fields.
            repository.updateUserSettings(
                original.copy(
                    systemPrompt = "persisted instructions",
                    agentModeAuthorizationEnabled = false,
                    agentModeAuthorizationMethod = AgentModeAuthorizationMethod.Shizuku,
                    defaultChatModelKey = "",
                ),
            )

            val reloaded = SettingsRepository(context).settings.first()
            assertEquals("persisted instructions", reloaded.systemPrompt)
            assertEquals("provider:chat", reloaded.defaultChatModelKey)
            assertEquals("provider:title", reloaded.defaultTitleModelKey)
            assertEquals("provider:naming", reloaded.defaultNamingModelKey)
            assertEquals("provider:compacting", reloaded.defaultCompactingModelKey)
            assertTrue(reloaded.agentModeAuthorizationEnabled)
            assertEquals(AgentModeAuthorizationMethod.Root, reloaded.agentModeAuthorizationMethod)
        } finally {
            repository.updateSettings(original)
            repository.updateDefaultModelKeys(
                original.defaultChatModelKey,
                original.defaultTitleModelKey,
                original.defaultNamingModelKey,
                original.defaultCompactingModelKey,
            )
            repository.updateAgentModeAuthorization(
                original.agentModeAuthorizationEnabled,
                original.agentModeAuthorizationMethod,
            )
        }
    }

    private suspend fun SettingsRepository.writeAllSettings(settings: AppSettings) {
        updateSettings(settings)
        updateUserSettings(settings)
        updateAgentModeAuthorization(
            settings.agentModeAuthorizationEnabled,
            settings.agentModeAuthorizationMethod,
        )
        updatePhoneUseVisualFeedback(settings.phoneUseShowCommentary, settings.phoneUseShowEdgeGlow)
        updateDefaultModelKeys(
            settings.defaultChatModelKey,
            settings.defaultTitleModelKey,
            settings.defaultNamingModelKey,
            settings.defaultCompactingModelKey,
        )
        updateDefaultSelectedSkillIds(settings.defaultSelectedSkillIds)
        updateOnboardingSeenVersion(settings.onboardingSeenVersion)
        updateOnboardingCompletedVersion(settings.onboardingCompletedVersion)
        updatePrivacyPolicyAccepted(settings.privacyPolicyAccepted)
        updateLastUpdateCheckAtMillis(settings.lastUpdateCheckAtMillis)
    }
}
