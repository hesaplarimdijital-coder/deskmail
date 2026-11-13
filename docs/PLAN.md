# DeskMail planı

12 Kasım 2026'da kararlaştırılan plan. Değişen karar burada güncellenir; yapılan iş `CHANGELOG.md`'de,
kitten istenenler `docs/KIT-ISTEKLERI.md`'de durur.

## Kararlar

| Konu | Karar |
| --- | --- |
| Sağlayıcılar | Outlook.com (OAuth2) ve Gmail (uygulama şifresi). Önce bunlar denenir. |
| Protokoller | Yalnızca IMAP ve SMTP. POP3, EWS, ActiveSync, Graph yok. |
| IMAP/SMTP kitaplığı | Angus Mail 2.0.5. Thunderbird/K-9'un yığını kitaplık olarak yayımlanmıyor; Commons Net yalnızca komut düzeyinde. |
| OAuth | Kendi küçük PKCE akışı; tarayıcı düz Intent'le Custom Tab olarak açılır. MSAL, AppAuth, androidx.browser yok. |
| Veritabanı | Room 3.0.3, platformun SQLite'ı, FTS4. Sorun çıkarsa Room 2.8.5. |
| Uygulama kimliği | `com.deskmail`. Değişmez. |
| İmza | Sabit anahtar (PKCS12, RSA 4096); GitHub Secrets'ta ve sahibinin parola yöneticisinde. Depoda ve günlüklerde yok. |
| Dağıtım | Etiket → iş akışı imzalı APK'yı sürüme ekler → Obtainium. Her PR'dan sonra ön sürüm (`0.1.0-alpha.N`). |
| Sürüm | SemVer, 0.x; `versionCode` her sürümde bir artar. |
| SDK | `minSdk` 34, `compileSdk` 37, `targetSdk` 36. |
| Yedekleme | Kapalı (`allowBackup=false`). Posta sunucuda; kimlik bilgileri cihazdan çıkmaz. |
| Senkron | Eklentisiz temel yol; CONDSTORE varsa hızlandırır; QRESYNC 0.1.0'da yok. |
| Sunucu kimliği (TLS) | Sertifikanın bağlanılan sunucuya ait olduğunu uygulama kendisi denetler (`ServerIdentity`: RFC 6125, yalnızca SAN, joker tek etiket). Angus Mail bu denetimi platforma "LDAPS" adıyla bırakıyor; Java'nın TLS'i bu adı tanır, Android'inki tanımaz ve denetim sessizce yapılmazdı. Sertifika zincirinin güvenilir bir kökten gelmesi platformun denetimi olarak kalır. |
| Angus Mail'in kaynak dosyaları | Kitaplığın sağlayıcı listesi, içerik işleyici tablosu ve kod çözücü bulucusu jar içindeki kaynak dosyalarıdır. Android paketlemesine güvenmemek için IMAP deposu doğrudan kurulur, çok parçalı iletinin parçaları ileti yapısından alınır, kod çözücülerin (base64, quoted-printable) sınıfı adıyla verilir (`MailLibrary`). CI, APK'ya giren bu dosyaların listesini günlük dalına yazar (`apk-resources.txt`). |
| Okuma ve cam | Cam iletinin üstünde de kalır: kabuğun camı bulanıkken iletinin WebView'ı kendi donanım katmanına çizilir (`webLayerFor`); bir kez çizilir, pencere ve her bulanıklık hazır resmi o katmandan alır. Kit bulanıklık için içeriği her karede birkaç kez çizer; doğrudan pencereye çizen WebView buna dayanmaz ve kaydırılırken karoları gidip gelir (S24, alpha.1; `docs/KIT-ISTEKLERI.md`, 20). Katmana sığmayan uzun iletide (`LayerLimits`: cihazın en büyük dokusu, 64 MB) katman yoktur ve o ileti ekrandayken cam kapanır (`shellGlass`). alpha.2'nin yolu (ileti açıkken cam hep kapalı) Denemeler'de "İletinin üstünde cam" kapatılarak geri gelir. |
| WebView'ın hazırlanması | Uygulamanın ilk ekranı çizildikten sonra bir WebView kurulup boş bir sayfa yüklenir ve bırakılır (`WebViewWarmUp`): ilk iletinin açılışı motorun yüklenmesini beklemez. Süre günlüğe yazılır. |
| Bekleyen işlemler | Kullanıcının yaptığı (okundu, yıldız, taşıma, silme) önce cihazda görünür ve `pending_op` tablosuna yazılır; sunucuya arka planda, klasör klasör, önce bayraklar sonra taşımalar sonra silmeler sırasıyla söylenir (`ChangeSync`). Her eşitleme önce bekleyenleri gönderir, sonra sunucuyu okur. Bayrak bekleyen iletide sunucunun eski bayrağı kullanıcınınkini ezmez. Taşınan ve silinen ileti, sunucuya söylenene kadar hiçbir listede görünmez; sunucu yeni UID'yi söylerse (UIDPLUS) satır metniyle birlikte yeni klasörüne taşınır, söylemezse silinir ve o klasörün eşitlemesi getirir. |
| Geri alma | Taşıma ve silme sunucuya hemen söylenmez: bildirim ekrandayken (ve en çok 15 saniye) bekler; geri almak kuyruktan çıkarmaktır. Bildirim gidince söylenir. Sonradan geri alma (Ctrl+Z) ters yönde yeni bir taşımadır ve iletinin yeni UID'sinin bilinmesine bağlıdır. Kalıcı silme söylendikten sonra geri alınamaz. |
| Arşiv | Hesabın `\Archive` klasörü; yoksa `\All` (Gmail: "Tüm postalar"a taşımak, Gelen etiketini kaldırmaktır). İkisi de yoksa arşivle düğmesi görünmez. |
| Ekler | İstenince iner (`BODY.PEEK[parça]`, okundukça akar), önbellek dizininde bir hafta kalır. Açma ve paylaşma FileProvider ile, kaydetme Storage Access Framework ile; depolama izni yok. |
| Giden kutusu ve gönderim | Yazılan ileti `outbox` tablosundadır: taslak, bekliyor, gönderiliyor, gönderilemedi. Yazılan metin duruma dokunmadan ve yalnızca ileti yazılabilir durumdayken kaydedilir; durum geçişleri tek sorguluk koşullu güncellemelerdir, böylece geç gelen bir kayıt, sıraya alınmış iletiyi ne değiştirir ne geri getirir. Gönderimi WorkManager yapar (tek adlı iş, ağ koşulu, yenisi eskisinin yerine geçer; iş hep en erken bekleyen iletinin zamanına kurulur). Sunucuya ulaşılamazsa ileti bekler ve iş artan aralıklarla yeniden dener; sunucu reddederse ileti "gönderilemedi" olur. Sunucuya verilmeye başlanan ileti yarıda bırakılmaz; uygulama o sırada ölürse ileti bir sonraki çalışmada "gönderilemedi (yarıda kesildi)" olur ve kendiliğinden ikinci kez gönderilmez. Bu bakış her çalışmada bir kez, o çalışma bir şey göndermeden önce yapılır. |
| Gönderiyi geri alma | Gönder, iletiyi `sendAfter = şimdi + süre` ile sıraya koyar (süre ayarlardan 5, 10, 15 sn). Pencere o süre boyunca "Geri al"lı bildirimi gösterir; geri almak sıradan çıkarmaktır. Bildirim giderse (süre doldu, kaydırıldı, yerine başka bildirim geldi) pencere iletiyi hemen gönderttirir; pencere kapanırsa ileti süresi dolunca kendiliğinden gider. |
| Gönderilenin kopyası | Sağlayıcı tablosu söyler (`savesSentItself`): Gmail ve Outlook kendileri kaydeder, uygulama yazmaz. Ötekilerde gönderilen ileti, gizli alıcılarıyla, Gönderilmiş klasörüne APPEND edilir. |
| İletinin yazılması | Gönderilen ileti uygulamanın kendi yazıcısıyla yazılır (`MessageWriter`: RFC 5322 başlıkları, RFC 2047 sözcükleri, quoted-printable metin, base64 ekler, RFC 2231 dosya adları) ve SMTP'ye dosyadan akıtılır. Angus Mail'in `MimeMessage` kurucuları içerik işleyici tablosuna dayanır; o tablo jar içindeki kaynak dosyalarıdır ve Android paketlemesine güvenilmez. Yalnızca düz metin; biçimli yazma Aşama 2'de. |
| Arama | Ara ekranı ayrı bir ekran değil, posta listesinin bir görünümüdür (`MailView.Search`): bulunanlar canlı bir sorgudur, liste, okuma bölmesi, süzgeçler, ileti işlemleri ve kısayollar aynen çalışır. Dizin FTS4'tür (katlanmış konu, kişiler, metin); sorguyu `SearchQuery` kurar. Yalnızca cihazdaki iletiler aranır; "Tüm postalar"daki kopyalar tek gösterilir. |
| Bildirim: önce yedek yol | Brifin iki yolundan (ön plan hizmetinde IMAP IDLE; WorkManager ile periyodik denetim) önce ikincisi kuruldu: cihazsız sınanabilen ve tek başına işe yarayan o. Yeni posta her eşitlemenin sonunda aranır (`MailSync.onSynced` → `MailNotifier.check`), kim eşitlerse eşitlesin; IDLE geldiğinde aynı kancaya bağlanır. Bir iletinin "yeni" olması, gelen kutusunun UID işaretinin üstünde olmasıdır (`NotifyStore`, `UIDVALIDITY` ile); işaret ilk bakışta kurulur, o yüzden eski posta bildirilmez. |
| Bildirim: sıklık | Şimdilik tek ayar (`AppSettings.checkMinutes`: 15, 30, 60, 0 = elle). Şemadaki hesap başına `notifyMode` alanı "Anlık" geldiğinde kullanılacak. |
| Bildirimden yapılanlar | Okundu, Arşivle, Sil: alıcı (`NotifyActionReceiver`) cihazda hemen yapar ve `pending_op`'a yazar (geri alma beklemesi yok), sunucuya ayrı bir WorkManager işi söyler (`SyncWorker.tellServers`). Bildirime dokunmak iletiyi birleşik Gelen'de açar: o ekran her düzende ve klasörler okunmadan da vardır. |
| Bildirimden gelen istek | "İletiyi aç" isteği (`OpenRequest`) dışa açık etkinliğe gelir; onu her uygulama gönderebilir. Bu yüzden istek, kurulumda bir kez üretilen ve yalnızca uygulamanın kendi bildirimlerinin taşıdığı bir belirteçle kabul edilir (`NotifyStore.token`). Belirteçsiz ya da okunamayan istek yok sayılır: başka bir uygulama DeskMail'e ileti açtıramaz, dolayısıyla okundu da yaptıramaz. |
| Başka uygulamalardan gelen istek | `mailto:` (RFC 6068) ve paylaşım: yalnızca formun gösterdiği alanlar alınır (alıcılar, konu, metin; başka başlık adları atılır, satır kırma denemeleri tek satır olur). Dosyalar yalnızca başka uygulamaların `content://` sağlayıcılarından alınır; dosya yolu (`file://`) ve uygulamanın kendi sağlayıcısı alınmaz. Hiçbir şey kendiliğinden gönderilmez. |
| Kit eksikleri | Kite yazılır; uygulamada yamanmaz (`docs/KIT-ISTEKLERI.md`). |
| GitHub | PR'ları CI yeşilken Claude birleştirir. Etiketi ve sürümü Barış açar (Releases'ta `main` üzerinde): Claude'un oturumu etiket ve sürüm açamıyor (12 Kasım 2026'da denendi, ikisi de reddedildi). Force-push ve geçmişi yeniden yazma yok. |

