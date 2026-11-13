package com.deskmail.demo

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.text.SearchQuery
import com.deskmail.mail.AccountState
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailFolder
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailUndo
import com.deskmail.mail.MailView
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

/**
 * Made-up accounts, folders and messages for the tests and the screenshots: the screens are
 * drawn and exercised with them, without a server and without a database. Reading and starring
 * change the copy in memory; a test can also say what a message's content is ([setContent]), how
 * an account is doing ([setAccountState]), that mail is being fetched ([syncing]), and see what the
 * screens asked for.
 *
 * The addresses use the reserved ".example" domain: they belong to nobody. The messages are dated
 * back from [now], so the list always has mail of today, of this week and of last year.
 *
 * @param empty no account at all: what the app shows before the first one is added.
 */
class DemoMail(private val now: Instant = Instant.now(), empty: Boolean = false) : MailSource {

    private val accountList = mutableStateListOf<MailAccount>().apply {
        if (!empty) {
            add(MailAccount(OUTLOOK, "Outlook", "deniz@outlook.example", server = "outlook.office365.com"))
            add(MailAccount(GMAIL, "Gmail", "deniz@gmail.example", server = "imap.gmail.com"))
        }
    }

    override val accounts: List<MailAccount> get() = accountList

    private val tree: List<MailFolder> = if (empty) emptyList() else listOf(
        MailFolder(11, OUTLOOK, listOf("Inbox"), FolderRole.Inbox),
        MailFolder(12, OUTLOOK, listOf("Drafts"), FolderRole.Drafts),
        MailFolder(13, OUTLOOK, listOf("Sent"), FolderRole.Sent),
        MailFolder(14, OUTLOOK, listOf("Archive"), FolderRole.Archive),
        MailFolder(15, OUTLOOK, listOf("Junk"), FolderRole.Junk),
        MailFolder(16, OUTLOOK, listOf("Deleted"), FolderRole.Trash),
        MailFolder(17, OUTLOOK, listOf("Faturalar"), FolderRole.Other),
        MailFolder(18, OUTLOOK, listOf("Projeler"), FolderRole.Other),
        MailFolder(19, OUTLOOK, listOf("Projeler", "2026"), FolderRole.Other),
        MailFolder(21, GMAIL, listOf("INBOX"), FolderRole.Inbox),
        MailFolder(22, GMAIL, listOf("[Gmail]", "Drafts"), FolderRole.Drafts),
        MailFolder(23, GMAIL, listOf("[Gmail]", "Sent Mail"), FolderRole.Sent),
        MailFolder(24, GMAIL, listOf("[Gmail]", "All Mail"), FolderRole.All),
        MailFolder(25, GMAIL, listOf("[Gmail]", "Spam"), FolderRole.Junk),
        MailFolder(26, GMAIL, listOf("[Gmail]", "Trash"), FolderRole.Trash),
        MailFolder(27, GMAIL, listOf("Seyahat"), FolderRole.Other),
    )

    /** What each message says. */
    private val contents = HashMap<Long, MailContent.Ready>()

    /** What a test says a message's content is, instead. */
    private val overrides = mutableStateMapOf<Long, MailContent>()

    private val stored = mutableStateListOf<MailMessage>().apply { if (!empty) addAll(demoMessages()) }

    /** Every message, newest first. */
    val messages: List<MailMessage> get() = stored

    /** The folders with their counts, which follow what is read. */
    override val folders: List<MailFolder> by derivedStateOf {
        tree.map { folder ->
            val inFolder = stored.filter { it.folderId == folder.id }
            folder.copy(unread = inFolder.count { it.unread }, total = inFolder.size)
        }
    }

    override val loaded: Boolean get() = true

    override val recent: List<MailMessage> get() = stored

    /** Whether mail is being fetched: a test says so. */
    override var syncing: Boolean by mutableStateOf(false)

    /** What was taken off its place, by what would bring it back. */
    private val taken = HashMap<MailUndo, List<MailMessage>>()

    /** What the screens let stand ([commit]). */
    val committed = ArrayList<MailUndo>()

    /** What fetching a file comes to; a test says so. Gone unless it does. */
    var fetched: (MailAttachment) -> FetchedFile = { FetchedFile.Gone }

    /** The files that were asked for. */
    val fetches = ArrayList<MailAttachment>()

