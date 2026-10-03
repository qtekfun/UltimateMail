// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.DisplayDensity
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.SwipeActions
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.account.ServerEndpoint
import java.time.Instant

/** The outcome of [BackupCodec.decode]. */
sealed interface DecodeResult {
    data class Decoded(val document: BackupDocument) : DecodeResult

    data class Failed(val error: BackupError) : DecodeResult
}

/**
 * Turns a [BackupDocument] into the JSON of the file and back (RF-12). Reading checks the shape
 * and size of every field, because the file is untrusted input; whether an address or a host
 * makes sense is judged later, per account, by the importer (one odd account must not block
 * the others).
 */
@Suppress("TooManyFunctions") // One small reader or writer per kind of field.
object BackupCodec {
    private const val MARKER = "ultimatemail-backup"
    private const val MAX_ACCOUNTS = 50
    private const val MAX_FOLDERS = 2_000
    private const val MAX_PATH = 512
    private const val MAX_EMAIL = 254
    private const val MAX_NAME = 100
    private const val MAX_HOST = 253
    private const val MAX_SIGNATURE = 4_000
    private const val MAX_VERSION_TEXT = 40
    private const val MAX_CLIENT_ID = 200
    private const val MAX_PASSWORD = 1_024
    private const val MAX_TOKEN = 8_192
    private const val MAX_OFFLINE_DAYS = 36_500
    private const val MAX_EPOCH_SECOND = 4_102_444_800L

