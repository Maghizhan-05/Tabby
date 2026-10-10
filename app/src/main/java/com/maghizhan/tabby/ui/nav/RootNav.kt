package com.maghizhan.tabby.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maghizhan.tabby.ui.common.TabbyTab
import com.maghizhan.tabby.ui.common.TabbyTabToggle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maghizhan.tabby.AppGraph
import com.maghizhan.tabby.analytics.AnalyticsEvent
import com.maghizhan.tabby.analytics.AnalyticsScreen
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
import com.maghizhan.tabby.ui.profile.AccountDeletionViewModel
import com.maghizhan.tabby.ui.profile.ManageCategoriesScreen
import com.maghizhan.tabby.ui.profile.ProfileScreen
import com.maghizhan.tabby.ui.profile.ProfileViewModel
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyBackdrop
import kotlinx.coroutines.launch

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
                provider = state.session.provider,
                syncScheduler = syncScheduler,
                onBeginGoogleReauthentication = authViewModel::startGoogleSignIn,
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
    provider: com.maghizhan.tabby.data.remote.AuthProvider,
    syncScheduler: SyncScheduler,
    onBeginGoogleReauthentication: () -> Unit,
    onSignOut: () -> Unit
) {
    val colors = Tabby.colors
    val scope = rememberCoroutineScope()
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

    val analytics: suspend (AnalyticsEvent) -> Unit = graph.analyticsTracker::track
    val homeViewModel: HomeViewModel = viewModel(
        key = "home-$ownerId",
        factory = HomeViewModel.factory(analytics)
    )
    val entryViewModel: EntryViewModel = viewModel(
        key = "entry-$ownerId",
        factory = EntryViewModel.factory(graph.entryWriter, graph.expenseStore, onLocalWrite, analytics)
    )
    val friendsViewModel: FriendsViewModel = viewModel(
        key = "friends-$ownerId",
        factory = FriendsViewModel.factory(graph.friendStore, onLocalWrite, analytics)
    )
    val profileViewModel: ProfileViewModel = viewModel(
        key = "profile-$ownerId",
        factory = ProfileViewModel.factory(graph.categoryStore, onLocalWrite, analytics)
    )
    val deletionViewModel: AccountDeletionViewModel = viewModel(
        key = "account-deletion-$ownerId",
        factory = AccountDeletionViewModel.factory(
            provider = provider,
            auth = graph.authService,
            coordinator = graph.accountDeletion,
            pendingGoogle = graph.deletionRequests,
            beginGoogleReauthentication = onBeginGoogleReauthentication,
            onDeleted = onSignOut
        )
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
    val deletionState by deletionViewModel.state.collectAsStateWithLifecycle()
    var analyticsEnabled by remember { mutableStateOf(graph.analyticsTracker.isEnabled()) }
    var showAnalyticsDisclosure by remember {
        mutableStateOf(!graph.analyticsTracker.hasAnsweredDisclosure())
    }


    var showEntrySheet by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }

    LaunchedEffect(currentRoute?.route) {
        val screen = when (currentRoute?.route) {
            Routes.HOME -> AnalyticsScreen.HOME
            Routes.FRIENDS -> AnalyticsScreen.FRIENDS
            Routes.PROFILE -> AnalyticsScreen.PROFILE
            else -> null
        }
        if (screen != null) analytics(AnalyticsEvent.ScreenViewed(screen))
    }

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
        analytics(AnalyticsEvent.ScreenViewed(AnalyticsScreen.QUICK_ENTRY))
        QuickEntryLauncher.consume(requestId)
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            // One connected toggle instead of two NavigationBarItems. See
            // TabbyTabToggle for why: Material's per-item indicators read as two
            // separate buttons, where iOS moves ONE marker between two states.
            val destinations = listOf(Routes.HOME, Routes.FRIENDS)
            // Default to Home rather than -1 while the back stack settles: a
            // missing selection would park the pill off-tab for a frame on
            // every cold start.
            val selectedIndex = destinations
                .indexOfFirst { route -> currentRoute?.hierarchy?.any { it.route == route } == true }
                .coerceAtLeast(0)

            fun navigateTo(route: String) {
                // Is this tab's root already somewhere beneath us? Checked
                // against the real back stack, NOT the destination hierarchy:
                // Profile is a SIBLING of Home in the same graph, not a child
                // of it, so a hierarchy check never matched and every tap fell
                // through to navigate().
                val inBackStack = navController.currentBackStack.value
                    .any { it.destination.route == route }
                val isCurrent = currentRoute?.route == route

                if (inBackStack && !isCurrent) {
                    // Pop back to it rather than navigating.
                    //
                    // navigate() cannot do this job here: popUpTo(saveState =
                    // true) SAVES the entries it pops, keyed by the destination
                    // popped up to, and restoreState = true then restores them.
                    // Tapping Home from Profile therefore saved [profile] under
                    // "home" and restored it in the same call, landing straight
                    // back on Profile — the button looked dead while actually
                    // completing a perfect round trip. Friends was unaffected
                    // only because it had no saved stack to restore.
                    navController.popBackStack(route, inclusive = false)
                    return
                }
                if (isCurrent) return

                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            TabbyTabToggle(
                tabs = listOf(
                    TabbyTab("Home", Icons.Filled.Home) { navigateTo(Routes.HOME) },
                    TabbyTab("Friends", Icons.Filled.Group) { navigateTo(Routes.FRIENDS) }
                ),
                selectedIndex = selectedIndex,
                // Inset from the screen edges so the capsule floats clear of
                // them; the bar is a control on the backdrop, not a docked edge.
                //
                // navigationBarsPadding FIRST, then the visual padding. The
                // Scaffold drops the bottom content inset so the translucent
                // capsule can float over the backdrop, but that left the toggle
                // sitting in the system gesture zone — Android's gesture
                // indicator drew across its lower edge and the bottom of its
                // touch target competed with the back/home swipe. Consuming the
                // inset here restores the clearance without re-reserving an
                // opaque bar area in the Scaffold.
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            )
        },
        // Only the BOTTOM inset is dropped, not all four. The toggle is
        // translucent and floats over the backdrop, so Scaffold must not
        // reserve an opaque bar area beneath it — but zeroing every side put
        // the screen titles under the status bar. Keep the top inset.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
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
                    analyticsEnabled = analyticsEnabled,
                    onAnalyticsChanged = { enabled ->
                        analyticsEnabled = enabled
                        scope.launch {
                            graph.analyticsTracker.setConsent(enabled)
                        }
                    },
                    deletionProvider = provider,
                    deletionState = deletionState,
                    onDeleteAccount = deletionViewModel::present,
                    onDeleteDismiss = deletionViewModel::dismiss,
                    onDeletePasswordChanged = deletionViewModel::onPasswordChanged,
                    onDeleteConfirm = deletionViewModel::confirm,
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

    if (showAnalyticsDisclosure) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Help improve Tabby?") },
            text = {
                Text(
                    "Share optional usage analytics so we can understand which features matter. " +
                        "This never includes your notes, names, email, exact amounts, advertising ID, or device ID. " +
                        "You can change this later in Profile."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    analyticsEnabled = true
                    showAnalyticsDisclosure = false
                    scope.launch { graph.analyticsTracker.setConsent(true) }
                }) { Text("Share analytics") }
            },
            dismissButton = {
                TextButton(onClick = {
                    analyticsEnabled = false
                    showAnalyticsDisclosure = false
                    scope.launch { graph.analyticsTracker.setConsent(false) }
                }) { Text("Not now") }
            },
            containerColor = colors.elevatedSurface
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
