package com.maghizhan.tabby.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.TabbyShapes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")

/**
 * The quick-entry / edit sheet.
 *
 * One composable for both, as with [EntryViewModel]: the only differences are
 * the eyebrow text and the CTA label, and duplicating 200 lines of form to vary
 * two strings is how the two iOS sheets drifted apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(
    viewModel: EntryViewModel,
    categories: List<CategoryEntity>,
    activeOwnerId: String?,
    isEditing: Boolean,
    onDismiss: () -> Unit
) {
    val colors = Tabby.colors
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // The sheet closes itself only after the save has durably committed, so a
    // failed write leaves the user's typing on screen to retry.
    if (state.saved) onDismiss()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.paper
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEditing) "EDIT TAB" else "NEW TAB",
                        color = colors.accentBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Text(
                        text = if (isEditing) "Update spend" else "Log a spend",
                        color = colors.ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                TabbyOrbit(size = 34.dp)
            }

            // Amount
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.72f), TabbyShapes.card)
                    .border(1.dp, colors.hairline, TabbyShapes.card)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AMOUNT",
                    color = colors.subtleInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = viewModel::onAmountChanged,
                    placeholder = {
                        Text("0", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colors.ink,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Category
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.88f), TabbyShapes.control)
                    .border(1.dp, colors.hairline, TabbyShapes.control)
                    .clickable { showCategoryPicker = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TabbyOrbit(size = 24.dp, lineWidth = 2.dp)
                Text(
                    text = state.categoryQuery.ifEmpty { "Choose category" },
                    color = if (state.categoryQuery.isEmpty()) colors.subtleInk else colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.accentBright
                )
            }

            // Note, with a live counter
            Column {
                OutlinedTextField(
                    value = state.noteText,
                    onValueChange = viewModel::onNoteChanged,
                    placeholder = { Text("Note (optional)", color = colors.subtleInk) },
                    isError = !state.isNoteValid,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${state.noteText.length}/${ExpenseEntity.MAXIMUM_NOTE_LENGTH}",
                    color = if (state.isNoteValid) colors.subtleInk else colors.accentBright,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // When: date and time are two Material pickers, since Compose has no
            // single combined picker equivalent to the iOS `.compact` DatePicker.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.88f), TabbyShapes.control)
                    .border(1.dp, colors.hairline, TabbyShapes.control)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "When", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = { showDatePicker = true }) {
                    Text(
                        text = WHEN_FORMAT.format(state.date.atZone(ZoneId.systemDefault())),
                        color = colors.accentBright,
                        fontSize = 13.sp
                    )
                }
            }

            state.notice?.let { notice ->
                Text(text = notice, color = colors.accentBright, fontSize = 12.sp)
            }

            Button(
                onClick = { viewModel.submit(categories, activeOwnerId) },
                enabled = state.canSubmit,
                shape = TabbyShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.paper,
                    disabledContainerColor = colors.accent.copy(alpha = 0.32f),
                    disabledContentColor = colors.paper.copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isEditing) "Save changes" else "Lock it in",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isEditing) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = null
                )
            }
        }
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories = categories,
            query = state.categoryQuery,
            onQueryChanged = viewModel::onCategoryChanged,
            onSelect = { name ->
                viewModel.onCategoryChanged(name)
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (showDatePicker) {
        val zone = ZoneId.systemDefault()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        // The picker reports a UTC midnight; the time-of-day is
                        // preserved from the current value so choosing a date
                        // does not silently reset the clock to 00:00.
                        val pickedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        val existingTime = state.date.atZone(zone).toLocalTime()
                        viewModel.onDateChanged(
                            pickedDate.atTime(existingTime).atZone(zone).toInstant()
                        )
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val zone = ZoneId.systemDefault()
        val local = state.date.atZone(zone)
        val timePickerState = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = true
        )
        DatePickerDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date: LocalDate = state.date.atZone(zone).toLocalDate()
                    val time = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    viewModel.onDateChanged(date.atTime(time).atZone(zone).toInstant())
                    showTimePicker = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        ) {
            TimePicker(state = timePickerState)
        }
    }
}

/** Searchable category list with an "Add …" row for a new name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Tabby.colors
    val filtered = EntryForm.filtered(categories, query)
    val isNew = EntryForm.isNewCategory(categories, query)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Category",
                color = colors.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                placeholder = { Text("Search or add category", color = colors.subtleInk) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (isNew) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accent.copy(alpha = 0.10f), TabbyShapes.control)
                        .clickable { onSelect(query.trim()) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = colors.accentBright
                    )
                    Text(
                        text = "Add \"${query.trim()}\"",
                        color = colors.accentBright,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = filtered, key = { it.id }) { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(category.name) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(colors.accent.copy(alpha = 0.20f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(colors.accent, CircleShape)
                            )
                        }
                        Text(
                            text = category.name,
                            color = colors.ink,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (category.name.equals(query.trim(), ignoreCase = true)) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = colors.accentBright
                            )
                        }
                    }
                }
            }
        }
    }
}