    fun encode(document: BackupDocument): ByteArray = BackupJson.write(toJson(document))
        .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): DecodeResult = try {
        val root = BackupJson.parse(String(bytes, Charsets.UTF_8)) as? JsonValue.Obj
            ?: fail()
        if (root.string("format", MAX_VERSION_TEXT) != MARKER) fail()
        val version = root.int("formatVersion")
        if (version > BackupFormat.VERSION) {
            DecodeResult.Failed(BackupError.UnsupportedVersion(version))
        } else if (version < 1) {
            DecodeResult.Failed(BackupError.Malformed)
        } else {
            DecodeResult.Decoded(readDocument(root, version))
        }
    } catch (_: JsonFormatException) {
        DecodeResult.Failed(BackupError.Malformed)
    } catch (_: BadBackup) {
        DecodeResult.Failed(BackupError.Malformed)
    }

    // ---- writing ----

    private fun toJson(document: BackupDocument): JsonValue {
        val fields = linkedMapOf<String, JsonValue>(
            "format" to JsonValue.Str(MARKER),
            "formatVersion" to JsonValue.Num(document.formatVersion.toLong()),
            "appVersion" to JsonValue.Str(document.appVersion),
            "accounts" to JsonValue.Arr(document.accounts.map(::accountJson))
        )
        document.settings?.let { fields["settings"] = settingsJson(it) }
        return JsonValue.Obj(fields)
    }

    private fun accountJson(account: BackupAccount): JsonValue {
        val fields = linkedMapOf<String, JsonValue>(
            "email" to JsonValue.Str(account.email),
            "displayName" to JsonValue.Str(account.displayName),
            "username" to JsonValue.Str(account.username),
            "authType" to JsonValue.Str(account.authType.name),
            "imap" to endpointJson(account.imap),
            "smtp" to endpointJson(account.smtp),
            "signature" to JsonValue.Str(account.signature),
            "signatureEnabled" to JsonValue.Bool(account.signatureEnabled),
            "signatureBeforeQuote" to JsonValue.Bool(account.signatureBeforeQuote),
            "offlineWindowDays" to (
                account.offlineWindowDays?.let { JsonValue.Num(it.toLong()) } ?: JsonValue.Null
                ),
            "downloadForOffline" to JsonValue.Bool(account.downloadForOffline),
            "folders" to JsonValue.Arr(
                account.folders.map { (path, sync) ->
                    JsonValue.Obj(
                        mapOf("path" to JsonValue.Str(path), "sync" to JsonValue.Bool(sync))
                    )
                }
            )
        )
        account.oauthClientId?.let { fields["oauthClientId"] = JsonValue.Str(it) }
        account.credentials?.let { fields["credentials"] = credentialsJson(it) }
        return JsonValue.Obj(fields)
    }

    private fun endpointJson(endpoint: ServerEndpoint): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "host" to JsonValue.Str(endpoint.host),
            "port" to JsonValue.Num(endpoint.port.toLong()),
            "security" to JsonValue.Str(endpoint.security.name)
        )
    )

    private fun credentialsJson(credentials: AccountCredentials): JsonValue {
        val fields = linkedMapOf<String, JsonValue>()
        credentials.password?.let { fields["password"] = JsonValue.Str(it) }
        credentials.oauth?.let { tokens ->
            val oauth = linkedMapOf<String, JsonValue>(
                "accessToken" to JsonValue.Str(tokens.accessToken)
            )
            tokens.refreshToken?.let { oauth["refreshToken"] = JsonValue.Str(it) }
            tokens.expiresAt?.let { oauth["expiresAt"] = JsonValue.Num(it.epochSecond) }
            fields["oauth"] = JsonValue.Obj(oauth)
        }
        return JsonValue.Obj(fields)
    }

    private fun settingsJson(settings: AppSettings): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "theme" to JsonValue.Str(settings.theme.name),
            "dynamicColor" to JsonValue.Bool(settings.dynamicColor),
            "amoled" to JsonValue.Bool(settings.amoled),
            "density" to JsonValue.Str(settings.density.name),
            "swipeRight" to JsonValue.Str(settings.swipe.right.name),
            "swipeLeft" to JsonValue.Str(settings.swipe.left.name),
            "remoteContent" to JsonValue.Str(settings.remoteContent.name)
        )
    )

    // ---- reading ----

    private class BadBackup : Exception()

    private fun fail(): Nothing = throw BadBackup()

    private fun readDocument(root: JsonValue.Obj, version: Int): BackupDocument {
        val accounts = root.array("accounts")
        if (accounts.size > MAX_ACCOUNTS) fail()
        return BackupDocument(
            formatVersion = version,
            appVersion = root.string("appVersion", MAX_VERSION_TEXT),
            accounts = accounts.map { readAccount(it as? JsonValue.Obj ?: fail()) },
            settings = root.optionalObject("settings")?.let(::readSettings)
        )
    }

    private fun readAccount(obj: JsonValue.Obj): BackupAccount = BackupAccount(
        email = obj.string("email", MAX_EMAIL),
        displayName = obj.string("displayName", MAX_NAME, default = ""),
        username = obj.string("username", MAX_EMAIL),
        authType = obj.enum<AuthType>("authType"),
        imap = readEndpoint(obj.obj("imap")),
        smtp = readEndpoint(obj.obj("smtp")),
        signature = obj.string("signature", MAX_SIGNATURE, default = ""),
        signatureEnabled = obj.bool("signatureEnabled", default = true),
        signatureBeforeQuote = obj.bool("signatureBeforeQuote", default = true),
        offlineWindowDays = readOfflineDays(obj),
        downloadForOffline = obj.bool("downloadForOffline", default = true),
        folders = readFolders(obj),
        oauthClientId = obj.optionalString("oauthClientId", MAX_CLIENT_ID),
        credentials = obj.optionalObject("credentials")?.let(::readCredentials)
    )

    private fun readOfflineDays(obj: JsonValue.Obj): Int? {
        val value = obj.fields["offlineWindowDays"] ?: return DEFAULT_OFFLINE_DAYS
        return when (value) {
            JsonValue.Null -> null

            is JsonValue.Num -> value.value.takeIf { it in 1..MAX_OFFLINE_DAYS }?.toInt()
                ?: fail()

            else -> fail()
        }
    }

    private fun readFolders(obj: JsonValue.Obj): Map<String, Boolean> {
        val list = if ("folders" in obj.fields) obj.array("folders") else return emptyMap()
        if (list.size > MAX_FOLDERS) fail()
        val folders = linkedMapOf<String, Boolean>()
        for (item in list) {
            val folder = item as? JsonValue.Obj ?: fail()
            val path = folder.string("path", MAX_PATH)
            if (path.isEmpty() || path.any { it.isISOControl() } || path in folders) {
                fail()
            }
            folders[path] = folder.bool("sync")
        }
        return folders
    }

    private fun readEndpoint(obj: JsonValue.Obj) = ServerEndpoint(
        host = obj.string("host", MAX_HOST),
        port = obj.int("port"),
        security = obj.enum<ConnectionSecurity>("security")
    )

    private fun readCredentials(obj: JsonValue.Obj): AccountCredentials = AccountCredentials(
        password = obj.optionalString("password", MAX_PASSWORD),
        oauth = obj.optionalObject("oauth")?.let { oauth ->
            OAuthTokens(
                accessToken = oauth.string("accessToken", MAX_TOKEN),
                refreshToken = oauth.optionalString("refreshToken", MAX_TOKEN),
                expiresAt = oauth.optionalLong("expiresAt")?.let {
                    if (it !in 0..MAX_EPOCH_SECOND) fail()
                    Instant.ofEpochSecond(it)
                }
            )
        }
    )

    /** Settings are the one place where an unknown value falls back to the default: a newer app
     * may know choices this one does not, and that must not block restoring everything else. */
    private fun readSettings(obj: JsonValue.Obj): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = obj.lenientEnum("theme", defaults.theme),
            dynamicColor = obj.bool("dynamicColor", defaults.dynamicColor),
            amoled = obj.bool("amoled", defaults.amoled),
            density = obj.lenientEnum<DisplayDensity>("density", defaults.density),
            swipe = SwipeActions(
                right = obj.lenientEnum<SwipeAction>("swipeRight", defaults.swipe.right),
                left = obj.lenientEnum<SwipeAction>("swipeLeft", defaults.swipe.left)
            ),
            remoteContent = obj.lenientEnum<RemoteContentPolicy>(
                "remoteContent",
                defaults.remoteContent
            )
        )
    }

    private const val DEFAULT_OFFLINE_DAYS = 90

    private fun JsonValue.Obj.string(key: String, max: Int, default: String? = null): String {
        val value = fields[key] ?: return default ?: fail()
        val text = (value as? JsonValue.Str)?.value ?: fail()
        if (text.length > max) fail()
        return text
    }

    private fun JsonValue.Obj.optionalString(key: String, max: Int): String? =
        when (val value = fields[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> value.value.takeIf { it.length <= max } ?: fail()
            else -> fail()
        }

    private fun JsonValue.Obj.bool(key: String, default: Boolean? = null): Boolean {
        val value = fields[key] ?: return default ?: fail()
        return (value as? JsonValue.Bool)?.value ?: fail()
    }

    private fun JsonValue.Obj.int(key: String): Int {
        val value = (fields[key] as? JsonValue.Num)?.value ?: fail()
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) fail()
        return value.toInt()
    }

    private fun JsonValue.Obj.optionalLong(key: String): Long? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.value
        else -> fail()
    }

    private fun JsonValue.Obj.obj(key: String): JsonValue.Obj =
        fields[key] as? JsonValue.Obj ?: fail()

    private fun JsonValue.Obj.optionalObject(key: String): JsonValue.Obj? =
        when (val value = fields[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Obj -> value
            else -> fail()
        }

    private fun JsonValue.Obj.array(key: String): List<JsonValue> =
        (fields[key] as? JsonValue.Arr)?.items ?: fail()

    private inline fun <reified T : Enum<T>> JsonValue.Obj.enum(key: String): T {
        val name = string(key, MAX_VERSION_TEXT)
        return enumValues<T>().firstOrNull { it.name == name } ?: fail()
    }

    private inline fun <reified T : Enum<T>> JsonValue.Obj.lenientEnum(key: String, default: T): T {
        val name = (fields[key] as? JsonValue.Str)?.value
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
