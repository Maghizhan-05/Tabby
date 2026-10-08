package com.maghizhan.tabby.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.ui.common.TabbyCard
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.TabbyShapes

/** Profile: the signed-in identity, a route into categories, and sign out. */
@Composable
fun ProfileScreen(
    email: String?,
    categoryCount: Int,
    onBack: () -> Unit,
    onManageCategories: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            // 16dp, matching Home and Friends; 18dp made the profile
            // sheet's cards sit narrower than the screen behind it.
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopBar(title = "Profile", onBack = onBack)

        TabbyCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TabbyOrbit(size = 42.dp)
                Column {
                    Text(
                        text = "SIGNED IN AS",
                        color = colors.subtleInk,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        text = email ?: "Unknown account",
                        color = colors.ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface.copy(alpha = 0.82f), TabbyShapes.control)
                .border(1.dp, colors.hairline, TabbyShapes.control)
                .clickable(onClick = onManageCategories)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Categories",
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(text = "$categoryCount", color = colors.subtleInk, fontSize = 14.sp)
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.accentBright
            )
        }

        Box(modifier = Modifier.weight(1f))

        Button(
            onClick = onSignOut,
            shape = TabbyShapes.control,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.elevatedSurface,
                // Destructive red, as on iOS: sign-out is the one action on this
                // screen that throws work away, and neutral ink made it look
                // like another navigation row.
                contentColor = colors.negative
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            // An exit arrow, matching the iOS
            // `rectangle.portrait.and.arrow.right`. A padlock said "locked",
            // which is the opposite of what the button does.
            Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Text(
                text = "Sign Out",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Manage categories: add a name, delete a custom one.
 *
 * Default categories show no delete affordance at all, rather than a disabled
 * one: the store refuses to delete them, and offering a button that always
 * fails is worse than not offering it.
 */
@Composable
fun ManageCategoriesScreen(
    categories: List<CategoryEntity>,
    draftName: String,
    canAdd: Boolean,
    canDelete: (CategoryEntity) -> Boolean,
    notice: String?,
    onDraftChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TopBar(title = "Categories", onBack = onBack)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = draftName,
                onValueChange = onDraftChanged,
                placeholder = { Text("New category", color = colors.subtleInk) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onAdd, enabled = canAdd) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add category",
                    tint = if (canAdd) colors.accentBright else colors.subtleInk
                )
            }
        }

        notice?.let {
            Text(text = it, color = colors.accentBright, fontSize = 12.sp)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = categories, key = { it.id }) { category ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(colors.accent.copy(alpha = 0.18f), CircleShape),
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
                    if (!canDelete(category)) {
                        Text(text = "Default", color = colors.subtleInk, fontSize = 11.sp)
                    } else {
                        IconButton(onClick = { onDelete(category) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete ${category.name}",
                                tint = colors.subtleInk
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.hairline)
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val colors = Tabby.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = colors.ink
            )
        }
        Text(
            text = title,
            color = colors.ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