## Mimari

- **`:core`** (saf Kotlin/JVM): IMAP/SMTP oturumu, MIME, HTML temizleme, sağlayıcı tablosu (farkların
  tek yeri), ayar bulma, PKCE ve jeton istemcisi, senkron kararları, IDLE döngüsü, günlük maskeleme.
- **`:app`** (Android): Room, kimlik kasası (Keystore, AES-GCM), ön plan hizmeti ve işler, bildirimler,
  arayüz (yalnızca desktop-kit).
- **Araçlar** desktop-kit ile aynı: AGP 9.4.1, Gradle 9.8.2, Kotlin 2.4.20, Robolectric 4.17 (API 36),
  Java 21; ek olarak KSP 2.3.13.
- **Çalışma zamanı:** desktop-kit 2.4.0, Angus Mail 2.0.5, jsoup 1.24.1, Room 3.0.3, WorkManager 2.12.0,
  activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0.
- **Test:** JUnit 4.13.2, GreenMail 2.1.15 (IDLE, MOVE, XOAUTH2 girişi var; CONDSTORE ve SPECIAL-USE
  yok, onlar için betikli sahte sunucu).
- **Bilerek yok:** security-crypto, DI çatısı, DataStore, OkHttp, görsel kitaplığı, androidx.webkit.

