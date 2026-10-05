package com.maghizhan.tabby.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.TabbyShapes

/**
 * Sign in or create an account.
 *
 * Stateless: the form lives in [AuthViewModel] so a rotation mid-typing does
 * not clear the fields, and the screen itself can be rendered in isolation.
 */
@Composable
fun LoginScreen(
    form: LoginFormState,
    routerError: String?,
    isSupabaseConfigured: Boolean,
    isGoogleProviderConfigured: Boolean,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConfirmPasswordChanged: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    onGoogleSignIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    val isCreating = form.mode == AuthMode.CREATE_ACCOUNT

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header geometry is shared with RestoringScreen so the chrome does not
        // jump when the router swaps one for the other.
        AuthHeader(
            title = if (isCreating) "Start your tab" else "Welcome back",
            subtitle = if (isCreating) {
                "Create an account to sync across devices."
            } else {
                "Sign in to pick up where you left off."
            }
        )

        if (!isSupabaseConfigured) {
            NoticeBanner(
                text = "Sync isn't configured in this build. " +
                    "Add your Supabase URL and anon key to continue.",
                emphasis = true
            )
        }

        OutlinedTextField(
            value = form.email,
            onValueChange = onEmailChanged,
            label = { Text("Email") },
            singleLine = true,
            enabled = isSupabaseConfigured && !form.isBusy,
            isError = form.email.isNotEmpty() && !form.isEmailValid,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChanged,
            label = { Text("Password") },
            singleLine = true,
            enabled = isSupabaseConfigured && !form.isBusy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isCreating) ImeAction.Next else ImeAction.Done
            ),
            supportingText = if (isCreating) {
                { Text("At least 6 characters.", color = colors.subtleInk, fontSize = 11.sp) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (isCreating) {
            OutlinedTextField(
                value = form.confirmPassword,
                onValueChange = onConfirmPasswordChanged,
                label = { Text("Confirm password") },
                singleLine = true,
                enabled = isSupabaseConfigured && !form.isBusy,
                isError = form.confirmPasswordError != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                supportingText = form.confirmPasswordError?.let { error ->
                    { Text(error, color = colors.accentBright, fontSize = 11.sp) }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // The form's own failure, then the router's. Both are shown because they
        // mean different things: a rejected password versus a restore that failed.
        form.notice?.let { NoticeBanner(text = it, emphasis = true) }
        routerError?.let { NoticeBanner(text = it, emphasis = true) }

        Button(
            onClick = onSubmit,
            enabled = form.canSubmit && isSupabaseConfigured,
            shape = TabbyShapes.control,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.paper,
                disabledContainerColor = colors.accent.copy(alpha = 0.3f),
                disabledContentColor = colors.paper.copy(alpha = 0.7f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (form.isBusy) {
                CircularProgressIndicator(
                    color = colors.paper,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp)
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
            }
            Text(
                text = form.primaryCtaTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(onClick = onToggleMode, enabled = !form.isBusy) {
            Text(
                text = if (isCreating) {
                    "Already have an account? Sign in"
                } else {
                    "New here? Create an account"
                },
                color = colors.accentBright,
                fontSize = 13.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(color = colors.hairline, modifier = Modifier.weight(1f))
            Text(text = "or", color = colors.subtleInk, fontSize = 12.sp)
            HorizontalDivider(color = colors.hairline, modifier = Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = onGoogleSignIn,
            enabled = isGoogleProviderConfigured && isSupabaseConfigured && !form.isBusy,
            shape = TabbyShapes.control,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = "Continue with Google",
                color = if (isGoogleProviderConfigured) colors.ink else colors.subtleInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!isGoogleProviderConfigured) {
            Text(
                text = "Google sign-in isn't configured for this build.",
                color = colors.subtleInk,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The splash shown while a stored session is being restored.
 *
 * Hidden from accessibility services entirely: it is a transient frame, and
 * announcing it would interrupt the user with a screen they cannot act on and
 * that disappears on its own.
 */
@Composable
fun RestoringScreen(modifier: Modifier = Modifier) {
    val colors = Tabby.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { },
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AuthHeader(title = "Tabby", subtitle = "Restoring your session…")
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun AuthHeader(title: String, subtitle: String) {
    val colors = Tabby.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TabbyOrbit(size = 64.dp, lineWidth = 3.dp)
        Text(
            text = title,
            color = colors.ink,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle,
            color = colors.subtleInk,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NoticeBanner(text: String, emphasis: Boolean) {
    val colors = Tabby.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (emphasis) {
                    colors.accent.copy(alpha = 0.12f)
                } else {
                    colors.elevatedSurface
                },
                TabbyShapes.control
            )
            .border(1.dp, colors.hairline, TabbyShapes.control)
            .padding(14.dp)
    ) {
        Text(
            text = text,
            color = if (emphasis) colors.accentBright else colors.subtleInk,
            fontSize = 12.sp
        )
    }
}
