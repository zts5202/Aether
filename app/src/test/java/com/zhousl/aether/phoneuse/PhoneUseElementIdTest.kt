package com.zhousl.aether.phoneuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseElementIdTest {
    @Test fun sameFingerprintReusesEidAcrossCalls() {
        val first = PhoneUseEidAssigner().assign(sample(path = "t1:example/0"))
        val second = PhoneUseEidAssigner().assign(sample(path = "t1:example/0"))
        assertEquals(first, second)
        assertTrue(first.startsWith("e"))
        assertEquals(16, first.removePrefix("e").length)
        assertFalse(first.contains(":"))
    }

    @Test fun pathAndViewIdSeparateSimilarControls() {
        val assigner = PhoneUseEidAssigner()
        val left = assigner.assign(sample(path = "t1:example/0", viewId = "example:id/send"))
        val right = assigner.assign(sample(path = "t1:example/1", viewId = "example:id/send"))
        assertNotEquals(left, right)
    }

    @Test fun identicalFingerprintsGetStableCollisionSuffixes() {
        val again = {
            val assigner = PhoneUseEidAssigner()
            listOf(
                assigner.assign(sample(path = "t1:example/0")),
                assigner.assign(sample(path = "t1:example/0")),
            )
        }
        assertEquals(again(), again())
        val ids = again()
        assertTrue(ids[1].startsWith(ids[0] + "~"))
    }

    @Test fun editableTextDoesNotChangeTheEid() {
        val before = phoneUseElementEid(sample(text = "old"))
        val after = phoneUseElementEid(sample(text = "你好"))
        assertEquals(before, after)
    }

    @Test fun visibleLabelChangeChangesTheEid() {
        val before = phoneUseElementEid(sample(editable = false, text = "Save", description = "Save"))
        val after = phoneUseElementEid(sample(editable = false, text = "Delete", description = "Delete"))
        assertNotEquals(before, after)
    }

    private fun sample(
        path: String = "t1:example/0",
        viewId: String = "example:id/field",
        editable: Boolean = true,
        text: String = "hello",
        description: String = "hello",
    ) = phoneUseElementFingerprint(
        packageName = "example.app",
        className = "android.widget.EditText",
        viewId = viewId,
        editable = editable,
        password = false,
        text = text,
        description = description,
        hint = "hint",
        path = path,
    )
}
