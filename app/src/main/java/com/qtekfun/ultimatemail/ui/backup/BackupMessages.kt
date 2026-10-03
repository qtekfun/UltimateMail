// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import androidx.annotation.StringRes
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.backup.BackupError
import com.qtekfun.ultimatemail.domain.backup.PassphraseStrength
import com.qtekfun.ultimatemail.domain.backup.PreviewStatus

/** The message that explains why a backup could not be opened. */
@StringRes
internal fun BackupError.message(): Int = when (this) {
    BackupError.NotABackup -> R.string.backup_error_not_backup
    is BackupError.UnsupportedVersion -> R.string.backup_error_version
    BackupError.TooLarge -> R.string.backup_error_too_large
    BackupError.Truncated -> R.string.backup_error_truncated
    BackupError.UnsupportedKdf -> R.string.backup_error_kdf
    BackupError.WrongPassphraseOrDamaged -> R.string.backup_error_wrong_passphrase
    BackupError.Malformed -> R.string.backup_error_malformed
    BackupError.Unreadable -> R.string.backup_error_unreadable
}

@StringRes
internal fun PassphraseStrength.label(): Int? = when (this) {
    PassphraseStrength.NONE -> null
    PassphraseStrength.WEAK -> R.string.backup_strength_weak
    PassphraseStrength.FAIR -> R.string.backup_strength_fair
    PassphraseStrength.STRONG -> R.string.backup_strength_strong
}

@StringRes
internal fun AuthType.providerLabel(): Int = when (this) {
    AuthType.PASSWORD -> R.string.backup_provider_password
    AuthType.OAUTH_GOOGLE -> R.string.backup_provider_google
    AuthType.OAUTH_MICROSOFT -> R.string.backup_provider_microsoft
}

/** Why an account cannot be imported; null when it can. */
@StringRes
internal fun PreviewStatus.explanation(): Int? = when (this) {
    PreviewStatus.IMPORTABLE -> null
    PreviewStatus.DUPLICATE -> R.string.backup_status_duplicate
    PreviewStatus.ADDRESS_TAKEN -> R.string.backup_status_address_taken
    PreviewStatus.INVALID -> R.string.backup_status_invalid
}
