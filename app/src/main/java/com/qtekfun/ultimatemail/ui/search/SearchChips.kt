// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.search.DateFilter
import com.qtekfun.ultimatemail.domain.search.SearchScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val MinTouchTarget = 48.dp

/** The row of chips under the search field: where to search, then what to keep. */
@Composable
fun SearchChips(state: SearchState, actions: SearchActions, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.scopeChoices.size > 1) {
            items(state.scopeChoices, key = { it.key }) { choice ->
                FilterChip(
                    selected = choice == state.scope,
                    onClick = { actions.onScope(choice) },
                    label = { Text(stringResource(choice.labelRes())) },
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                )
            }
        }
        item(key = "unread") {
            FilterChip(
                selected = state.filters.unread,
                onClick = actions.onToggleUnread,
                label = { Text(stringResource(R.string.search_filter_unread)) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            )
        }
        item(key = "starred") {
            FilterChip(
                selected = state.filters.starred,
                onClick = actions.onToggleStarred,
                label = { Text(stringResource(R.string.search_filter_starred)) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            )
        }
        item(key = "attachments") {
            FilterChip(
                selected = state.filters.withAttachments,
                onClick = actions.onToggleAttachments,
                label = { Text(stringResource(R.string.search_filter_attachments)) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            )
        }
        item(key = "date") { DateChip(state.filters.date, actions.onDate) }
    }
}

private fun SearchScope.labelRes(): Int = when (this) {
    is SearchScope.Folder -> R.string.search_scope_folder
    is SearchScope.Account -> R.string.search_scope_account
    SearchScope.AllAccounts -> R.string.search_scope_all
}

/** The date chip: a menu of presets and a custom range chosen with Material's range picker. */
@Composable
private fun DateChip(date: DateFilter, onDate: (DateFilter) -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = date != DateFilter.AnyTime,
            onClick = { menuOpen = true },
            label = { Text(dateLabel(date)) },
            modifier = Modifier.heightIn(min = MinTouchTarget)
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val presets = listOf(
                DateFilter.AnyTime to R.string.search_date_any,
                DateFilter.Last7Days to R.string.search_date_7_days,
                DateFilter.Last30Days to R.string.search_date_30_days,
                DateFilter.LastYear to R.string.search_date_year
            )
            presets.forEach { (preset, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        menuOpen = false
                        onDate(preset)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_date_custom)) },
                onClick = {
                    menuOpen = false
                    picking = true
                },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            )
        }
    }
    if (picking) {
        RangeDialog(
            current = date as? DateFilter.Custom,
            onDismiss = { picking = false },
            onPicked = {
                picking = false
                onDate(it)
            }
        )
    }
}

@Composable
private fun dateLabel(date: DateFilter): String = when (date) {
    DateFilter.AnyTime -> stringResource(R.string.search_filter_date)
    DateFilter.Last7Days -> stringResource(R.string.search_date_7_days)
    DateFilter.Last30Days -> stringResource(R.string.search_date_30_days)
    DateFilter.LastYear -> stringResource(R.string.search_date_year)
    is DateFilter.Custom -> customLabel(date)
}

@Composable
private fun customLabel(date: DateFilter.Custom): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val from = date.from?.format(format)
    val to = date.to?.format(format)
    return when {
        from != null && to != null -> stringResource(R.string.search_date_range, from, to)
        from != null -> stringResource(R.string.search_date_from, from)
        else -> stringResource(R.string.search_date_until, to.orEmpty())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(
    current: DateFilter.Custom?,
    onDismiss: () -> Unit,
    onPicked: (DateFilter) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = current?.from?.let(::millisOf),
        initialSelectedEndDateMillis = current?.to?.let(::millisOf)
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPicked(DateFilter.Custom(start?.let(::dayOf), end?.let(::dayOf))) },
                enabled = start != null,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Text(stringResource(R.string.search_date_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    ) {
        DateRangePicker(state = state)
    }
}

/** The picker works in UTC midnights. */
private fun millisOf(day: LocalDate): Long =
    day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun dayOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
