// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.Context
import com.qtekfun.ultimatemail.data.local.FileAttachmentStorage
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File

/** Where attachments are stored. The folder name must match `res/xml/file_paths.xml`. */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentModule {
    const val FOLDER = "attachments"

    @Provides
    fun attachmentStorage(@ApplicationContext context: Context): AttachmentStorage =
        FileAttachmentStorage(File(context.filesDir, FOLDER))
}
