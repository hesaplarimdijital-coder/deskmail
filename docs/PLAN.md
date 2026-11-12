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
- `pending_op`: yerelde yapılan işlemlerin kuyruğu; çevrimdışı çalışma ve geri al buradan.
- `outbox`: taslak ve giden; `send_after` ile gönderiyi geri alma.
- `address`: geçmiş iletilerdeki adresler (alıcı önerisi).

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

- **Nasıl yürür (alpha.2):** üç iş, her biri kendi bağlantısında, biri ötekini bekletmez (`MailSync`):
  - listeler: klasör listesi ve sayıları, gelen kutusu, istenen klasör; her klasörün en yeni 20
    iletisinin metni de bu sırada iner. Hesap başına aynı anda bir tane.
  - kalan metinler: liste işi bitince arka planda, klasör klasör, 50'şer; liste yeniden sorulmaz.
  - açılan iletinin metni: cihazda yoksa sırası beklenmeden.
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
  dinleyicili `setExactAndAllowWhileIdle`, 14–16'da `setAndAllowWhileIdle`. Okuma zaman aşımı yenileme
  süresi + 2 dakika.
- Uyandırma kilidi yalnızca olay işlenirken tutulur. Ağ değişince hemen yeniden bağlanır.
- Hata: 30 saniyeden 15 dakikaya üstel bekleme; kimlik hatasında durur, hesap "Yeniden bağlan"
  durumuna geçer.
- Yedek yol: WorkManager ile 15/30/60 dakika; "Elle"de hiçbiri.
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
| E / Delete | Arşivle / sil | Liste, okuma |
| S / U | Yıldız / okundu-okunmadı | Liste, okuma |

Kit `NewWindow`'u (Ctrl+N) `CommonShortcuts.all` dışında tutar; DeskMail onu eklemez, "Yeni pencere"yi
kitin metniyle Ctrl+Shift+N'e bağlar. Tek tuşlar ve Ctrl+Z, odak bir yazı alanındayken ve palet
açıkken çalışmaz.

- **alpha.1'de olanlar:** Ctrl+N (yer tutucu), Ctrl+Shift+N, Ctrl+K, Ctrl+,, Ctrl+B, Ctrl+/, J/K, S, U,
  Esc, /. Kalanlar işleriyle birlikte gelir: F5 hesaplarla geldi (alpha.2); E/Delete ve Ctrl+Z ileti
  işlemleriyle, R/A/F ve Ctrl+Enter yazmayla.
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
4. **İleti işlemleri ve ekler:** bekleyen işlem kuyruğu · okundu ve yıldız · arşivle, sil,
   taşı, geri al · çoklu seçim, sağ tık, "⋯" · sürükle bırak · filtre çipleri ve sıralama · ekleri
   açma ve kaydetme · tek harfli kısayollar.
5. **Yazma ve gönderme:** ileti oluşturma ve SMTP · giden kutusu, gönderim işi, gönderiyi
   geri alma · yazma ekranı · yanıtla, tümünü yanıtla, ilet · taslaklar · gönderileni kaydetme kuralı ·
   alıcı önerileri ve Contact Picker · `mailto:`, paylaşım, DeX'te ayrı pencere.
6. **Arama:** FTS dizini ve Türkçe katlama · Ara ekranı · sunucu araması · palet ve "/".
7. **Bildirimler:** IDLE döngüsü · ön plan hizmeti · alarm, uyandırma kilidi, ağ değişimi ·
   açılışta ve güncellemeden sonra başlatma · yeni posta bildirimi ve düğmeleri · periyodik yedek yol ·
   pil yönlendirmesi.
8. **Outlook OAuth2:** PKCE ve jeton istemcisi · XOAUTH2 ile IMAP/SMTP girişi · tarayıcı
   akışı ve jeton saklama · hesap eklemede Microsoft yolu · Outlook farkları ve hata yönlendirmeleri.
   Azure uygulama kaydını bekler (Barış'ın tarayıcısında yapılacak); o yüzden sırası sona alındı.
9. **Ayarlar, cilâ, 0.1.0:** hesap, bildirim, imza ayarları · boş ve hata durumları, erişilebilirlik ·
   lisanslar ekranı · belgeler (mimari, yıllık bakım listesi, Google OAuth yedek planı) · bağımsız
   inceleme bulguları · 0.1.0.

Aşama 2 ve sonrası brifteki sırayla: konuşma dizileri, erteleme, şablonlar, kayıtlı aramalar, yanıt
bekleyenler, VIP ve gönderen başına uzak görsel izni, ayrıntı paneli sekmeleri, biçimli yazma, ayarları
taşıma, bildirimden hızlı yanıt, satırı kaydırma.

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
  damgasıyla yazar; bir gecelik deneme (şarjsız, ekran kapalı) yapılır.
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

İmza anahtarından sonra, PR 3'ten önce; sahibinin tarayıcısında yapılır. "DeskMail" adıyla, "Personal
accounts only" türünde, istemci sırrı olmayan bir genel istemci. Yönlendirme adresi önce "Mobile and
desktop applications" altında `com.deskmail://oauth2redirect`; portal kabul etmezse "Android" platformu
ve imza özetiyle `msauth://com.deskmail/…`. İstemci kimliği gizli değildir, depoda durur.