## Veri modeli

Room 3; şema dışa aktarılır, v1'den itibaren göç testi vardır.

- `account`: adres, sağlayıcı, sunucular, giriş türü, senkron penceresi, bildirim kipi, imza, durum.
- `credential`: şifre ya da jetonlar; Keystore anahtarıyla AES-GCM.
- `folder`: yol, rol, UIDVALIDITY, UIDNEXT, HIGHESTMODSEQ, sayaçlar.
- `message`: (klasör, UID) tekil; Message-ID, References, adresler, konu, tarihler, bayraklar, önizleme,
  gövde durumu.
- `body`, `attachment`: düz metin, temizlenmiş HTML, ek parçaları.
- `message_fts`: FTS4; konu, kişiler ve gövde Türkçe harfler katlanarak yazılır.
- `pending_op` (şema 2): yerelde yapılan işlemlerin kuyruğu; ileti, tür (Seen, Flagged, Move, Expunge), değer, hedef
  klasör, en erken gönderim zamanı. Çevrimdışı çalışma ve geri al buradan.
- `outbox`, `outbox_file` (şema 3): yazılan iletiler ve dosyaları. Durum (Draft, Queued, Sending, Failed), yazılan
  alanlar, Message-ID, yanıtlanan iletinin kimliği ve dizisi, kaynak ileti (gönderilince yanıtlandı/iletildi diye
  işaretlenir), `sendAfter` (gönderiyi geri alma süresi), sunucunun hata yanıtı. Dosyaların kopyası uygulamanın
  kendi deposunda durur (önbellekte değil: ileti gidene kadar orada olmalı).
- Ayrı bir adres tablosu yok: alıcı önerileri en yeni 500 iletinin adreslerinden okunur.

## Senkron

1. Klasör listesi: roller LIST özniteliklerinden (`\Sent`, `\Drafts`, `\Trash`, `\Junk`, `\Archive`,
   `\All`); yoksa sağlayıcı tablosundaki TR/EN ad listesinden; hesap ayarından elle değişir.
2. UIDVALIDITY değiştiyse o klasörün önbelleği silinir.
3. Yeni iletiler: son bilinen UID'den sonrası.
4. Pencere (varsayılan 30 gün; 7/30/90/tümü): sunucudaki UID kümesiyle karşılaştırılır, sunucuda
   olmayan yerelden silinir.
5. Bayraklar: CONDSTORE varsa yalnızca değişenler, yoksa penceredekilerin hepsi.
6. Gövdeler: penceredeki metin parçaları boyut sınırıyla iner, temizlenir, dizine yazılır. Ekler ve
   eski iletiler istenince gelir.

- **Nasıl yürür:** dört iş, her biri kendi bağlantısında, biri ötekini bekletmez (`MailSync`):
  - cihazda yapılanlar: bekleyen bayraklar, taşımalar, silmeler sunucuya söylenir; bekleyen yoksa bağlantı
    kurulmaz. Liste işi de kendi bağlantısında önce bunu yapar.
  - listeler: klasör listesi ve sayıları, gelen kutusu, istenen klasör; her klasörün en yeni 20
    iletisinin metni de bu sırada iner. Hesap başına aynı anda bir tane.
  - kalan metinler: liste işi bitince arka planda, klasör klasör, 50'şer; liste yeniden sorulmaz.
  - açılan iletinin metni, istenen ek: cihazda yoksa sırası beklenmeden.
  - Klasörün UID'leri anlam değiştirdiyse (UIDVALIDITY) son iki iş hiçbir şey almaz; sıradaki liste işi
    baştan başlar.
- Hesabın durumu her işin sonunda yazılır: çalışıyor, yeniden giriş gerekiyor (kimlik hatası ya da
  saklanan şifre açılamıyor), sunucuya ulaşılamadı, hata (sunucunun yanıtıyla).
- alpha.2'deki tetikleyiciler: ekranın öne gelmesi ve klasörü açma (son iki dakikada eşitlenmediyse),
  elle yenileme (F5, Eşitle düğmeleri), hesap ekleme ve yeniden bağlanma.
