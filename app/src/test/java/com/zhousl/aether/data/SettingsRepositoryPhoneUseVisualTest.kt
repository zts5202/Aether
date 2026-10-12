package com.zhousl.aether.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SettingsRepositoryPhoneUseVisualTest {
    @Test fun visualFeedbackDefaultsOnAndRoundTrips() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = SettingsRepository(context)
        val original = repository.settings.first()
        try {
            clearVisualFeedbackKeys(context)
            val defaults = repository.settings.first()
            assertTrue(defaults.phoneUseShowCommentary)
            assertTrue(defaults.phoneUseShowEdgeGlow)

            repository.updatePhoneUseVisualFeedback(showCommentary = false, showEdgeGlow = false)
            val hidden = SettingsRepository(context).settings.first()
            assertFalse(hidden.phoneUseShowCommentary)
            assertFalse(hidden.phoneUseShowEdgeGlow)

            repository.updatePhoneUseVisualFeedback(showCommentary = false, showEdgeGlow = true)
            val edgeOnly = repository.settings.first()
            assertFalse(edgeOnly.phoneUseShowCommentary)
            assertTrue(edgeOnly.phoneUseShowEdgeGlow)

            repository.updatePhoneUseVisualFeedback(showCommentary = true, showEdgeGlow = false)
            val bubbleOnly = repository.settings.first()
            assertTrue(bubbleOnly.phoneUseShowCommentary)
            assertFalse(bubbleOnly.phoneUseShowEdgeGlow)
        } finally {
            repository.updatePhoneUseVisualFeedback(original.phoneUseShowCommentary, original.phoneUseShowEdgeGlow)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun clearVisualFeedbackKeys(context: Context) {
        val getter = Class.forName("com.zhousl.aether.data.SettingsRepositoryKt")
            .declaredMethods
            .first { it.name == "getDataStore" && it.parameterTypes.contentEquals(arrayOf(Context::class.java)) }
        getter.isAccessible = true
        val store = getter.invoke(null, context) as DataStore<Preferences>
        store.edit {
            it.remove(booleanPreferencesKey("phone_use_show_commentary"))
            it.remove(booleanPreferencesKey("phone_use_show_edge_glow"))
        }
    }
}
