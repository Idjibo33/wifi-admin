package dev.wifiadmin.poc

import dev.wifiadmin.router.OkHttpRouterTransport
import dev.wifiadmin.router.RouterAuthenticationException
import dev.wifiadmin.router.RouterNetworkException
import dev.wifiadmin.router.RouterProtocolException
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.RouterTransport
import dev.wifiadmin.router.ZltRouterClient
import dev.wifiadmin.router.firewall.FirewallMacFilterClient
import dev.wifiadmin.router.wifi.WifiBandFilter
import dev.wifiadmin.router.wifi.WifiMacFilterClient
import dev.wifiadmin.router.wifi.WifiMacFilterException
import java.io.EOFException
import java.io.IOException
import kotlin.system.exitProcess

private enum class OutputFormat(val option: String) {
    CONSOLE("console"),
    JSON("json"),
    DIAGNOSTIC("diagnostic");

    companion object {
        fun fromOption(option: String): OutputFormat? = entries.firstOrNull { it.option == option }
    }
}

private class CliOptions(
    val host: String,
    val username: String,
    val format: OutputFormat,
    val assumeYes: Boolean,
    val wifiFilter: Boolean,
    val firewallFilter: Boolean,
    val firewallVariant: FirewallVariant
)

/**
 * The shape of the firewall read.
 *
 * The variants exist because the firmware answered `GET 23` and `GET 30`
 * without any payload. Each variant changes **one** thing, so a result can
 * be attributed to that single change.
 */
enum class FirewallVariant {
    /** The commands as they were sent in the first place. */
    DEFAULT,

    /** Adds the `getfun: true` flag to `GET 23`. */
    GETFUN,

    /** Reads the default policy with `GET 28` instead of `GET 30`. */
    CMD_28,
}

private const val USAGE = """
Usage: poc-cli [options]

Options:
  --host <host>        Router address (default: 192.168.1.1)
  --user <username>    Administrator username (default: root)
  --format <format>    console (default), json, or diagnostic
  --wifi-filter        Also read the Wi-Fi MAC filter (read-only, command 278)
  --firewall-filter    Also read the firewall MAC filter (read-only, commands 23 and 30)
  --firewall-variant <v>
                       Shape of the firewall read: default, getfun or cmd28.
                       Each variant changes one thing only (read-only).
  --yes                Skip the interactive read-only confirmation
  --help               Show this help

Formats:
  console      Human-readable list of devices.
  json         Structured JSON document (schemaVersion 1).
  diagnostic   Anonymised statistics only: no MAC, IP, hostname or SSID.

The password is read interactively and is never accepted as a command-line argument.
Only read-only commands are used: 232, 100, 223, 224, 225, 278, 23 and 30.
No write command (20, 233) is ever sent.

The diagnostic lists the commands this run really sent. A command that is
possible but was not used is not listed.
"""

/**
 * A single reader over stdin.
 *
 * Creating a second `bufferedReader()` on the same stream would consume the
 * buffered bytes of the first one, so the password prompt and the confirmation
 * prompt have to share this instance.
 */
private val stdin = System.`in`.bufferedReader()

