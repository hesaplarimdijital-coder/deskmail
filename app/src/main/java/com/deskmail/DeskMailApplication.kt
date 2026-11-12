package com.deskmail

import android.app.Application
import android.content.Context
import android.os.Build
import com.deskmail.core.log.AppLog
import com.deskmail.core.provider.Autoconfig
import com.deskmail.data.AccountSetup
import com.deskmail.data.AccountStore
import com.deskmail.data.DeviceSecretBox
import com.deskmail.data.LiveMail
import com.deskmail.data.MailSync
import com.deskmail.db.DeskMailDatabase
import com.deskmail.log.LogFile
import com.deskmail.mail.AccountActions
import com.deskmail.net.DnsLookup
import com.deskmail.net.HttpsText
import com.deskmail.settings.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * The process: what every window of the app shares. The log starts with the process, so that
 * whatever goes wrong afterwards is written down; the rest (the database, the mail, the sync) is
 * created when it is first asked for.
 */
class DeskMailApplication : Application() {

    /** What the app did and what went wrong; see the log screen under Settings. */
    val log = AppLog()

    private val logFile: LogFile by lazy { LogFile(File(filesDir, "log")) }

    val settings: AppSettings by lazy { AppSettings(getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE), log) }

    /**
     * Where what outlives the windows runs: the sync, and the watching of the database. A failure
     * in it is written to the log and ends that piece of work, not the app.
     */
    val background = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> log.error("app", "background work failed", error) },
    )

    /** The accounts and what is kept of their mail. */
    val database: DeskMailDatabase by lazy { DeskMailDatabase.open(this) }

    private val accountStore: AccountStore by lazy { AccountStore(database, DeviceSecretBox(), log) }

    private val sync: MailSync by lazy { MailSync(database, accountStore, log, background) }

    /** The mail as the windows show it. */
    val mail: LiveMail by lazy { LiveMail(database, sync, background) }

    /** Adding, reconnecting and removing accounts. */
    val accounts: AccountActions by lazy {
        val autoconfig = Autoconfig(fetch = HttpsText::get, mailHosts = DnsLookup::mailHosts, services = DnsLookup::services, log = log)
        AccountSetup(database, accountStore, sync, autoconfig, log, background, startSync = { mail.sync(it) })
    }

    override fun onCreate() {
        super.onCreate()
        // the end of earlier runs first, then everything from now on goes to the file as well
        logFile.loadTail(EARLIER_LINES) { log.preload(it) }
        log.sink = logFile::append
        log.info("app", "started: " + describe())
        current = this
        installCrashLog()
    }

    /** The app's version and the device, as the first line of every run and of a shared log. */
    fun describe(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return "DeskMail ${info.versionName} (${info.longVersionCode}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, ${Locale.getDefault().toLanguageTag()}, ${ZoneId.systemDefault()}"
    }

    /** The whole log on disk as one file under the cache, for sharing. */
    fun exportLog(): File {
        val target = File(File(cacheDir, SHARE_DIRECTORY), "deskmail-log.txt")
        logFile.exportTo(target, header = listOf(describe(), ""))
        return target
    }

    /** Empties the log, in memory and on disk. */
    fun clearLog() {
        logFile.clear()
        log.clear()
    }

    /** Writes an uncaught exception into the log before the system ends the process as it always does. */
    private fun installCrashLog() {
        if (crashLogInstalled) return
        crashLogInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            current?.let { app ->
                app.log.error("crash", "uncaught exception in thread ${thread.name}", error)
                app.logFile.flush()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** Under the cache directory; what FileProvider may hand to other apps (res/xml/file_paths.xml). */
        const val SHARE_DIRECTORY = "share"

        /** How much of earlier runs the log screen shows. */
        private const val EARLIER_LINES = 1000

        @Volatile
        private var current: DeskMailApplication? = null
        private var crashLogInstalled = false
    }
}

/** The process this context belongs to. */
val Context.deskMail: DeskMailApplication get() = applicationContext as DeskMailApplication
