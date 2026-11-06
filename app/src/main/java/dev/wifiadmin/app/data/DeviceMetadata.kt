package dev.wifiadmin.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * The local metadata of one device.
 *
 * It is **local** data: the router knows nothing about it, and it is never
 * sent to the router.
 *
 * @property key the identity of the device, the canonical MAC address.
 * @property customName the name the user gave the device, or null when the
 *   device has none. It is never empty: an empty name means "no name".
 * @property lastSeenMillis when the device was **last observed** by a
 *   confirmed read, in epoch milliseconds, or null when it never was.
 * @property lastIp the address the device had at that observation. It is an
 *   **attribute**, never an identity: the address changes at every DHCP
 *   renewal.
 * @property lastInterface the interface the device was seen on.
 */
data class DeviceMetadata(
    val key: String,
    val customName: String? = null,
    val lastSeenMillis: Long? = null,
    val lastIp: String? = null,
    val lastInterface: String? = null,
) {

    /** True when the user gave this device a name. */
    val hasCustomName: Boolean get() = !customName.isNullOrBlank()

    /**
     * The name to show for this device.
     *
     * The custom name comes first, then the router's own name. When the
     * router reported no name, a readable French placeholder is used: the
     * English "Unknown Device" of the protocol layer is not a product
     * wording.
     */
    fun displayName(routerName: String?): String {
        if (hasCustomName) return customName!!.trim()

        val original = routerName?.trim().orEmpty()
        return if (original.isEmpty() || original == DeviceIdentity.UNKNOWN_DEVICE_NAME) {
            UNNAMED_DEVICE_LABEL
        } else {
            original
        }
    }

    companion object {
        /** The placeholder shown for a device the router did not name. */
        const val UNNAMED_DEVICE_LABEL = "Appareil sans nom"
    }
}

/**
 * The validation of a name the user typed.
 *
 * ## Why a name is validated at all
 *
 * A custom name is a piece of text the application displays. It is never
 * interpreted, but it must not be able to **mislead**:
 *
 * - a **control character** can break the layout, and an escape sequence can
 *   disturb a terminal or a log;
 * - a **bidirectional override** (`U+202E`) reverses the displayed text: a
 *   name could be read backwards, which is a visual impersonation;
 * - an **unbounded length** breaks the list.
 *
 * The normalisation is applied when the name is **stored**, not when it is
 * displayed: normalising only at display time would leave the dirty value
 * on the disk.
 */
object CustomName {

    /** The longest name that is stored. */
    const val MAX_LENGTH = 64

    /**
     * The characters that are removed from a name.
     *
     * They are removed rather than the whole name being rejected: a name
     * that contains a newline is still a name the user wants.
     */
    private val FORBIDDEN = buildSet {
        // C0 controls and DEL.
        for (code in 0x00..0x1F) add(code.toChar())
        add(0x7F.toChar())
        // C1 controls.
        for (code in 0x80..0x9F) add(code.toChar())
        // Bidirectional formatting: they can reverse the displayed text.
        for (code in 0x200E..0x200F) add(code.toChar())
        for (code in 0x202A..0x202E) add(code.toChar())
        for (code in 0x2066..0x2069) add(code.toChar())
        // The rest of the Unicode categories that carry no visible text.
        // The bidi ones are already listed above; these are the remaining
        // formatting characters (the Arabic letter mark, the zero-width
        // spaces, the byte order mark) and the line separators, which would
        // break the layout of a single-line text.
        for (code in 0x206A..0x206F) add(code.toChar())
        add(0x061C.toChar())
        add(0x200B.toChar())
        add(0x200C.toChar())
        add(0x200D.toChar())
        add(0xFEFF.toChar())
        add(0x2028.toChar())
        add(0x2029.toChar())
        add(0x202F.toChar())
        add(0x2060.toChar())
    }

    /**
     * The canonical form of a name the user typed.
     *
     * The text is normalised to NFC, so that the composed and the
     * decomposed forms of an accented letter are the same name — otherwise
     * the search would not find it and the file would carry two entries.
     * The forbidden characters are removed, and the result is trimmed and
     * truncated.
     *
     * @return the name to store, or an empty string when nothing usable is
     *   left. An empty name means "the user removed the custom name".
     */
    fun sanitize(raw: String?): String {
        if (raw == null) return ""

        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val cleaned = buildString(normalized.length) {
            for (character in normalized) {
                if (character !in FORBIDDEN) append(character)
            }
        }
        return cleaned.trim().take(MAX_LENGTH)
    }

    /**
     * Whether a name can be stored.
     *
     * @return true when the name is usable, false when it is empty or only
     *   made of forbidden characters.
     */
    fun isValid(raw: String?): Boolean = sanitize(raw).isNotEmpty()

    /**
     * The comparison used by the search.
     *
     * The needle is normalised the same way as the stored names, so the
     * search finds a name whatever the way it was typed.
     */
    fun matches(candidate: String?, needle: String): Boolean {
        if (needle.isBlank()) return true
        val haystack = sanitize(candidate).lowercase(Locale.ROOT)
        val normalizedNeedle = sanitize(needle).lowercase(Locale.ROOT)
        if (normalizedNeedle.isEmpty()) return true
        return haystack.contains(normalizedNeedle)
    }
}
