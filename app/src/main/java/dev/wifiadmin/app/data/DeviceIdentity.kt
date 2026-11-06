package dev.wifiadmin.app.data

import dev.wifiadmin.router.RouterDevice
import java.util.Locale

/**
 * The identity of a device, as far as the router lets us know it.
 *
 * ## Why the MAC, and why only the MAC
 *
 * The router exposes no stable device identifier: the fields it reports are
 * the MAC address, the IP address, the host name, the interface and the
 * lease expiry. Every one of them can change under the user's feet:
 *
 * - the **IP** changes at every DHCP renewal. It is therefore never used as
 *   an identifier, only as information.
 * - the **host name** is chosen by the device, may be empty, and is reported
 *   as the `Unknown Device` placeholder when it is missing. It is never an
 *   identifier either.
 * - the **MAC** changes only when the device randomises it.
 *
 * The MAC is therefore the primary key, and it is the **only** key: no
 * fallback tries to guess which device is which when the MAC does not
 * match.
 *
 * ## The limit this leaves
 *
 * A device that randomises its MAC on every association — the "private
 * Wi-Fi address" of modern phones — is a **new** device every time. The
 * application does not pretend otherwise: it creates a new entry, and it
 * never merges it with the previous one. The consequence is honest rather
 * than comfortable: the custom name does not follow such a device.
 *
 * The opposite risk is handled explicitly: when two devices carry the same
 * MAC, their metadata is **never** merged. See [DeviceMetadataStore].
 */
object DeviceIdentity {

    /** The name the router uses when the device has no host name. */
    const val UNKNOWN_DEVICE_NAME = "Unknown Device"

    /**
     * The canonical form of a MAC address.
     *
     * The router's format is `aa:bb:cc:dd:ee:ff`, but the value is a plain
     * string in the payload and nothing guarantees its formatting. The
     * canonical form accepts the separators that are in use — colon, dash
     * and dot — and always produces the colon-separated lower case form.
     *
     * @return the canonical MAC, or null when the value cannot be a MAC at
     *   all: empty, the literal `null`, or something that is not twelve
     *   hexadecimal digits.
     */
    fun normalizeMac(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty() || value == "null") return null

        val hex = value.filter { it != ':' && it != '-' && it != '.' }
        if (hex.length != 12) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null

        return hex.chunked(2).joinToString(":")
    }

    /**
     * The key a device's local metadata is stored under.
     *
     * @return the canonical MAC, or null when the device has no usable
     *   identity. A device without a key cannot carry a custom name: the
     *   application would have no way to find it again.
     */
    fun keyOf(device: RouterDevice): String? = normalizeMac(device.mac)

    /**
     * The key a device's local metadata is stored under.
     *
     * @return the canonical MAC, or null when the value cannot be a MAC.
     */
    fun keyOf(rawMac: String?): String? = normalizeMac(rawMac)
}
