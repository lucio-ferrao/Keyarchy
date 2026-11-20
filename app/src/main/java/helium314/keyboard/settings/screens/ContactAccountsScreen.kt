// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.accounts.AccountManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
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
import helium314.keyboard.latin.AppLauncherMode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.settings.SearchScreen

/** An account with contacts: its name, the app it belongs to and how many contacts it has. */
private class ContactAccount(val key: String, val name: String, val app: String, val icon: Drawable?, val count: Int)

/** The accounts with contacts, each with a switch for the search of the launcher mode to find its contacts (all off at first). */
@Composable
fun ContactAccountsScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val accounts = remember { contactAccounts(ctx) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.contact_accounts)) },
        filteredItems = { term -> accounts.filter { it.name.contains(term, true) || it.app.contains(term, true) } },
        itemContent = { account -> ContactAccountRow(account) },
    )
}

@Composable
private fun ContactAccountRow(account: ContactAccount) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(account.key in AppLauncherMode.contactAccounts(ctx)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        account.icon?.let { Image(it.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(32.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge)
            Text("${account.app} · ${account.count}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                AppLauncherMode.setContactAccount(ctx, account.key, it)
            }
        )
    }
}

/** the accounts with contacts, the ones with most contacts first, with the name and icon of the app they belong to */
private fun contactAccounts(context: Context): List<ContactAccount> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    try {
        context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            ContactsContract.RawContacts.DELETED + " = 0", null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val account = cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty()
                counts[account] = (counts[account] ?: 0) + 1
            }
        }
    } catch (e: SecurityException) {
        Log.w("contact-accounts", "no permission to read contacts", e)
    }
    val packageManager = context.packageManager
    val authenticators = AccountManager.get(context).authenticatorTypes.associateBy { it.type }
    return counts.map { (account, count) ->
        val (type, name) = account
        val authenticator = authenticators[type]
        val app = authenticator?.let { packageManager.getText(it.packageName, it.labelId, null)?.toString() } ?: type
        val icon = authenticator?.let { packageManager.getDrawable(it.packageName, it.iconId, null) }
        ContactAccount(AppLauncherMode.accountKey(type, name), name.ifEmpty { app }, app, icon, count)
    }.sortedByDescending { it.count }
}
