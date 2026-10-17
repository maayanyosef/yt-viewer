package local.youtubetv.apikey

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The letters a Key page token is made of.
 *
 * Lowercase letters and digits, without `i`, `l`, `o`, `0` and `1`, because a user with a phone
 * that cannot scan types the address by hand from the television, and those five are the ones
 * that are read wrong from across a room.
 */
internal const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

/**
 * How long a token is. 31 letters to the power of 10 is about 2 to the 49, which no device on a
 * home network can guess against a server that answers one request at a time.
 */
internal const val TOKEN_LENGTH = 10

/** A new random token. One is made every time the key screen opens. */
fun newKeyPageToken(random: SecureRandom = SecureRandom()): String =
    buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
    }

/**
 * Whether [offered] is the screen's token, compared in constant time.
 *
 * `MessageDigest.isEqual` takes as long for a token wrong in its first letter as for one wrong
 * in its last, so the time of a refusal says nothing about how much of a guess was right.
 */
fun tokenMatches(offered: String, token: String): Boolean =
    token.isNotEmpty() &&
        MessageDigest.isEqual(offered.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

/**
 * Whether a connection from [address] may reach the Key page.
 *
 * Only the home network and the device itself: a private IPv4 range, a link-local address, an
 * IPv6 unique local address, or loopback. Loopback is what `adb forward` arrives as, which is
 * how the page is reached on the emulator.
 */
fun isLocalNetworkAddress(address: InetAddress): Boolean = when {
    address.isLoopbackAddress -> true
    address.isSiteLocalAddress -> true
    address.isLinkLocalAddress -> true
    address is Inet6Address -> (address.address[0].toInt() and 0xfe) == 0xfc
    else -> false
}

/** One network interface, as far as choosing an address goes. */
data class InterfaceAddresses(val name: String, val up: Boolean, val addresses: List<InetAddress>)

/**
 * The address the Key page is printed with, or null when the device is on no home network.
 *
 * A private IPv4 address of an interface that is up. A wired or wifi interface is preferred
 * over anything else, such as a VPN, because the phone has to be able to reach it.
 */
fun chooseLocalAddress(interfaces: List<InterfaceAddresses>): Inet4Address? {
    val candidates = interfaces
        .filter { it.up }
        .flatMap { iface ->
            iface.addresses.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { iface.name to it }
        }
    val preferred = candidates.firstOrNull { (name, _) ->
        name.startsWith("wlan") || name.startsWith("eth")
    }
    return (preferred ?: candidates.firstOrNull())?.second
}

/** [chooseLocalAddress] over the device's own interfaces. */
fun deviceLocalAddress(): Inet4Address? = runCatching {
    chooseLocalAddress(
        NetworkInterface.getNetworkInterfaces().toList().map { iface ->
            InterfaceAddresses(
                name = iface.name,
                up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false),
                addresses = iface.inetAddresses.toList(),
            )
        },
    )
}.getOrNull()

/** The address the QR code holds and the screen prints. */
fun keyPageUrl(host: String, port: Int, token: String): String = "http://$host:$port/$token"
