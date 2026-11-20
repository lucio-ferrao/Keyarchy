// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.latin.ChosenApps
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchScreen

/** An app of the launcher, by its package. */
private class NotifyingApp(val packageName: String, val name: String, val icon: Drawable?)

/**
 * The apps, each with a switch for what [chosen] turns on for its notifications: showing the silent and ongoing ones
 * in the list of the launcher mode, or their buttons on the home screen (only Home Assistant on at first).
 */
@Composable
fun NotificationAppsScreen(
    onClickBack: () -> Unit,
    title: Int,
    chosen: ChosenApps,
) {
    val ctx = LocalContext.current
    val apps = remember { launcherApps(ctx) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(title)) },
        filteredItems = { term -> apps.filter { it.name.contains(term, true) } },
        itemContent = { app -> NotifyingAppRow(app, chosen) },
    )
}

@Composable
private fun NotifyingAppRow(app: NotifyingApp, chosen: ChosenApps) {
    val ctx = LocalContext.current
    var shown by remember {
        mutableStateOf(app.packageName in chosen.get(ctx))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        app.icon?.let { Image(it.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(32.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = shown,
            onCheckedChange = {
                shown = it
                chosen.set(ctx, app.packageName, it)
            }
        )
    }
}

/** the apps with an icon in the launcher, of the profiles that are not paused, once each, by name */
private fun launcherApps(context: Context): List<NotifyingApp> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val packageManager = context.packageManager
    return launcherApps.profiles.filter { !userManager.isQuietModeEnabled(it) }
        .flatMap { user -> launcherApps.getActivityList(null, user) }
        // the main profile when the app is in more than one
        .sortedBy { if (it.user == Process.myUserHandle()) 0 else 1 }
        .distinctBy { it.componentName.packageName }
        .map { activity ->
            val info = activity.applicationInfo
            NotifyingApp(
                activity.componentName.packageName,
                info.loadLabel(packageManager).toString(),
                info.loadIcon(packageManager).let { if (activity.user == Process.myUserHandle()) it else packageManager.getUserBadgedIcon(it, activity.user) }
            )
        }.sortedBy { it.name.lowercase() }
}