    /** What the screens asked for: the views that came to the front, the syncs, the texts asked for again. */
    val shown = ArrayList<MailView>()
    val refreshed = ArrayList<MailView?>()
    val retried = ArrayList<Long>()

    fun message(id: Long): MailMessage? = stored.firstOrNull { it.id == id }

    /** Takes every message away: accounts as they are just after they were added. */
    fun clearMessages() = stored.clear()

    /** The made-up content of a message. */
    fun content(id: Long): MailContent.Ready = contents.getValue(id)

    /** The messages of [view], newest first. */
    fun messages(view: MailView): List<MailMessage> {
        val roles = tree.associate { it.id to it.role }
        return when (view) {
            MailView.Inbox -> stored.filter { roles[it.folderId] == FolderRole.Inbox }
            MailView.Unread -> stored.filter { it.unread && roles[it.folderId] !in OUTSIDE_THE_VIEWS }
            MailView.Starred -> stored.filter { it.starred && roles[it.folderId] !in OUTSIDE_THE_VIEWS }
            is MailView.Folder -> stored.filter { it.folderId == view.id }
            is MailView.Search -> found(view.query)
        }
    }

    /** What a search finds: every word typed begins a word of the message's subject, of the people on it or of its text. Not in Spam. */
    private fun found(query: String): List<MailMessage> {
        if (SearchQuery.match(query) == null) return emptyList()
        val words = SearchQuery.words(query)
        val roles = tree.associate { it.id to it.role }
        return stored.filter { message ->
            roles[message.folderId] != FolderRole.Junk && roles[message.folderId] != FolderRole.All &&
                SearchQuery.matches(words, listOf(message.subject, message.fromName, message.fromAddress, message.to, contents[message.id]?.text.orEmpty()).joinToString(" "))
        }
    }

    override fun observe(view: MailView): Flow<List<MailMessage>> = snapshotFlow { messages(view) }

    override fun cached(view: MailView): List<MailMessage> = messages(view)

    override fun observeMessage(id: Long): Flow<MailMessage?> = snapshotFlow { message(id) }

    override fun cachedMessage(id: Long): MailMessage? = message(id)

    override fun observeContent(id: Long): Flow<MailContent> = snapshotFlow { overrides[id] ?: contents[id] ?: MailContent.Unavailable }

    override fun retryContent(id: Long) {
        retried += id
    }

    override fun markRead(id: Long) = setUnread(id, false)

    override fun toggleUnread(id: Long) {
        message(id)?.let { setUnread(id, !it.unread) }
    }

    override fun toggleStarred(id: Long) {
        message(id)?.let { setStarred(id, !it.starred) }
    }

    override fun markUnread(ids: List<Long>, unread: Boolean) = ids.forEach { setUnread(it, unread) }

    override fun markStarred(ids: List<Long>, starred: Boolean) = ids.forEach { setStarred(it, starred) }

    override suspend fun archive(ids: List<Long>): MailUndo? = relocate(ids, MailUndo.Action.Archived) { message ->
        folderOf(message.accountId, FolderRole.Archive) ?: folderOf(message.accountId, FolderRole.All)
    }

    override suspend fun delete(ids: List<Long>): MailUndo? {
        val before = ArrayList<MailMessage>()
        var forGood = true
        for (id in ids) {
            val message = message(id) ?: continue
            val trash = folderOf(message.accountId, FolderRole.Trash)
            before += message
            if (trash == null || trash.id == message.folderId) {
                stored.remove(message)
            } else {
                forGood = false
                change(id) { it.copy(folderId = trash.id) }
            }
        }
        if (before.isEmpty()) return null
        return MailUndo(if (forGood) MailUndo.Action.DeletedForGood else MailUndo.Action.Deleted, before.map { it.id }).also { taken[it] = before }
    }

    override suspend fun move(ids: List<Long>, folderId: Long): MailUndo? =
        relocate(ids, MailUndo.Action.Moved, folderId) { tree.firstOrNull { it.id == folderId } }

    private fun folderOf(accountId: Long, role: FolderRole): MailFolder? = tree.firstOrNull { it.accountId == accountId && it.role == role }

    private fun relocate(ids: List<Long>, action: MailUndo.Action, folder: Long = 0, target: (MailMessage) -> MailFolder?): MailUndo? {
        val before = ArrayList<MailMessage>()
        for (id in ids) {
            val message = message(id) ?: continue
            val to = target(message) ?: continue
            if (to.id == message.folderId || to.accountId != message.accountId) continue
            before += message
            change(id) { it.copy(folderId = to.id) }
        }
        if (before.isEmpty()) return null
        return MailUndo(action, before.map { it.id }, folder).also { taken[it] = before }
    }

