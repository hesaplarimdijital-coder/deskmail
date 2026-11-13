package com.deskmail

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import com.deskmail.core.log.AppLog
import com.deskmail.core.provider.Autoconfig
import com.deskmail.data.AccountSetup
import com.deskmail.data.AccountStore
import com.deskmail.data.ComposeWords
import com.deskmail.data.DeviceSecretBox
import com.deskmail.data.LiveComposer
import com.deskmail.data.LiveMail
import com.deskmail.data.MailSend
import com.deskmail.data.MailSync
import com.deskmail.db.DeskMailDatabase
import com.deskmail.log.LogFile
import com.deskmail.mail.AccountActions
import com.deskmail.net.DnsLookup
import com.deskmail.net.HttpsText
import com.deskmail.notify.FrontViews
import com.deskmail.notify.MailNotifier
import com.deskmail.notify.NotifyActions
import com.deskmail.notify.NotifyStore
import com.deskmail.settings.AppSettings
import com.deskmail.ui.mail.WebViewWarmUp
import com.deskmail.work.SendWorker
import com.deskmail.work.SyncWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The process: what every window of the app shares. The log starts with the process, so that
 * whatever goes wrong afterwards is written down; the rest (the database, the mail, the sync) is
 * created when it is first asked for.
 */
class DeskMailApplication : Application(), Configuration.Provider {

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

    private val sync: MailSync by lazy {
        // whoever has an account synced, new mail is told of afterwards
        MailSync(database, accountStore, log, background).also { it.onSynced = { accountId -> notifier.check(accountId) } }
    }

    /** Which lists of mail the windows have in front: new mail in one of them needs no notification. */
    val front = FrontViews()

    /** How far each inbox's new mail has been told of, and whether the user was asked to allow notifications. */
    val notifyStore: NotifyStore by lazy { NotifyStore(getSharedPreferences(NotifyStore.FILE, Context.MODE_PRIVATE)) }

    /** Tells of new mail with the system's notifications, and takes them down as the mail is read. */
    val notifier: MailNotifier by lazy { MailNotifier(this, database, notifyStore, front, log).also { it.watch(background) } }

    /** What the buttons of a notification do. */
    val notifyActions: NotifyActions by lazy { NotifyActions(mail, notifier, log, tellServers = { SyncWorker.tellServers(this) }) }

    /** The mail as the windows show it. */
    val mail: LiveMail by lazy { LiveMail(database, sync, background, File(cacheDir, FILES_DIRECTORY)) }

    /** Adding, reconnecting and removing accounts. */
    val accounts: AccountActions by lazy {
        val autoconfig = Autoconfig(fetch = HttpsText::get, mailHosts = DnsLookup::mailHosts, services = DnsLookup::services, log = log)
        AccountSetup(
            database, accountStore, sync, autoconfig, log, background,
            startSync = { accountId ->
                mail.sync(accountId)
                // from now on there is mail to look for in the background
                lookForMail(restart = false)
            },
            onRemoved = { accountId, folderIds ->
                notifier.forget(accountId, folderIds)
                // the last account gone, there is nothing left to look for
                lookForMail(restart = false)
            },
        )
    }

    /**
     * Has the mail looked for in the background as often as the settings say, or not at all.
     * Nothing is scheduled while there is no account: there is nothing to look for. And while
     * nothing is scheduled and nothing is wanted, the scheduler is left alone.
     *
     * @param restart the interval was just chosen: it counts from now.
     */
    fun lookForMail(restart: Boolean) {
        background.launch {
            try {
                val wanted = if (database.accounts().all().isEmpty()) 0 else settings.checkMinutes
                val scheduled = notifyStore.scheduledMinutes
                if (wanted == 0 && scheduled == 0) return@launch
                SyncWorker.schedule(this@DeskMailApplication, wanted, restart = restart || wanted != scheduled)
                notifyStore.scheduledMinutes = wanted
                if (wanted != scheduled) {
                    log.info("app", if (wanted > 0) "new mail is looked for every $wanted minutes" else "new mail is not looked for in the background")
                }
            } catch (e: Exception) {
                log.error("app", "the background look for mail could not be scheduled", e)
            }
        }
    }

    /** Where the files attached to messages that have not left yet are kept. */
    private val outboxFiles: File get() = File(filesDir, OUTBOX_DIRECTORY)

    /** Sends what waits in the outbox; the background work that does so ([SendWorker]) finds it here. */
    val sender: MailSend by lazy { MailSend(database, accountStore, sync, log, File(cacheDir, OUTGOING_DIRECTORY), outboxFiles) }

    /** Writing and sending, as the windows see it. */
    val composer: LiveComposer by lazy {
        LiveComposer(
            db = database,
            sync = sync,
            scope = background,
            files = outboxFiles,
            scheduler = SendWorker.scheduler(this),
            fetchFile = { mail.fetch(it) },
            words = ::composeWords,
            recover = { sender.recover() },
            log = log,
            undoMs = { settings.undoSendSeconds * 1000L },
        )
    }

    /** WorkManager is set up when it is first asked for (the manifest takes its own start-up out). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    /** The words a reply and a forwarded message begin with, in the language the device speaks now. */
    private fun composeWords(): ComposeWords {
        val dates = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
        return ComposeWords(
            wrote = { date, who -> getString(R.string.compose_wrote, date, who) },
            forwarded = getString(R.string.compose_forwarded),
            from = getString(R.string.detail_from),
            sent = getString(R.string.detail_date),
            subject = getString(R.string.compose_subject),
            to = getString(R.string.detail_to),
            cc = getString(R.string.detail_cc),
            date = { dates.format(it) },
        )
    }

    /** Has the device's WebView ready before the first message is opened. */
    val webWarmUp: WebViewWarmUp by lazy { WebViewWarmUp(log) }

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

        /** Under the cache directory: the files of messages that were fetched to be opened, saved or shared. */
        const val FILES_DIRECTORY = "files"

        /** Under the cache directory: a message is written out here just before it is handed to its server. */
        private const val OUTGOING_DIRECTORY = "outgoing"

        /** Under the app's files: what is attached to messages that have not left yet. Not a cache: it must still be there when they go. */
        private const val OUTBOX_DIRECTORY = "outbox"

        /** How much of earlier runs the log screen shows. */
        private const val EARLIER_LINES = 1000

        @Volatile
        private var current: DeskMailApplication? = null
        private var crashLogInstalled = false
    }
}

/** The process this context belongs to. */
val Context.deskMail: DeskMailApplication get() = applicationContext as DeskMailApplication