- Tetikleyiciler (hedef): IDLE olayı, klasörü açma, elle yenileme, bekleyen işlem sonrası, periyodik iş.
- İki cihaz: yerel işlem hemen görünür, `pending_op` sırasıyla sunucuya gider; doğrunun kaynağı sunucudur.
- Gmail: `\All` birleşik görünüme girmez; arşiv Gelen'den çıkarmadır; gönderilen eklenmez.
- Outlook: gönderilen ve arşiv davranışı ilk gerçek bağlantıda günlükten doğrulanıp tabloya yazılır.
- Ayar bulma sırası: uygulamadaki sağlayıcı tablosu → ISPDB → MX kaydıyla ISPDB → SRV (RFC 6186) → elle.
  Dışarıya yalnızca alan adı gider. MX ve SRV yanıtlarını cihazın çözümleyicisi getirir
  (`DnsResolver.rawQuery`), `:core` okur (`DnsAnswer`).
- Gmail'in "Yıldızlı" ve "Önemli" klasörleri (`\Flagged`, `\Important`) görünümdür; cihazda tutulmaz.
  İleti tutmayan kap klasörler de (`[Gmail]`) listelenmez.

## Bildirim

- `specialUse` ön plan hizmeti; "Anlık" seçili hesap varsa çalışır; açılışta ve uygulama güncellenince
  yeniden başlar.
- Hesap başına Gelen'de bir IDLE; yenileme 24 dakikada, tüm hesaplar tek alarmda. Android 17'de
  dinleyicili `setExactAndAllowWhileIdle` (izin istemez; süreç yaşadıkça geçerli, hizmet yaşatır), 14–16'da
  `setAndAllowWhileIdle` (uykuda 15 dakikaya kadar gecikebilir).
- **Okuma zaman aşımı (plandan sapma, beta.1):** "yenileme süresi + 2 dakika" yerine öteki bağlantılardaki
  60 saniye. Posta kitaplığı (Angus) IDLE beklerken hiçbir şey okunmadan dolan zaman aşımını sessizlik
  sayıp beklemeyi sürdürür; ölü bir hat beklerken anlaşılmaz. Ölü hattı yenilemedeki DONE + NOOP yakalar:
  yanıt 60 saniyede gelmezse hat kapatılır ve hemen yeniden kurulur. Uzun zaman aşımı bu soruyu 26 dakika
  bekletirdi.
- Haber gelince (ileti geldi, gitti, işaretlendi) hesabın eşitlemesi kendi bağlantılarında çalışır; hat
  yalnızca bekler. Art arda gelen haberler tek eşitlemede toplanır. Hat her kurulduğunda bir kez eşitlenir
  (kimse beklemezken gelmiş olabilir).
- Uyandırma kilidi yalnızca olay işlenirken tutulur. Ağ değişince hemen yeniden bağlanır.
- Hata: 30 saniyeden 15 dakikaya üstel bekleme; kimlik hatasında durur, hesap "Yeniden bağlan"
  durumuna geçer.
- Yedek yol: WorkManager ile 15/30/60 dakika; "Elle"de hiçbiri. "Anlık"ta saatte bir bakılmaya devam
  edilir; o iş, sistemin kapattığı hizmeti (izin varsa) ve vazgeçilmiş hesapların beklenmesini de yeniden
  dener.
- Ayar bütün hesaplar için tektir (beta.1). Hesap başına sıklık istenirse `AccountEntity.notifyMode` hazır.
- Biçim: hesap başına kanal ve grup, ileti başına BigTextStyle, özet InboxStyle. Düğmeler Okundu,
  Arşivle, Sil (alıcı → WorkManager → `pending_op`).
- Klasör ekrandaysa bildirim çıkmaz. İleti başka cihazda okununca bildirimi kalkar.
- Hizmetin kalıcı bildirimi ayrı, sessiz, düşük önemli kanalda.

## Düzenler

- **Telefon:** alt çubukta Gelen, Klasörler, Ara ve "⋯" (`MoreStyle.Sheet`). Liste `DeskListRow`, üstte
  `FilterChips`, yüklenirken `SkeletonList`. Çoklu seçimde `SelectionBar` kabuğun `bottomBar`
  yuvasında. Okuma `DetailTransition` ve `CompactTopBar` ile; uzak görseller için `StatusBanner`.
  Yeni ileti üst çubukta vurgu renkli simge. Yazma tam ekran.
- **Tablet (ray):** rayın yanındaki alan 880 dp'den genişse `HorizontalSplitPane` ile liste ve okuma yan
  yana, darsa `DetailTransition` (Tab S11 Ultra dik tutulunca 924 dp: liste → ileti).
- **DeX ve büyük pencere:** menü çubuğu kısayol tablosundan; kenar çubuğunda akıllı klasörler ve hesap
  başına bir bölüm; `badges` ve `NavDrop`. İçerik `WorkstationLayout`: liste | okuma | `InspectorPane`.
  Yazma `DeskWindows.openNewWindow` ile ayrı pencerede. `CommandPalette` klasörleri, komutları ve ileti
  aramasını kapsar.
- **Liste ile okuma arasındaki pay** (`MailLayout`): liste, kenar çubuğunun ya da rayın yanındaki alanın
  bir payını alır (başlangıçta % 45; en az 320 dp, okuma bölmesine en az 360 dp kalır). Bölücü
  sürüklenince pay değişir ve saklanır; genişlik değil pay saklandığı için pencere boyu değişince ve
  başka boyda bir pencerede bölünme aynı kalır.
- **Tablo mu satır mı:** büyük pencerede liste, fare kullanılırken ve en az 520 dp genişken `DenseTable`
  (başlığından sıralanır); parmakla ya da dar listede iki satırlı `DeskListRow` (hep yeniden eskiye).
  Kitin yoğunluğu "Sistem"deyken fareden parmağa geçince liste de biçim değiştirir; Ayarlar › Giriş
  yoğunluğu sabitler.

## Kısayollar

