package com.zhousl.aether.phoneuse

import java.security.MessageDigest

/**
 * Stable element id for one interactive node.
 *
 * The id is a hash of package, class, resource id, a text fingerprint, and the
 * structural path. It does not include the observation snapshot. Collisions in
 * one tree get a numeric suffix in document order so the same tree yields the
 * same ids on the next observe.
 */
internal data class PhoneUseElementFingerprint(
    val packageName: String,
    val className: String,
    val viewId: String,
    val textFingerprint: String,
    val path: String,
)

internal fun phoneUseTextFingerprint(
    editable: Boolean,
    password: Boolean,
    text: String,
    description: String,
    hint: String,
): String = when {
    password -> "password"
    // Live input values change while the field stays the same control.
    editable -> "editable:" + hint.trim().take(80)
    else -> text.trim().take(80) + "\u001f" + description.trim().take(80)
}

internal fun phoneUseElementFingerprint(
    packageName: String,
    className: String,
    viewId: String,
    editable: Boolean,
    password: Boolean,
    text: String,
    description: String,
    hint: String,
    path: String,
): PhoneUseElementFingerprint = PhoneUseElementFingerprint(
    packageName = packageName,
    className = className,
    viewId = viewId,
    textFingerprint = phoneUseTextFingerprint(editable, password, text, description, hint),
    path = path,
)

internal fun phoneUseElementEid(fingerprint: PhoneUseElementFingerprint): String {
    val material = listOf(
        fingerprint.packageName,
        fingerprint.className,
        fingerprint.viewId,
        fingerprint.textFingerprint,
        fingerprint.path,
    ).joinToString("\u001e")
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return "e" + digest.take(8).joinToString("") { "%02x".format(it) }
}

internal class PhoneUseEidAssigner {
    private val seen = HashMap<String, Int>()

    fun assign(fingerprint: PhoneUseElementFingerprint): String {
        val base = phoneUseElementEid(fingerprint)
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        return if (count == 1) base else "$base~$count"
    }
}

internal const val PhoneUseElementIdMigration =
    "node_id is a deprecated alias of eid. Pass eid from the latest tree. Snapshot ids of the form {snapshot}:{index} are no longer issued."