    override suspend fun undo(undo: MailUndo): Int {
        val before = taken.remove(undo) ?: return 0
        for (message in before) {
            val index = stored.indexOfFirst { it.id == message.id }
            if (index >= 0) {
                stored[index] = message
            } else {
                // removed for good, and back: at its place among the others, newest first
                val at = stored.indexOfFirst { it.sentAt < message.sentAt }
                if (at < 0) stored.add(message) else stored.add(at, message)
            }
        }
        return before.size
    }

    override fun commit(undo: MailUndo) {
        taken.remove(undo)
        committed += undo
    }

    override suspend fun fetch(attachment: MailAttachment): FetchedFile {
        fetches += attachment
        return fetched(attachment)
    }

    override fun viewShown(view: MailView) {
        shown += view
    }

    override fun refresh(view: MailView?) {
        refreshed += view
    }

    fun setUnread(id: Long, unread: Boolean) = change(id) { it.copy(unread = unread) }

    fun setStarred(id: Long, starred: Boolean) = change(id) { it.copy(starred = starred) }

    /** Says what a message's content is from now on (being fetched, failed, none); null gives it its own back. */
    fun setContent(id: Long, content: MailContent?) {
        if (content == null) overrides.remove(id) else overrides[id] = content
    }

    /** Says how an account is doing. */
    fun setAccountState(id: Long, state: AccountState, detail: String = "") {
        val index = accountList.indexOfFirst { it.id == id }
        if (index >= 0) accountList[index] = accountList[index].copy(state = state, detail = detail)
    }

    private fun change(id: Long, edit: (MailMessage) -> MailMessage) {
        val index = stored.indexOfFirst { it.id == id }
        if (index < 0) return
        val edited = edit(stored[index])
        if (edited != stored[index]) stored[index] = edited
    }

    // ------------------------------------------------------------------ the mail itself