fun main(args: Array<String>) {
    val options = parseOptions(args)
    if (options == null) {
        // The usage goes to stderr so that stdout only ever carries the result.
        System.err.println(USAGE.trim())
        exitProcess(if (args.any { it == "--help" }) 0 else 2)
    }

    val password = readPassword()
    if (password == null) {
        System.err.println("Error: no password supplied.")
        exitProcess(2)
    }

    if (!options.assumeYes && !confirm(options.host)) {
        System.err.println("Aborted. No request was sent to the router.")
        exitProcess(0)
    }

    val settings = object : RouterSettings {
        override val routerHost = options.host
        override val username = options.username
        override val password: String = password
    }

    // The journal records what is really sent, so the diagnostic never
    // announces a command that was not used.
    val journal = CommandJournal()
    val transport = RecordingTransport(OkHttpRouterTransport(), journal)

    val client = ZltRouterClient(settings, transport = transport)
    val snapshot = try {
        client.fetchDevices()
    } catch (e: RouterAuthenticationException) {
        System.err.println("Error: authentication failed (${e.message}).")
        exitProcess(3)
    } catch (e: RouterNetworkException) {
        System.err.println("Error: cannot reach the router at ${options.host} (${e.message}).")
        exitProcess(4)
    } catch (e: RouterProtocolException) {
        System.err.println("Error: unexpected router response (${e.message}).")
        exitProcess(5)
    }

    // Only the result goes to stdout, so `--format json` can be piped or
    // redirected to a file without any prompt polluting the document.
    when (options.format) {
        OutputFormat.CONSOLE -> println(ConsoleRenderer.render(snapshot.devices))
        OutputFormat.JSON -> println(JsonRenderer.render(snapshot.devices))
        OutputFormat.DIAGNOSTIC -> Unit
    }

    if (options.wifiFilter) {
        printWifiFilter(settings, transport)
    }

    if (options.firewallFilter) {
        printFirewallFilter(client, options.firewallVariant)
    }

    // The diagnostic comes last: it then lists every command this run
    // really sent, including the ones sent by the optional sections.
    if (options.format == OutputFormat.DIAGNOSTIC) {
        println()
        println(
            DiagnosticRenderer.render(
                snapshot.devices,
                commands = journal.commands(),
                commandPairs = journal.commandPairs(),
                commandCounts = journal.commandsWithCounts(),
                payloadAbsent = snapshot.payloadAbsent,
            )
        )
    }
}

/**
 * Reads the Wi-Fi MAC filter and prints an anonymised report.
 *
 * Only `GET 278` is sent, with `subcmd` `'1'` (2.4 GHz) and `'0'` (5 GHz).
 * No MAC address is ever printed.
 */
private fun printWifiFilter(settings: RouterSettings, transport: RouterTransport) {
    val filterClient = WifiMacFilterClient(settings, transport = transport)

    println()
    println("=== Filtre MAC Wi-Fi (cmd 278, lecture seule) ===")
    WifiBandFilter.entries.forEach { band ->
        try {
            val observed = filterClient.readWithStructure(band)
            println(WifiFilterRenderer.render(band, observed.state, observed.structure))
        } catch (e: WifiMacFilterException) {
            println(
                WifiFilterRenderer.render(
                    band,
                    null,
                    emptyList(),
                    WifiFilterError.OTHER,
                )
            )
        }
    }
}

/**
 * Reads the firewall MAC filter and prints an anonymised report.
 *
 * Only `GET 23` and `GET 30` are sent, on the session already opened by the
 * device read: no second login, no write token, no apply command.
 *
 * No MAC address and no remark is ever printed.
 */
private fun printFirewallFilter(client: ZltRouterClient, variant: FirewallVariant) {
    println()
    println("=== Pare-feu MAC (cmd 23 / 30, lecture seule) ===")
    val filterClient = FirewallMacFilterClient(client)

    // The two reads are independent: a failure on one must not hide the
    // other.
    val rules = runCatching { filterClient.readRules(getfun = variant == FirewallVariant.GETFUN) }
    val defaults = runCatching {
        filterClient.readDefaults(
            command = if (variant == FirewallVariant.CMD_28) {
                FirewallMacFilterClient.CMD_DEFAULT_FILTER
            } else {
                FirewallMacFilterClient.CMD_MAC_DEFAULT_FILTER
            }
        )
    }

    println(FirewallFilterRenderer.renderRules(rules.getOrNull(), rules.exceptionOrNull()))
    println()
    println(FirewallFilterRenderer.renderDefaults(defaults.getOrNull(), defaults.exceptionOrNull()))
}

/**
 * The commands this POC is **allowed** to send.
 *
 * This is an audit list, not a history: it says what the POC may do, not
 * what a given run did. The commands a run really sent are collected by the
 * [CommandJournal] and printed by the diagnostic renderer.
 *
 * It is listed here rather than read from the client so that the POC keeps
 * an explicit, auditable allowlist. The client itself exposes no generic
 * `query(cmd)` entry point.
 */
