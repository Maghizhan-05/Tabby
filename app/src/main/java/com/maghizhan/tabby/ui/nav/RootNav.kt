package com.maghizhan.tabby.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maghizhan.tabby.AppGraph
import com.maghizhan.tabby.data.QuickEntryLauncher
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncScheduler
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
import com.maghizhan.tabby.ui.auth.LoginScreen
import com.maghizhan.tabby.ui.auth.RestoringScreen
import com.maghizhan.tabby.ui.entry.EntrySheet
import com.maghizhan.tabby.ui.entry.EntryViewModel
import com.maghizhan.tabby.ui.friends.FriendEditSheet
import com.maghizhan.tabby.ui.friends.FriendsScreen
import com.maghizhan.tabby.ui.friends.FriendsForm
import com.maghizhan.tabby.ui.friends.FriendsViewModel
import com.maghizhan.tabby.ui.home.HomeScreen
import com.maghizhan.tabby.ui.home.HomeViewModel
import com.maghizhan.tabby.ui.profile.CategoryRules
import com.maghizhan.tabby.ui.profile.ManageCategoriesScreen
import com.maghizhan.tabby.ui.profile.ProfileScreen
import com.maghizhan.tabby.ui.profile.ProfileViewModel
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyBackdrop

private object Routes {
    const val HOME = "home"
    const val FRIENDS = "friends"
    const val PROFILE = "profile"
    const val CATEGORIES = "profile/categories"
}

/**
 * The root router: exactly the three states of [AuthUiState], nothing else.
 *
 * Implemented as a `when` over the sealed state rather than as navigation
 * destinations, deliberately. A NavHost keeps a back stack, and an auth
 * transition must not be back-navigable: a user who signs out must not be able
 * to press Back into the authenticated tree. Navigation is used only *inside*
 * the authenticated branch, where a back stack is what the user expects.
 */
@Composable
fun RootNav(
    graph: AppGraph,
    authViewModel: AuthViewModel,
    syncScheduler: SyncScheduler,
    modifier: Modifier = Modifier
) {
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val form by authViewModel.formState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        TabbyBackdrop()

        when (val state = authState) {
            AuthUiState.Restoring -> RestoringScreen()

            is AuthUiState.SignedOut -> LoginScreen(
                form = form,
                routerError = state.error,
                isSupabaseConfigured = authViewModel.isSupabaseConfigured,
                isGoogleProviderConfigured = authViewModel.isGoogleProviderConfigured,
                onEmailChanged = authViewModel::onEmailChanged,
                onPasswordChanged = authViewModel::onPasswordChanged,
                onConfirmPasswordChanged = authViewModel::onConfirmPasswordChanged,
                onToggleMode = authViewModel::toggleMode,
                onSubmit = authViewModel::submitPrimary,
                onGoogleSignIn = authViewModel::startGoogleSignIn
            )

            is AuthUiState.Authenticated -> AuthenticatedHost(
                graph = graph,
                ownerId = state.session.userId,
                email = state.session.email,
                syncScheduler = syncScheduler,
                onSignOut = authViewModel::requestSignOut
            )
        }
    }
}

/**
 * The signed-in app: Home and Friends tabs plus the profile stack.
 *
 * Keyed on `ownerId` by the caller's `when`, so switching accounts builds a
 * fresh NavHost and fresh view models rather than leaving the previous
 * account's rows on screen.
 */
