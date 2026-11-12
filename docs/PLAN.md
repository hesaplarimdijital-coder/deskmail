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

- Tetikleyiciler: IDLE olayı, klasörü açma, elle yenileme, bekleyen işlem sonrası, periyodik iş.
- İki cihaz: yerel işlem hemen görünür, `pending_op` sırasıyla sunucuya gider; doğrunun kaynağı sunucudur.
- Gmail: `\All` birleşik görünüme girmez; arşiv Gelen'den çıkarmadır; gönderilen eklenmez.
- Outlook: gönderilen ve arşiv davranışı ilk gerçek bağlantıda günlükten doğrulanıp tabloya yazılır.
- Ayar bulma sırası: uygulamadaki sağlayıcı tablosu → ISPDB → MX kaydıyla ISPDB → SRV (RFC 6186) → elle.

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
  Esc, /. Kalanlar işleriyle birlikte gelir: F5 alpha.2, R/A/F ve Ctrl+Enter alpha.5, E/Delete ve
  Ctrl+Z alpha.4.
- **Tuşlar nereden geçer:** etkinlik her tuşu odaktaki görünümden önce `KeyRouter`'a sorar. Yazı
  yazılıp yazılmadığı iki yerden okunur: odaktaki görünüm bir metin düzenleyici mi
  (`View.onCheckIsTextEditor`), ve Compose'un kendi ekran tarifinde odakta bir yazı alanı var mı.
- **Türkçe klavye:** "/" ayrı bir tuş değil (Shift+7); tek tuşlu "/" için yazılan karaktere de bakılır.
  Kitin Ctrl+/ kısayolu Türkçe klavyede çalışmıyor (`docs/KIT-ISTEKLERI.md`, 10).

## Aşama 1: PR'lar

Her PR'ın son commit'i sürüm, `versionCode`, CHANGELOG ve cihaz deneme listesidir. Birleşince Barış
Releases'ta `main` üzerinde etiketi açar (README, "Sürüm çıkarma"); iş akışı imzalı APK'yı ve sürüm
notlarını ekler.

1. **İskelet ve erken denemeler (alpha.1):** Gradle iskeleti ve CI · imza ve etiket iş akışı · kit
   kabuğu · sahte veriyle liste ve okuma · WebView deneme ekranı · kısayollar, menü çubuğu, palet ·
   günlük · ekran görüntüsü testleri ve kenar çubuğu testi.
2. **Hesaplar ve senkron (alpha.2):** IMAP oturumu ve klasör listesi · sağlayıcı tablosu, ISPDB, MX/SRV ·
   MIME ve HTML temizleme · senkron planlayıcı · Room şeması v1, FTS, göç testi · kimlik kasası ·
   hesap ekleme ve "Yeniden bağlan" · gerçek veriyle liste, klasörler, birleşik gelen, rozetler ·
   güvenli okuma ve uzak görsel şeridi.
3. **Outlook OAuth2 (alpha.3):** PKCE ve jeton istemcisi · XOAUTH2 ile IMAP/SMTP girişi · tarayıcı
   akışı ve jeton saklama · hesap eklemede Microsoft yolu · Outlook farkları ve hata yönlendirmeleri.
4. **İleti işlemleri ve ekler (alpha.4):** bekleyen işlem kuyruğu · okundu ve yıldız · arşivle, sil,
   taşı, geri al · çoklu seçim, sağ tık, "⋯" · sürükle bırak · filtre çipleri ve sıralama · ekleri
   açma ve kaydetme · tek harfli kısayollar.
5. **Yazma ve gönderme (alpha.5):** ileti oluşturma ve SMTP · giden kutusu, gönderim işi, gönderiyi
   geri alma · yazma ekranı · yanıtla, tümünü yanıtla, ilet · taslaklar · gönderileni kaydetme kuralı ·
   alıcı önerileri ve Contact Picker · `mailto:`, paylaşım, DeX'te ayrı pencere.
6. **Arama (alpha.6):** FTS dizini ve Türkçe katlama · Ara ekranı · sunucu araması · palet ve "/".
7. **Bildirimler (alpha.7):** IDLE döngüsü · ön plan hizmeti · alarm, uyandırma kilidi, ağ değişimi ·
   açılışta ve güncellemeden sonra başlatma · yeni posta bildirimi ve düğmeleri · periyodik yedek yol ·
   pil yönlendirmesi.
8. **Ayarlar, cilâ, 0.1.0:** hesap, bildirim, imza ayarları · boş ve hata durumları, erişilebilirlik ·
   lisanslar ekranı · belgeler (mimari, yıllık bakım listesi, Google OAuth yedek planı) · bağımsız
   inceleme bulguları · 0.1.0.

Aşama 2 ve sonrası brifteki sırayla: konuşma dizileri, erteleme, şablonlar, kayıtlı aramalar, yanıt
bekleyenler, VIP ve gönderen başına uzak görsel izni, ayrıntı paneli sekmeleri, biçimli yazma, ayarları
taşıma, bildirimden hızlı yanıt, satırı kaydırma.

## Riskler ve erken denenecekler

- **WebView ile cam:** kitin bulanıklığı WebView içeriğini kaynak olarak alamayabilir. alpha.1'deki
  deneme ekranında bakılır; olmazsa okuma ekranında içerik çubukların altına girmez.
- **IDLE'ın uykuda kalması (S24):** günlük her yenilemeyi, kopmayı ve yeniden bağlanmayı zaman
  damgasıyla yazar; bir gecelik deneme (şarjsız, ekran kapalı) yapılır.
- **Microsoft:** yeni kişisel hesaplarda SMTP kapalı başlayabiliyor ve kullanıcı ayarı yok; IMAP
  varsayılan olarak kapalı (Settings › Mail › Forwarding and IMAP). Azure dizini etkinlik yokluğunda
  engellenebiliyor: istemci kimliği gelişmiş ayardan değiştirilebilir olacak.
- **Kenar çubuğunun sıfırlanması:** klasör listesi değişince gezinme grafiği yeniden kurulur ve kabuk
  başlangıç ekranına döner (testle doğrulandı; `docs/KIT-ISTEKLERI.md`, madde 3). Pencerenin gösterdiği
  her şey bu yüzden ekranların dışında tutulur (`MailUiState`); alpha.2'de klasör listesi değişince
  açık klasöre uygulama kendisi dönecek.
- **Liste → ileti geçişinde listenin durumu:** telefonda ayrıntı açıkken liste bileşimden çıkar;
  kendi hatırladıkları kaybolur (`docs/KIT-ISTEKLERI.md`, madde 9). Aynı çözüm: durum dışarıda.
- **Room 3, KSP ve AGP 9.4.1 uyumu:** alpha.2'de görülür; yedek Room 2.8.5.
- **Android 17:** uygulama bellek sınırı (aşan süreç öldürülür); ACTION_SEND'de otomatik URI izni
  Android 18'de kalkıyor, izinler baştan açıkça verilir.

## Azure kaydı (Outlook için)

İmza anahtarından sonra, PR 3'ten önce; sahibinin tarayıcısında yapılır. "DeskMail" adıyla, "Personal
accounts only" türünde, istemci sırrı olmayan bir genel istemci. Yönlendirme adresi önce "Mobile and
desktop applications" altında `com.deskmail://oauth2redirect`; portal kabul etmezse "Android" platformu
ve imza özetiyle `msauth://com.deskmail/…`. İstemci kimliği gizli değildir, depoda durur.