internal val DECLARED_READ_ONLY_COMMANDS = listOf(
    232, 100, 223, 224, 225, 278,
    // The firewall MAC filter and its default policy. Command 28 is the
    // older single-entry variant, used only by `--firewall-variant cmd28`.
    23, 28, 30,
)

private fun parseOptions(args: Array<String>): CliOptions? {
    if (args.isEmpty()) {
        return CliOptions(
            host = "192.168.1.1",
            username = "root",
            format = OutputFormat.CONSOLE,
            assumeYes = false,
            wifiFilter = false,
            firewallFilter = false,
            firewallVariant = FirewallVariant.DEFAULT
        )
    }

    var host = "192.168.1.1"
    var username = "root"
    var format = OutputFormat.CONSOLE
    var assumeYes = false
    var wifiFilter = false
    var firewallFilter = false
    var firewallVariant = FirewallVariant.DEFAULT

    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--host" -> {
                host = args.getOrNull(++index) ?: return null
            }
            "--user" -> {
                username = args.getOrNull(++index) ?: return null
            }
            "--format" -> {
                val value = args.getOrNull(++index) ?: return null
                format = OutputFormat.fromOption(value) ?: return null
            }
            "--firewall-variant" -> {
                val value = args.getOrNull(++index) ?: return null
                firewallVariant = when (value.lowercase()) {
                    "default" -> FirewallVariant.DEFAULT
                    "getfun" -> FirewallVariant.GETFUN
                    "cmd28" -> FirewallVariant.CMD_28
                    else -> return null
                }
            }
            "--yes" -> assumeYes = true
            "--wifi-filter" -> wifiFilter = true
            "--firewall-filter" -> firewallFilter = true
            "--help" -> return null
            else -> return null
        }
        index++
    }

    return CliOptions(
        host = host,
        username = username,
        format = format,
        assumeYes = assumeYes,
        wifiFilter = wifiFilter,
        firewallFilter = firewallFilter,
        firewallVariant = firewallVariant
    )
}

/**
 * Reads the password without writing it to the shell history.
 *
 * On a real terminal the echo is switched off so the password is not shown on
 * screen. When stdin is a pipe (tests, scripted runs) the terminal is not
 * touched at all.
 */
private fun readPassword(): String? {
    // The prompt goes to stderr: stdout is reserved for the result, so the JSON
    // output stays a valid, pipeable document.
    System.err.print("Router password (not echoed): ")
    System.err.flush()
    val terminal = disableTerminalEcho()
    return try {
        val password = stdin.readLine()
        System.err.println()
        password
    } catch (e: IOException) {
        null
    } catch (e: EOFException) {
        null
    } finally {
        restoreTerminalEcho(terminal)
    }
}

/**
 * Returns true when the echo was switched off and has to be restored.
 *
 * A shutdown hook is registered as well: a `finally` block does not run when
 * the process is killed with Ctrl-C, and the shell would be left with the echo
 * disabled, making everything typed afterwards invisible.
 */
private fun disableTerminalEcho(): Boolean {
    // No console means stdin is a pipe: the terminal must not be touched.
    if (System.console() == null) return false
    return try {
        val stty = ProcessBuilder("stty", "-echo").redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val disabled = stty.waitFor() == 0
        if (disabled) {
            Runtime.getRuntime().addShutdownHook(Thread { restoreTerminalEcho(true) })
        }
        disabled
    } catch (e: Exception) {
        false
    }
}

private fun restoreTerminalEcho(disabled: Boolean) {
    if (!disabled) return
    try {
        ProcessBuilder("stty", "echo")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
    } catch (e: Exception) {
        // The terminal is restored by the shell when the process exits anyway.
    }
}

private fun confirm(host: String): Boolean {
    System.err.print(
        "This will connect to $host and only read data " +
            "(commands 232, 100, 223, 224, 225, and optionally 278, 23, 30). Continue? [y/N] "
    )
    System.err.flush()
    val answer = try {
        stdin.readLine()
    } catch (e: IOException) {
        null
    }
    return answer?.trim()?.lowercase() == "y"
}