@Composable
private fun AuthenticatedHost(
    graph: AppGraph,
    ownerId: String,
    email: String?,
    syncScheduler: SyncScheduler,
    onSignOut: () -> Unit
) {
    val colors = Tabby.colors
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // Wrapped in a lambda that discards the SyncRun result: a view model only
    // needs "a sync was requested", and returning the run would tempt a screen
    // into waiting on the network before dismissing a sheet.
    //
    // The widget is refreshed FIRST, because it must reflect the committed local
    // write whether or not the push succeeds; the sync run refreshes it again
    // afterwards if reconciliation changed anything.
    val onLocalWrite: suspend () -> Unit = {
        graph.widgetUpdater.setActiveOwner(ownerId)
        syncScheduler.onLocalWrite()
    }

    val homeViewModel: HomeViewModel = viewModel(key = "home-$ownerId")
    val entryViewModel: EntryViewModel = viewModel(
        key = "entry-$ownerId",
        factory = EntryViewModel.factory(graph.entryWriter, graph.expenseStore, onLocalWrite)
    )
    val friendsViewModel: FriendsViewModel = viewModel(
        key = "friends-$ownerId",
        factory = FriendsViewModel.factory(graph.friendStore, onLocalWrite)
    )
    val profileViewModel: ProfileViewModel = viewModel(
        key = "profile-$ownerId",
        factory = ProfileViewModel.factory(graph.categoryStore, onLocalWrite)
    )

    // The DAO flows are already owner-scoped and tombstone-filtered in SQL, so
    // the lists reaching the screens cannot contain another account's rows even
    // if a UI filter were forgotten.
    val expenses by graph.expenseDao
        .observeVisible(ownerId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by graph.categoryDao
        .observeForOwner(ownerId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val friends by graph.friendDao
        .observeVisible(ownerId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val mode by homeViewModel.mode.collectAsStateWithLifecycle()
    val selectedCategory by homeViewModel.selectedCategory.collectAsStateWithLifecycle()
    val friendEdit by friendsViewModel.editState.collectAsStateWithLifecycle()
    val profileState by profileViewModel.uiState.collectAsStateWithLifecycle()

    var showEntrySheet by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }

    // Shortcut and widget taps. Collected here rather than in the activity so a
    // request can only open the sheet once the user is actually signed in —
    // opening quick entry on the login screen would have nowhere to save to.
    //
    // Requests are consumable ids, not a counter: a counter stayed non-zero
    // forever, so every rotation and every account-keyed recreation observed the
    // same non-zero value and reopened a sheet the user had already used. The id
    // is consumed as soon as it is acted on, and null means nothing outstanding.
    val quickEntryRequest by QuickEntryLauncher.requests.collectAsStateWithLifecycle()
    LaunchedEffect(quickEntryRequest) {
        val requestId = quickEntryRequest ?: return@LaunchedEffect
        editingExpense = null
        entryViewModel.startNew()
        showEntrySheet = true
        QuickEntryLauncher.consume(requestId)
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = colors.surface.copy(alpha = 0.94f)) {
                listOf(
                    Triple(Routes.HOME, "Home", Icons.Filled.Home),
                    Triple(Routes.FRIENDS, "Friends", Icons.Filled.Group)
                ).forEach { (route, label, icon) ->
                    val selected = currentRoute?.hierarchy?.any { it.route == route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(imageVector = icon, contentDescription = label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.paper,
                            selectedTextColor = colors.accentBright,
                            indicatorColor = colors.accent,
                            unselectedIconColor = colors.subtleInk,
                            unselectedTextColor = colors.subtleInk
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    expenses = expenses,
                    mode = mode,
                    selectedCategory = selectedCategory,
                    onModeSelected = homeViewModel::onModeSelected,
                    onCategorySelected = homeViewModel::onCategorySelected,
                    onProfile = { navController.navigate(Routes.PROFILE) },
                    onAddSpend = {
                        editingExpense = null
                        entryViewModel.startNew()
                        showEntrySheet = true
                    },
                    onEditExpense = { expense ->
                        editingExpense = expense
                        entryViewModel.startEditing(expense)
                        showEntrySheet = true
                    },
                    onDeleteExpense = { expense ->
                        entryViewModel.delete(expense, ownerId)
                    }
                )
            }

            composable(Routes.FRIENDS) {
                FriendsScreen(
                    friends = friends,
                    aggregateNet = FriendsForm.aggregateNet(friends),
                    onAdd = friendsViewModel::beginAdding,
                    onEdit = friendsViewModel::beginEditing,
                    onDelete = { friend -> friendsViewModel.delete(friend, ownerId) }
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    email = email,
                    categoryCount = categories.size,
                    onBack = { navController.popBackStack() },
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                    onSignOut = onSignOut
                )
            }

            composable(Routes.CATEGORIES) {
                ManageCategoriesScreen(
                    categories = categories,
                    draftName = profileState.newCategoryName,
                    canAdd = profileState.newCategoryName.trim().isNotEmpty() &&
                        !CategoryRules.isDuplicate(
                            categories,
                            profileState.newCategoryName,
                            ownerId
                        ),
                    canDelete = { category -> CategoryRules.canDelete(category, ownerId) },
                    notice = profileState.notice,
                    onDraftChanged = profileViewModel::onNewCategoryNameChanged,
                    onAdd = { profileViewModel.addCategory(categories, ownerId) },
                    onDelete = { category -> profileViewModel.deleteCategory(category, ownerId) },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (showEntrySheet) {
        EntrySheet(
            viewModel = entryViewModel,
            categories = categories,
            activeOwnerId = ownerId,
            isEditing = editingExpense != null,
            onDismiss = {
                showEntrySheet = false
                editingExpense = null
                entryViewModel.consumeSaved()
            }
        )
    }

    // `isPresenting` is the view model's own flag, so the sheet closes exactly
    // when the save commits (the view model resets the whole state) rather than
    // optimistically on tap.
    if (friendEdit.isPresenting) {
        FriendEditSheet(
            state = friendEdit,
            onNameChanged = friendsViewModel::onNameChanged,
            onTheyOweUsChanged = friendsViewModel::onTheyOweUsChanged,
            onWeOweThemChanged = friendsViewModel::onWeOweThemChanged,
            onSubmit = {
                // The live row is looked up from the observed list at save time,
                // so an edit commits against the current revision even if a pull
                // landed while the sheet was open.
                val live = friendEdit.friendId?.let { id -> friends.firstOrNull { it.id == id } }
                friendsViewModel.save(live, ownerId)
            },
            onDismiss = friendsViewModel::dismissEditor
        )
    }
}
