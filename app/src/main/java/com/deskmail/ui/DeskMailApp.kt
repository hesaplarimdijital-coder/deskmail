package com.deskmail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import com.deskmail.R
import com.deskmail.demo.DemoMail
import com.deskmail.ui.mail.FoldersScreen
import com.deskmail.ui.settings.SettingsScreen
import desktopkit.DesktopKitRoot
import desktopkit.appResources
import desktopkit.components.DeskIcons
import desktopkit.screens.MessageScreen
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.ShellSpec

/**
 * The whole app inside one window: the kit's root (theme, density, insets) and its shell (bottom
 * bar, rail or sidebar by the window's width) around the screens. [startRoute] is where a window
 * opened from another one starts.
 */
@Composable
fun DeskMailApp(
    mail: DemoMail,
    startRoute: String? = null,
    prefs: DeskPreferences = rememberDeskPreferences(),
) {
    val navController = rememberNavController()
    val resources = appResources()
    val locales = LocalConfiguration.current.locales
    val accounts = mail.accounts
    val folders = mail.folders
    // the kit builds its navigation graph from the spec: a new one only when what it lists changes
    val spec = remember(locales, accounts, folders, startRoute) { buildShellSpec(resources, accounts, folders, startRoute) }
    val badges = remember(folders) { navBadges(folders) }
    DesktopKitRoot(prefs) { widthClass ->
        DesktopShell(
            spec = spec,
            widthClass = widthClass,
            navController = navController,
            sidebarCollapsed = prefs.sidebarCollapsed,
            onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
            badges = badges,
        ) { route, navigator ->
            val navKind = LocalNavKind.current
            when (route) {
                Routes.FOLDERS -> FoldersScreen(
                    accounts = accounts,
                    folders = folders,
                    onOpen = { folder ->
                        val target = Routes.folder(folder.id)
                        // The sidebar lists the folders itself and switches between them. On a phone or beside a
                        // rail the folder opens on top of this list, and Back returns here.
                        if (navKind == NavKind.Sidebar) navigator.navigate(target) else navController.navigate(target) { launchSingleTop = true }
                    },
                )
                Routes.SETTINGS -> SettingsScreen(prefs)
                Routes.SEARCH -> MessageScreen(
                    title = stringResource(R.string.search_coming_title),
                    icon = DeskIcons.Search,
                    pageTitle = stringResource(R.string.nav_search),
                    description = stringResource(R.string.search_coming_text),
                )
                else -> MailPlaceholder(spec, route)
            }
        }
    }
}

/** A view over the mail before there is mail to show: its name and icon. */
@Composable
private fun MailPlaceholder(spec: ShellSpec, route: String) {
    val item = spec.item(route)
    MessageScreen(
        title = item?.label ?: stringResource(R.string.app_name),
        icon = item?.icon ?: MailIcons.Mail,
        pageTitle = item?.label,
        description = stringResource(R.string.skeleton_description),
    )
}