| Tuş | İş | Nerede |
| --- | --- | --- |
| Ctrl+N | Yeni ileti | Her yer |
| Ctrl+Shift+N | Yeni pencere | Her yer |
| Ctrl+K | Komut paleti | Her yer (kit) |
| Ctrl+, | Ayarlar | Her yer (kit) |
| Ctrl+B | Kenar çubuğu | Her yer (kit) |
| Ctrl+/ | Kısayol listesi | Her yer (kit) |
| F5 | Eşitle | Her yer |
| Esc | Kapat, seçimi bırak | Her yer |
| Ctrl+Enter | Gönder | Yazma |
| Ctrl+Z | Son işlemi geri al | Liste, okuma |
| / | Ara | Liste, okuma |
| J / K | Sonraki / önceki | Liste, okuma |
| Enter | Aç | Liste |
| R / A / F | Yanıtla / tümünü yanıtla / ilet | Liste, okuma |
| E / V / Delete | Arşivle / taşı / sil | Liste, okuma |
| S / U | Yıldız / okundu-okunmadı | Liste, okuma |

Kit `NewWindow`'u (Ctrl+N) `CommonShortcuts.all` dışında tutar; DeskMail onu eklemez, "Yeni pencere"yi
kitin metniyle Ctrl+Shift+N'e bağlar. Tek tuşlar ve Ctrl+Z, odak bir yazı alanındayken ve palet
açıkken çalışmaz.

- **alpha.1'de olanlar:** Ctrl+N (yer tutucu), Ctrl+Shift+N, Ctrl+K, Ctrl+,, Ctrl+B, Ctrl+/, J/K, S, U,
  Esc, /. Kalanlar işleriyle birlikte geldi: F5 hesaplarla (alpha.2); E, V, Delete ve Ctrl+Z ileti
  işlemleriyle, R/A/F ve Ctrl+Enter yazmayla (alpha.3). Ctrl+Z önce az önce gönderilen iletiyi geri alır
  (bildirimi ekrandayken), sonra son ileti işlemini. Yazma ekranı öndeyken listenin tek tuşları çalışmaz;
  Esc yazma ekranını kapatır.
- **Tuşlar nereden geçer:** etkinlik her tuşu odaktaki görünümden önce `KeyRouter`'a sorar. Yazı
  yazılıp yazılmadığı iki yerden okunur: odaktaki görünüm bir metin düzenleyici mi
  (`View.onCheckIsTextEditor`), ve Compose'un kendi ekran tarifinde odakta bir yazı alanı var mı.
- **Türkçe klavye:** "/" ayrı bir tuş değil (Shift+7); tek tuşlu "/" için yazılan karaktere de bakılır.
  Kitin Ctrl+/ kısayolu Türkçe klavyede çalışmıyor (`docs/KIT-ISTEKLERI.md`, 10).

## Aşama 1: PR'lar

Her PR'ın son commit'i CHANGELOG'u ve cihaz deneme listesini günceller. Ön sürüm, birleşen bir ya da
birkaç PR'dan sonra çıkar (13 Kasım 2026'dan beri her PR'dan sonra beklenmiyor): sürüm ve `versionCode`
o ön sürümün ilk PR'ında artar, sonraki PR'lar CHANGELOG'da aynı başlığın altına yazar. Barış Releases'ta
`main` üzerinde etiketi açar (README, "Sürüm çıkarma"); iş akışı imzalı APK'yı ve sürüm notlarını ekler.

**13 Kasım 2026, Barış'ın kararı:** alpha.4 çıkmıyor. Aşağıda "kalan" diye duran her şey ve Aşama 2'nin
tamamı `0.1.0-beta.1`'de toplanır; dışarıda kalan yalnızca Outlook girişidir, o `0.1.0-beta.2`'de gelir
(Azure kaydını Barış yapıyor; adımlar aşağıda, "Azure kaydı"). beta.1'in iş sırası "beta.1'e kalanlar"
başlığında.

1. **İskelet ve erken denemeler (alpha.1):** Gradle iskeleti ve CI · imza ve etiket iş akışı · kit
   kabuğu · sahte veriyle liste ve okuma · WebView deneme ekranı · kısayollar, menü çubuğu, palet ·
   günlük · ekran görüntüsü testleri ve kenar çubuğu testi.
2. **Hesaplar ve senkron (alpha.2):** IMAP oturumu ve klasör listesi · sağlayıcı tablosu, ISPDB, MX/SRV ·
   MIME ve HTML temizleme · senkron planlayıcı · Room şeması v1, FTS · kimlik kasası ·
   hesap ekleme ve "Yeniden bağlan" · gerçek veriyle liste, klasörler, birleşik gelen, rozetler ·
   güvenli okuma ve uzak görsel şeridi. Yalnızca okur: okundu ve yıldız bekleyen işlem kuyruğuyla gelir.
   Göç testi şemanın ilk değişikliğiyle gelir; o zamana kadar CI şemanın değişmediğini denetler.
3. **Cam iletinin üstünde (alpha.3):** WebView kendi donanım katmanında · katmana sığmayan iletide camın
   kapanması · deneme anahtarları ve günlük satırı.
4. **İleti işlemleri ve ekler (alpha.3):** bekleyen işlem kuyruğu, şema 2 ve göç testi · okundu ve yıldız ·
   arşivle, sil, taşı, geri al · sağ tık ve uzun basma menüsü · ekleri açma, kaydetme, paylaşma · tek
   harfli kısayollar (E, V, Delete) ve Ctrl+Z.
   - **Sonradan (beta.1):** çoklu seçim ve toplu işlem · geniş pencerede klasöre sürükle bırak. Seçim
     pencerenindir (`MailUiState.selection`, rota başına); tek seçili satır açık iletinin satırıdır, işlemler
     birden çok satır seçiliyken seçime, değilse açık iletiye uygulanır (`MailUiState.targets`). Dokunmada
     seçim "Seç" ile başlar (`picking`); kitin tablosu dokunmada uzun basmayı menüye ayırıyor. Çerçeveyle
     seçme kitte yok (kit isteği 22).
