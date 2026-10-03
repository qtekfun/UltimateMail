# SPDX-FileCopyrightText: 2026 UltimateMail contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# Project-specific R8 rules. Libraries in use ship their own consumer rules.

# Angus Mail loads its protocol providers and activation handlers by reflection.
-keep class org.eclipse.angus.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.security.auth.callback.**
-dontwarn org.graalvm.nativeimage.**