    private fun demoMessages(): List<MailMessage> {
        val list = ArrayList<MailMessage>()
        var nextId = 100L
        fun add(
            folder: Long,
            from: String,
            address: String,
            subject: String,
            ago: Duration,
            text: String,
            html: String? = null,
            unread: Boolean = false,
            starred: Boolean = false,
            to: String = ME,
            attachments: List<MailAttachment> = emptyList(),
            remoteImages: Boolean = false,
            outgoing: Boolean = false,
            answered: Boolean = false,
            forwarded: Boolean = false,
        ) {
            val id = nextId++
            list += MailMessage(
                id = id,
                accountId = tree.first { it.id == folder }.accountId,
                folderId = folder,
                fromName = from,
                fromAddress = address,
                to = to,
                subject = subject,
                sentAt = now.minus(ago),
                preview = text.trim().replace(WHITESPACE, " ").take(PREVIEW_LENGTH),
                unread = unread,
                starred = starred,
                attachmentCount = attachments.size,
                outgoing = outgoing,
                answered = answered,
                forwarded = forwarded,
            )
            contents[id] = MailContent.Ready(text, html, remoteImages, attachments)
        }

        // Outlook · Inbox
        add(
            11, "Ayşe Yılmaz", "ayse.yilmaz@posta.example", "Cumartesi kahvaltı?", Duration.ofMinutes(25),
            "Selam Deniz,\n\nCumartesi sabahı Moda'da kahvaltıya ne dersin? Zeynep de geliyor, saat 10 gibi buluşuruz diye düşündük. " +
                "Yer ayırtmam gerekiyor, akşama kadar haber verirsen sevinirim.\n\nSevgiler,\nAyşe",
            unread = true,
        )
        add(
            11, "Sahaf Kitabevi", "bulten@sahaf.example", "Kasım bülteni: yeni gelenler ve imza günü", Duration.ofHours(2),
            "Merhaba Deniz, bu ay raflara 140 kitap girdi: ilk baskı şiir kitapları, 1950'lerden dergi ciltleri ve imzalı birkaç roman. " +
                "İmza günü: 21 Kasım Cumartesi, saat 15.00.",
            html = DemoBodies.newsletter, unread = true, remoteImages = true,
        )
        add(
            11, "Mehmet Demir", "mehmet.demir@isyeri.example", "Re: Proje planı (v3)", Duration.ofHours(5),
            "Deniz selam,\n\nPlanın üçüncü sürümü ekte. İki değişiklik var: senkron işi iki haftaya çıktı, bildirimler bir hafta öne geldi. " +
                "Perşembe günü üstünden geçelim mi?\n\nMehmet",
            html = DemoBodies.replyChain, starred = true, to = "$ME, Elif Şahin",
            attachments = listOf(MailAttachment("Proje planı v3.pdf", 284_000, mimeType = "application/pdf")),
            answered = true,
        )
        add(
            11, "Derleme Sunucusu", "ci@ci.example", "[deskmail] feature/skeleton: 3 iş başarılı", Duration.ofHours(21),
            "feature/skeleton dalındaki son çalışma bitti. :core:test başarılı, :app:testDebugUnitTest başarılı, :app:assembleRelease başarılı. " +
                "APK sabit anahtarla imzalandı.",
            html = DemoBodies.buildReport, unread = true,
        )
        add(
            11, "Elektrik Dağıtım", "fatura@elektrik.example", "Kasım 2026 faturanız hazır", Duration.ofDays(2),
            "Sayın Deniz Aksoy, 1002003004 numaralı tesisatınızın Kasım dönemi faturası ektedir. Ödenecek tutar: 893,52 TL. " +
                "Son ödeme tarihi: 27 Kasım 2026.",
            html = DemoBodies.invoice, attachments = listOf(MailAttachment("Fatura-2026-11.pdf", 96_500)),
        )
        add(
            11, "Zeynep Kaya", "zeynep.kaya@posta.example", "Kapadokya gezi notları", Duration.ofDays(3),
            TRAVEL_NOTES, starred = true,
            attachments = listOf(MailAttachment("Göreme sabahı.jpg", 2_480_000), MailAttachment("Ihlara vadisi.jpg", 3_120_000)),
            forwarded = true,
        )
        add(
            11, "İlçe Kütüphanesi", "bilgi@kutuphane.example", "İade tarihi yaklaşıyor", Duration.ofDays(4),
            "Sayın üyemiz,\n\nÜzerinizdeki \"Tutunamayanlar\" adlı kitabın iade tarihi 16 Kasım 2026'dır. " +
                "Süreyi kütüphaneye uğramadan, üye sayfanızdan bir kez uzatabilirsiniz.",
            unread = true,
        )
        add(
            11, "Can Öztürk", "can.ozturk@isyeri.example", "Toplantı tutanağı ve yapılacaklar", Duration.ofDays(6),
            "10 Kasım toplantısı. Katılanlar: Can, Deniz, Elif, Mehmet. İskelet ve imza bitti; hesap ekleme için Outlook kaydı bekleniyor; " +
                "bildirimler için gece denemesi yapılacak.",
            html = DemoBodies.minutes, to = "$ME, Elif Şahin, Mehmet Demir",
        )
        add(
            11, "Apartman Yönetimi", "yonetim@apartman.example", "Aidat ve genel kurul duyurusu", Duration.ofDays(9),
            "Değerli kat malikleri,\n\nOlağan genel kurul toplantımız 29 Kasım Pazar günü saat 14.00'te sığınakta yapılacaktır. " +
                "Aralık ayından itibaren aidat 1.250 TL olarak önerilecektir. Katılamayacak olanların vekâlet vermesini rica ederiz.",
        )

        // Outlook · the other folders
        add(
            12, ME, "deniz@outlook.example", "Re: Cumartesi kahvaltı?", Duration.ofMinutes(8),
            "Ayşe selam,\n\nOlur, gelirim. Saat 10 uygun, yalnız", to = "Ayşe Yılmaz", outgoing = true,
        )
        add(
            13, ME, "deniz@outlook.example", "Re: Proje planı (v2)", Duration.ofHours(19),
            "Senkron için bir hafta az. Klasör listesi, bayraklar ve gövdeler ayrı ayrı sınanmalı; en az iki hafta diyelim.",
            to = "Mehmet Demir", outgoing = true,
        )
        add(
            13, ME, "deniz@outlook.example", "Kitap ayırtma", Duration.ofDays(5),
            "Merhaba,\n\nBültende gördüğüm Varlık 1953 cildini ayırtmak istiyorum. Cumartesi uğrayıp alabilirim.\n\nDeniz Aksoy",
            to = "Sahaf Kitabevi", outgoing = true,
        )
        add(
            13, ME, "deniz@outlook.example", "Genel kurul için vekâlet", Duration.ofDays(8),
            "Merhaba,\n\n29 Kasım'da şehir dışında olacağım. Vekâleti 7 numaradaki Elif Hanım'a verdim, imzalı kâğıdı kendisi getirecek.",
            to = "Apartman Yönetimi", outgoing = true, attachments = listOf(MailAttachment("Vekâlet.pdf", 61_000)),
        )
        add(
            14, "Mehmet Demir", "mehmet.demir@isyeri.example", "Proje planı (v1)", Duration.ofDays(12),
            "İlk taslağı gönderiyorum, tarihler kabaca. Yorumlarını bekliyorum.",
            attachments = listOf(MailAttachment("Proje planı v1.pdf", 251_000)),
        )
        add(
            14, "Zeynep Kaya", "zeynep.kaya@posta.example", "Uçak mı tren mi?", Duration.ofDays(20),
            "Ankara'ya trenle gidelim diyorum; kapıdan kapıya süre aynı çıkıyor, üstelik yolda çalışılır. Ne dersin?",
        )
        add(
            15, "ÖDÜL MERKEZİ", "kazandiniz@odul.example", "TEBRİKLER! Ödülünüz sizi bekliyor", Duration.ofHours(30),
            "Tebrikler! Ödülünüz hazır. Ödülünüzü almak için 24 saat içinde bilgilerinizi doğrulayın.",
            html = DemoBodies.junk, unread = true, remoteImages = true,
        )
        add(
            17, "Elektrik Dağıtım", "fatura@elektrik.example", "Ekim 2026 faturanız hazır", Duration.ofDays(33),
            "Sayın Deniz Aksoy, Ekim dönemi faturanız ektedir. Ödenecek tutar: 742,10 TL.",
            attachments = listOf(MailAttachment("Fatura-2026-10.pdf", 95_200)),
        )
        add(
            17, "Doğalgaz", "e-fatura@dogalgaz.example", "Aralık 2025 dönemi faturanız", Duration.ofDays(330),
            "Sayın abonemiz, Aralık 2025 dönemi faturanız ektedir. Ödenecek tutar: 1.914,00 TL.",
            attachments = listOf(MailAttachment("Fatura-2025-12.pdf", 88_400)),
        )
        add(
            19, "Elif Şahin", "elif.sahin@isyeri.example", "Tablet düzeni için notlar", Duration.ofDays(1),
            "Deniz, tablet düzenini denedim. Yatayda liste ile okuma bölmesi yan yana çok iyi; dikeyde listeden iletiye geçiş daha doğal. " +
                "Ayrıntı paneli varsayılan olarak kapalı gelsin derim.",
            unread = true,
        )
        add(
            19, "Can Öztürk", "can.ozturk@isyeri.example", "Sağlayıcı tablosu taslağı", Duration.ofDays(7),
            "Outlook ve Gmail satırlarını yazdım: sunucular, giriş türü, gönderileni kimin kaydettiği. Gözden geçirir misin?",
            attachments = listOf(MailAttachment("saglayicilar.csv", 4_200)),
        )

        // Gmail
        add(
            21, "Zeynep Kaya", "zeynep.kaya@posta.example", "Bilet tamam", Duration.ofHours(1),
            "Biletleri aldım, 28 Kasım 08:15 treni. Seninkini de iletiyorum. Söğütlüçeşme'de 07:45'te buluşalım.",
            unread = true,
        )
        add(
            21, "Hızlı Tren Bilet", "bilet@tren.example", "E-biletiniz: İstanbul → Ankara, 28 Kasım", Duration.ofMinutes(95),
            "Elektronik bilet. İstanbul (Söğütlüçeşme) → Ankara, 28 Kasım 2026 Cumartesi, kalkış 08:15, vagon 4, koltuk 12A.",
            html = DemoBodies.ticket, unread = true, attachments = listOf(MailAttachment("Bilet-12A.pdf", 132_000)),
        )
        add(
            21, "Fotoğraf Kulübü", "duyuru@fotografkulubu.example", "Kasım buluşması: sis ve sabah ışığı", Duration.ofDays(2),
            "Bu ayki buluşmada sisli havada çekim konuşulacak. Yanınızda en sevdiğiniz üç kareyi getirin.",
        )
        add(
            21, "Ayşe Yılmaz", "ayse.yilmaz@posta.example", "Tarif", Duration.ofDays(5),
            "Geçen hafta sorduğun mercimek köftesinin tarifi:\n\n1 su bardağı kırmızı mercimek, yarım su bardağı ince bulgur, " +
                "1 kuru soğan, salça, kimyon, maydanoz ve yeşil soğan. Mercimeği haşla, bulguru içine at ve demlendir.",
            starred = true,
        )
        add(
            21, "Yapı Kooperatifi", "bilgi@kooperatif.example", "2025 yılı faaliyet raporu", Duration.ofDays(340),
            "Sayın ortağımız, 2025 yılı faaliyet raporu ve denetim kurulu raporu ektedir.",
            attachments = listOf(MailAttachment("Faaliyet raporu 2025.pdf", 1_240_000)),
        )
        add(
            23, ME, "deniz@gmail.example", "Re: Bilet tamam", Duration.ofMinutes(50),
            "Harika, teşekkürler. 07:45'te oradayım.", to = "Zeynep Kaya", outgoing = true,
        )
        add(
            27, "Otel Rezervasyon", "rezervasyon@otel.example", "Rezervasyon onayı: Ankara, 28-29 Kasım", Duration.ofDays(1),
            "Rezervasyonunuz onaylandı. Giriş: 28 Kasım 14.00, çıkış: 29 Kasım 12.00. Kahvaltı dâhil, iki kişi.",
            attachments = listOf(MailAttachment("Rezervasyon.pdf", 74_000)),
        )

        // Gmail's "All Mail" holds every message of the account once more
        list.filter { it.accountId == GMAIL }.forEach { message ->
            val copy = message.copy(id = nextId++, folderId = 24)
            list += copy
            contents[copy.id] = contents.getValue(message.id)
        }

        return list.sortedByDescending { it.sentAt }
    }