5. **Yazma ve gönderme (alpha.3):** iletinin yazılması ve SMTP · giden kutusu (şema 3 ve göç testi),
   gönderim işi (WorkManager), gönderiyi geri alma ve süresi · yazma ekranı · yanıtla, tümünü yanıtla, ilet ·
   dosya ekleme · cihazda taslaklar · gönderileni kaydetme kuralı · yanıtlandı ve iletildi işaretleri ·
   hesabın adı ve imzası · R, A, F ve Ctrl+Enter.
   - **Sonradan (alpha.3):** `mailto:` bağlantıları ve paylaşım (`MailtoLink`, `ComposeRequest`): başka
     uygulamanın istediği ileti, öndeki ekranın üstünde yeni bir ileti olarak başlar.
   - **Sonradan (beta.1):** taslakların sunucudaki Taslaklar klasörüne kaydı (`DraftSync`, `DraftWorker`;
     şema 4). Tek kopya kuralı: yeni kopya konur, sonra eskisi silinir (UIDPLUS ile, klasörün UID
     damgası tutuyorsa); gönderilen ya da atılan iletinin kopyası `draft_removal` notuyla silinir. Taslaklar'da
     duran ileti "Düzenle" ile alınır (`DraftKind.Edit`): bu cihazınki Message-ID'sinden bulunur.
   - **Sonradan (beta.1):** rehberden alıcı (Contact Picker) · DeX'te yazmanın ayrı pencerede açılması.
     Alıcı önerilerinin arayüzü kiti bekliyor (kit isteği 4).
6. **Arama (alpha.3):** dizin sorgusu (`SearchQuery`: yazılan katlanır, dizinin böldüğü gibi sözcüklere
   bölünür, her sözcük ön ek olarak aranır; yazılanın hiçbir parçası dizine sözdizimi olarak ulaşmaz) ·
   Ara ekranı (posta listesinin kendisi, üstünde arama alanı) · "/" kısayolu.
   - **Sonradan (beta.1):** sunucu araması (cihazda olmayan eski posta; `search_hit`) · alan önekleri
     ("kimden:", "kime:", "konu:"; İngilizceleri de) · palette "İletilerde ara".
   - **Odak (çözüldü):** alan, pencerede yerini aldıktan sonra odağı ister ve odak birkaç kare kendisinde
     kalana dek (en çok bir saniye) istemeyi sürdürür. İlk karelerde bir kez istemek, ekran yerleşmeden
     karşılıksız kalıyordu; "/" isteği ikinci kez tetiklediği için çalışıyordu. Cihazda bakılacak tek şey:
     her girişte klavyenin açılması istenir mi.
7. **Bildirimler**
   - **7a (alpha.3):** periyodik yedek yol (WorkManager, 15/30/60 dk ya da elle) · yeni posta bildirimi
     (hesap başına kanal ve grup, BigTextStyle, InboxStyle özet) · Okundu, Arşivle, Sil düğmeleri ·
     bildirime dokununca iletinin açılması · liste ekrandayken bildirim çıkmaması · okunan iletinin
     bildiriminin kalkması · bildirim izni ve Ayarlar › Bildirimler.
   - **7b (beta.1):** IDLE (`ImapWatch`, `MailWatch`) · `specialUse` ön plan hizmeti ve kalıcı bildirimi
     (`WatchService`) · yenileme alarmı, uyandırma kilidi (`Awake`), ağ değişimi · açılışta ve
     güncellemeden sonra başlatma (`StartReceiver`) · "Anlık" seçeneği (varsayılan) · pil ayarına
     yönlendirme. JVM testleri gömülü sunucuyla; **cihazda bir gecelik deneme şart** (aşağıda, "Riskler").
     - Test sunucusunun (GreenMail) iki sınırı testlere yansıdı: beklerken bayrak değişimini bildirmiyor
       (silme ile denendi), ve bekleme kurulurken gelen iletiyi ancak bekleme bitince söylüyor (testler
       bildirilene dek ileti bırakır). Gerçek sunucular ikisini de anında söyler.
