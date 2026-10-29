package dev.wifiadmin.poc

import dev.wifiadmin.router.RouterAuthenticationException
import dev.wifiadmin.router.RouterNetworkException
import dev.wifiadmin.router.RouterProtocolException
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.ZltRouterClient
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
    val wifiFilter: Boolean
)

private const val USAGE = """
Usage: poc-cli [options]

Options:
  --host <host>        Router address (default: 192.168.1.1)
  --user <username>    Administrator username (default: root)
  --format <format>    console (default), json, or diagnostic
  --wifi-filter        Also read the Wi-Fi MAC filter (read-only, command 278)
  --yes                Skip the interactive read-only confirmation
  --help               Show this help

Formats:
  console      Human-readable list of devices.
  json         Structured JSON document (schemaVersion 1).
  diagnostic   Anonymised statistics only: no MAC, IP, hostname or SSID.

The password is read interactively and is never accepted as a command-line argument.
Only read-only commands are used: 232, 100, 223, 224, 225 and 278.
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

    val client = ZltRouterClient(settings)
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
        OutputFormat.DIAGNOSTIC -> println(
            DiagnosticRenderer.render(snapshot.devices, commands = READ_ONLY_COMMANDS)
        )
    }

    if (options.wifiFilter) {
        printWifiFilter(settings)
    }
}

/**
 * Reads the Wi-Fi MAC filter and prints an anonymised report.
 *
 * Only `GET 278` is sent, with `subcmd` `'1'` (2.4 GHz) and `'0'` (5 GHz).
 * No MAC address is ever printed.
 */
private fun printWifiFilter(settings: RouterSettings) {
    val filterClient = WifiMacFilterClient(settings)

    println()
    println("=== Filtre MAC Wi-Fi (cmd 278, lecture seule) ===")
    WifiBandFilter.entries.forEach { band ->
        try {
            val observed = filterClient.readWithStructure(band)
            println(WifiFilterRenderer.render(band, observed.state, observed.structure))
        } catch (e: WifiMacFilterException) {
            println(WifiFilterRenderer.render(band, null, emptyList(), e.message))
        }
    }
}

/**
 * The commands this POC is allowed to send.
 *
 * They are listed here rather than read from the client so that the POC keeps
 * an explicit, auditable allowlist. The client itself exposes no generic
 * `query(cmd)` entry point.
 */
private val READ_ONLY_COMMANDS = listOf(232, 100, 223, 224, 225, 278)

private fun parseOptions(args: Array<String>): CliOptions? {
    if (args.isEmpty()) {
        return CliOptions(
            host = "192.168.1.1",
            username = "root",
            format = OutputFormat.CONSOLE,
            assumeYes = false,
            wifiFilter = false
        )
    }

    var host = "192.168.1.1"
    var username = "root"
    var format = OutputFormat.CONSOLE
    var assumeYes = false
    var wifiFilter = false

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
            "--yes" -> assumeYes = true
            "--wifi-filter" -> wifiFilter = true
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
        wifiFilter = wifiFilter
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
    System.err.print("This will connect to $host and only read device data (commands 232, 100, 223, 224, 225). Continue? [y/N] ")
    System.err.flush()
    val answer = try {
        stdin.readLine()
    } catch (e: IOException) {
        null
    }
    return answer?.trim()?.lowercase() == "y"
}