    companion object {
        const val OUTLOOK = 1L
        const val GMAIL = 2L
        const val ME = "Deniz Aksoy"

        /** Mail in these is not counted as unread or starred in the views over every account. */
        private val OUTSIDE_THE_VIEWS = setOf(FolderRole.All, FolderRole.Junk, FolderRole.Trash)

        private val WHITESPACE = Regex("\\s+")

        /** How long a list's preview of a message is here. */
        private const val PREVIEW_LENGTH = 140

        /** Long enough to scroll under the bars on every device. */
        private val TRAVEL_NOTES = """
            Deniz merhaba,

            Söz verdiğim gibi Kapadokya notlarını yazıyorum. Fotoğrafların ikisini ekledim, kalanı için bağlantı gönderirim.

            1. gün: Göreme
            Sabah beşte kalkıp gün doğumunu izledik. Balonlar tam güneşle birlikte yükseliyor; en iyi yer Âşıklar Tepesi, ama kalabalık. Biraz aşağıdaki patikada kimse yok ve manzara aynı. Kahvaltıyı köy meydanındaki fırında yaptık.

            Öğleden sonra Açık Hava Müzesi'ne gittik. Karanlık Kilise'ye ayrı bilet gerekiyor ama freskler buna değer. Müzeden çıkınca Kılıçlar Vadisi'nden yürüyerek döndük; yol bir buçuk saat sürüyor, yanına su al.

            2. gün: Ihlara
            Vadiye Ihlara köyünden girip Belisırma'ya kadar yürüdük, yedi kilometre kadar. Dere kenarındaki çardaklarda öğle yemeği yeniyor. Dönüşte Selime Katedrali'ne tırmandık; ayakkabının tabanı kaymasın.

            3. gün: Avanos ve Ürgüp
            Avanos'ta çömlek atölyesinde çarkın başına oturduk, benimki kâseden çok tabağa benzedi. Kızılırmak kıyısında yürüyüş yolu yeni yapılmış. Akşam Ürgüp'te kaldık.

            Pratik notlar
            - Kasımda sabahlar sıfırın altında, öğlen güneş yakıyor: kat kat giyin.
            - Müze kartı varsa çoğu yere ücretsiz giriliyor.
            - Araç kiralamak şart değil ama Ihlara için çok kolaylık.
            - Balon uçuşları rüzgâra göre iptal olabiliyor; ilk sabaha ayırt ki yedek günün kalsın.

            Bir dahaki sefere baharda gidelim, vadiler yeşilken.

            Sevgiler,
            Zeynep
        """.trimIndent()
    }
}