8. **Outlook OAuth2:** PKCE ve jeton istemcisi · XOAUTH2 ile IMAP/SMTP girişi · tarayıcı
   akışı ve jeton saklama · hesap eklemede Microsoft yolu · Outlook farkları ve hata yönlendirmeleri.
   Azure uygulama kaydını bekler (Barış'ın tarayıcısında yapılacak); o yüzden sırası sona alındı.
9. **Ayarlar, cilâ, 0.1.0:** hesap, bildirim, imza ayarları · boş ve hata durumları, erişilebilirlik ·
   lisanslar ekranı · belgeler (mimari, yıllık bakım listesi, Google OAuth yedek planı) · bağımsız
   inceleme bulguları · 0.1.0.

### beta.1'e kalanlar

Her biri ayrı PR; sıra, cihazda en çok denenmesi gerekenden başlar.

1. Paylaşılan dosyayla yazma ekranının hemen açılması; yazma ekranının kendiliğinden kapanmaması (alpha.3'ün
   cihaz denemesinden). **Yapıldı.**
2. Anlık bildirim (7b). **Yapıldı.**
3. Çoklu seçim, toplu işlem, klasöre sürükle bırak (4'ün kalanı). **Yapıldı.**
4. Taslakların sunucuya kaydı (5'in kalanı). **Yapıldı.**
5. Sunucu araması, alan süzgeçleri, palette arama (6'nın kalanı). **Yapıldı:** `SearchQuery.terms` yazılanı
   alanlarıyla parçalara ayırır; sunucuya `UID SEARCH` doğrudan gider (kitaplığın, sunucu hayır deyince
   klasörü cihazda tarayan yedek yolu kullanılmaz). Sunucunun bulduğu iletiler `search_hit` tablosunda bir
   gün tutulur; o sürede eşitleme onları silmez. **Şema 4** (taslak alanları, `draft_removal`,
   `search_hit`) yayımlanmadı: beta.1 çıkana dek beta.1'in bütün şema değişiklikleri onda toplanır.
6. DeX'te yazmanın ayrı pencerede açılması; Contact Picker (5'in kalanı). **Yapıldı:** yazma penceresi
   `WriteActivity` (kendi görevi; `<layout>` ile 720 × 760 dp, işi bitince son uygulamalardan kalkar);
   ekranı `WriteWindow`, kabuksuz `DesktopKitRoot` içinde. Masaüstü penceresi mi: Samsung'un DeX bayrağı ya
   da çoklu pencere kipinde başlık çubuğu (`DesktopWindow`). Ne olduğu açan pencereye söylenir
   (`WriteWindows`); "Geri al" orada. Paylaşım ve `mailto:` posta penceresinde kalır (dosyaların iletiye
   girişi o pencerenin işi). Rehber: Android 17'de `ACTION_PICK_CONTACTS`, öncesinde `ACTION_PICK`.
7. İletinin içindeki (`cid:`) görseller; listede yanıtlandı ve iletildi işaretleri. **Yapıldı:** WebView'ın
   `shouldInterceptRequest`'i `cid:` isteğini cihazdaki (gerekirse sunucudan alınan) parçayla yanıtlar;
   başka hiçbir isteğe karışmaz. Tablonun sütun sırası `MessageColumns`'ta.
8. Aşama 2, brifteki sırayla: konuşma dizileri · erteleme · şablonlar · kayıtlı aramalar · yanıt
   bekleyenler · VIP ve gönderen başına uzak görsel izni · ayrıntı paneli sekmeleri (Ayrıntılar, Ekler,
   Güvenlik, Ham başlıklar) · biçimli yazma · ayarları taşıma · bildirimden hızlı yanıt.
9. Cilâ (9): boş ve hata durumları, erişilebilirlik, lisanslar ekranı, belgeler, bağımsız inceleme.

**Kiti bekleyenler** (uygulamada yamanmaz; `docs/KIT-ISTEKLERI.md`): alıcı önerilerinin arayüzü (4),
telefonda satırı kaydırarak arşiv ve erteleme (7), fareyle çerçeve çizerek seçme (22). Kit bunları
yayımlayınca sürümü yükseltilir ve uygulamaya bağlanır; beta.1 bunları beklemez.

"Sonra, her biri ayrı karar" başlığındakiler (OpenPGP/S/MIME, çeviri, Handoff, Thunderbird QR, okundu
bilgisi) beta'ya girmez; her biri ayrıca sorulur.

## Riskler ve erken denenecekler

- **WebView ile cam (alpha.1'de görüldü):** cam açıkken kaydırılan iletinin içeriği saniyede onlarca kez
  parça parça kaybolup geri geliyordu (S24, ekran kaydı, kare kare); cam kapalıyken titreme olmadığı
  cihazda görüldü, alpha.2 ileti açıkken camı kapattı. alpha.3 camı geri getirir: WebView kendi donanım
  katmanına çizilir (yukarıdaki karar). **Cihazda doğrulanacak:** camla kaydırırken titreme var mı; uzun
  bir iletinin bir bölümü boş kalıyor mu (katmandaki WebView, ekranın dışındaki bölümünü de çizmiş
  olmalı); bellek. Olmazsa sıradaki yol kaydırmayı WebView'ın kendisine bırakmak (bugün WebView iletinin
  boyundadır, sayfayı Compose kaydırır); o zamana kadar Denemeler'de "İletinin üstünde cam" kapatılır.
- **Geniş iletiler:** sabit genişlikli bültenler (600 piksellik tablolar) telefonda ekrana sığdırılmıyor,
  sağı kesik kalıyor. Sığdırma (küçültme ya da tabloyu daraltma) okuma bölmesinin sonraki işi.
- **IDLE'ın uykuda kalması (S24):** günlük her yenilemeyi, kopmayı ve yeniden bağlanmayı zaman
  damgasıyla yazar; bir gecelik deneme (şarjsız, ekran kapalı) yapılır. Bakılacaklar: "the wait was begun
  anew" satırları 24 dakikada bir geliyor mu (Android 17'nin kesin alarmı); sabaha kadar kaç kez "the
  wait … ended" ve "next try" var; gece gelen iletinin bildirimi geldiği dakikada mı çıkmış. Pil
  kısıtlaması kaldırılmadan ve "Hiç uyumayan uygulamalar"a eklenmeden deneme anlamlı değil.
- **Microsoft:** yeni kişisel hesaplarda SMTP kapalı başlayabiliyor ve kullanıcı ayarı yok; IMAP
  varsayılan olarak kapalı (Settings › Mail › Forwarding and IMAP). Azure dizini etkinlik yokluğunda
  engellenebiliyor: istemci kimliği gelişmiş ayardan değiştirilebilir olacak.
- **Kenar çubuğunun sıfırlanması:** klasör listesi değişince gezinme grafiği yeniden kurulur ve kabuk
  başlangıç ekranına döner (testle doğrulandı; `docs/KIT-ISTEKLERI.md`, madde 3). Pencerenin gösterdiği
  her şey bu yüzden ekranların dışında tutulur (`MailUiState`, `SettingsUiState`); alpha.2'den beri
  klasör listesi değişince pencere kaldığı ekrana kendisi dönüyor (arada başlangıç ekranı bir an görünür).
- **Liste → ileti geçişinde listenin durumu:** telefonda ayrıntı açıkken liste bileşimden çıkar;
  kendi hatırladıkları kaybolur (`docs/KIT-ISTEKLERI.md`, madde 9). Aynı çözüm: durum dışarıda.
- **Room 3, KSP ve AGP 9.4.1 uyumu:** alpha.2'de doğrulandı (Room 3.0.3, KSP 2.3.13; FTS4 ve
  işlemler dâhil). Yedek plana gerek kalmadı.
- **Jakarta Mail Android'de:** kitaplık ilk kez cihazda alpha.2 ile çalışıyor; testler JVM'de koşuyor.
  Bilinen iki tuzak (kaynak dosyaları, sunucu kimliği denetimi) kodda kapatıldı; ilk bağlantının
  günlüğü (Ayarlar › Günlük: "signed in as …" ve sunucunun yetenekleri) bakılacak ilk yer. Bilinmeyenler
  için: her hata günlüğe neden zinciriyle yazılır, kitaplığın cihazda çalışmayan bir parçası
  (`LinkageError`) uygulamayı kapatmaz. Cihazda ilk kez çalışan öteki parça anahtar deposudur
  (`DeviceSecretBox`); şifreyi saklayamazsa hesap eklenmez ve ekran nedenini söyler.
- **Android 17:** uygulama bellek sınırı (aşan süreç öldürülür); ACTION_SEND'de otomatik URI izni
  Android 18'de kalkıyor, izinler baştan açıkça verilir.

## Bakım notları

Yıllık güncellemede bakılacaklar; 0.1.0'la birlikte ayrı bir belgeye taşınacak.

- **`DnsResolver.getInstance()`** Android 17'de (API 37) eskidi; yerine gelen `DnsResolver(Context, Looper)`
  yalnızca API 37'den beri var. `minSdk` 37 olunca değiştirilir (`net/DnsLookup.kt`). MX ve SRV sorguları
  `rawQuery`'ye tür numarasıyla (15, 33) verilir; platform yalnızca A, AAAA ve HTTPS için sabit tanımlar.
- **Angus Mail yükseltilirken** `SocketFetcher.configureSSLSocket` yeniden okunmalı: sunucu kimliği
  denetimini uygulama `mail.imap.ssl.hostnameverifier` ile kendisi veriyor ve kitaplığınkini kapatıyor
  (`ImapConnection.properties`). SMTP eklenince aynı iki ayar `mail.smtp.` önekiyle verilecek.
- **Compose test kuralı:** `createAndroidComposeRule` (v1) eskidi; v2'ye geçiş testlerde açık eşzamanlama
  isteyebilir. Kit yükseltilirken birlikte yapılır.
- **GreenMail** bir parçanın boyutuna parçanın kendi başlık satırlarını da katıyor (gerçek sunucular
  katmaz); ek boyutu kestirimi bu yüzden ayrı bir testte gerçek sayılarla sınanıyor.
- **Veritabanı şeması** (`app/schemas`): CI, derlemenin yazdığı şema depodakinden farklıysa durur. Şema
  değişikliği yeni sürüm numarası, göç ve göç testi demektir.
- **Microsoft 365 (iş/okul) hesapları:** posta sunucusu Microsoft olan alan adları Outlook satırına
  eşleniyor; kişisel hesaplardan farkları (SMTP sunucusu, kiracı) denenmedi.

## Azure kaydı (Outlook için)

Barış kendi tarayıcısında yapar (13 Kasım 2026'da adımlar sohbette verildi; Microsoft'un belgelerine göre,
portal burada denenmedi). "DeskMail" adıyla, "Personal accounts only" türünde, istemci sırrı olmayan bir
genel istemci. İstemci kimliği gizli değildir, depoda durur.

1. **Dizin.** Kişisel Microsoft hesapları Haziran 2024'ten beri yeni kayıt için bir dizin (tenant) ister.
   `entra.microsoft.com` › Entra ID › App registrations › New registration açılmıyorsa ücretsiz Azure
   hesabı açılır (`azure.microsoft.com/free`); kimlik doğrulaması için kart ister, ücret almaz. Kartsız
   resmi bir yol bulunamadı.
2. **Kayıt.** Name `DeskMail` · Supported account types: Personal accounts only · Redirect URI: platform
   "Public client/native (mobile & desktop)", değer `com.deskmail://oauth2redirect`.
3. **Yönlendirme adresi.** Authentication'da "Mobile and desktop applications" altında durmalı ("Web" ya
   da "Single-page application" altında değil). Portal adresi kabul etmezse "Android" platformu: paket
   `com.deskmail`, imza özeti `6S4i+iP1XZO3GIRA49nml7NnavU=` (imza sertifikasının SHA-1'i, Base64;
   yayımlanan APK'dan hesaplandı, gizli değil). Adres o zaman
   `msauth://com.deskmail/6S4i%2BiP1XZO3GIRA49nml7NnavU%3D` olur. "Allow public client flows" kapalı kalır.
4. **İzinler.** API permissions › Microsoft Graph › Delegated: `IMAP.AccessAsUser.All`, `SMTP.Send`,
   `offline_access`. Yönetici onayı gerekmez. İstemci sırrı oluşturulmaz.
5. **Outlook.com.** Settings › Mail › Forwarding and IMAP › "Let devices and apps use IMAP" açılır
   (varsayılan kapalı).

Uygulamanın kullanacakları: yetkilendirme `https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize`,
jeton `…/consumers/oauth2/v2.0/token` ("Personal accounts only" kaydı `common` ile çalışmaz) · kapsamlar
`https://outlook.office.com/IMAP.AccessAsUser.All`, `https://outlook.office.com/SMTP.Send`,
`offline_access` · IMAP `outlook.office365.com:993` (TLS), SMTP `smtp-mail.outlook.com:587` (STARTTLS) ·
giriş SASL XOAUTH2.
