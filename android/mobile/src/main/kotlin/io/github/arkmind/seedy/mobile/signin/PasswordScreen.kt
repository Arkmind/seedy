package io.github.arkmind.seedy.mobile.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.PasswordViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.ActionCard
import io.github.arkmind.seedy.mobile.parts.bleed
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.pagePadding
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.components.SeedySwitch
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Who the password is for: a profile from "Who's watching?", or nobody yet ("Add account" types a user name). */
data class SignInFor(val name: String, val imageUrl: String?, val colour: Int)

/** Board "Android · Sign in": a profile's password, "Stay signed in", or Quick Connect instead. */
@Composable
fun PasswordScreen(
    server: SavedServer,
    profile: SignInFor?,
    state: PasswordViewModel.State,
    onBack: () -> Unit,
    onUserName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onForgot: () -> Unit,
    onQuickConnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SeedyColors.Bg).imePadding().padding(pagePadding(bottom = 30.dp))) {
        Row(Modifier.fillMaxWidth().bleed(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
            Spacer(Modifier.size(8.dp))
            SText(
                "${server.name} · ${server.address.removePrefix("http://").removePrefix("https://")}",
                Modifier.weight(1f), size = 12.sp, color = SeedyColors.Text3, maxLines = 1,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (profile != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileSquare(profile.name, profile.imageUrl, profile.colour, size = 96.dp, radius = 26.dp, letterSize = 42.sp)
                    SText(profile.name, size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
                    SText(t("Not you? Switch profile"), Modifier.tap(onClick = onBack), size = 13.sp, weight = 600, color = SeedyColors.Text2)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SText(t("Sign in"), size = 26.sp, weight = 900, color = SeedyColors.White, lineHeight = 1.2f)
                    SText(
                        t("With your account on {server}", "server" to server.name),
                        Modifier.padding(top = 8.dp), size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profile == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel(t("User name"))
                        SeedyField(state.userName, onUserName, imeAction = ImeAction.Next)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel(t("Password"))
                    SeedyField(state.password, onPassword, password = true, onDone = onSignIn)
                }
                state.error?.let { SText(it, size = 13.sp, weight = 600, color = SeedyColors.Danger) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SText(t("Stay signed in on this phone"), Modifier.weight(1f), size = 14.sp, weight = 600, color = SeedyColors.Text)
                    SeedySwitch(state.remember, onRemember)
                }
                PrimaryButton(
                    if (state.busy) t("Signing in…") else t("Sign in"),
                    onSignIn,
                    Modifier.padding(top = 6.dp),
                    enabled = !state.busy && (profile != null || state.userName.isNotBlank()),
                )
                SText(
                    t("Forgot password?"),
                    Modifier.align(Alignment.CenterHorizontally).tap(onClick = onForgot).padding(10.dp),
                    size = 13.sp, weight = 600, color = SeedyColors.Text2,
                )
            }
        }
        ActionCard(AppIcons.Phone, t("Use Quick Connect instead"), t("Approve this phone from a signed-in device"), onQuickConnect)
    }
}

/**
 * Quick Connect's code, in a sheet over the sign-in page: type it on a device that's already signed in (Seedy on the
 * web: Settings › Profile and sign-in › Quick Connect) and this phone signs in by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectSheet(code: String?, error: String?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SeedyColors.Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Stroke))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SText(t("Quick Connect"), size = 20.sp, weight = 900, color = SeedyColors.White)
            when {
                error != null -> {
                    SText(error, size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center)
                    PrimaryButton(t("Get a new code"), onRetry)
                }
                code == null -> SText(t("Asking the server for a code…"), size = 14.sp, color = SeedyColors.Text2)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in code) {
                            Box(
                                Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row),
                                contentAlignment = Alignment.Center,
                            ) {
                                SText(c.toString(), size = 26.sp, weight = 900, color = LocalAccent.current)
                            }
                        }
                    }
                    SText(
                        t("On a device that’s signed in, open Settings › Profile and sign-in › Quick Connect and enter this code."),
                        size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center, lineHeight = 1.45f,
                    )
                    SText(t("This phone signs in as soon as it’s approved."), size = 12.sp, color = SeedyColors.Text3, align = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
