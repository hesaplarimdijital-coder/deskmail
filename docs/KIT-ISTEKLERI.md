# desktop-kit'ten istenenler

DeskMail'i kurarken desktop-kit 2.4.0'da eksik kalan genel parçalar. Karar (12 Kasım 2026): bunlar
uygulamada yamanmaz, **kite yazılır** (kitin kendi sohbetinde). Kit yeni bir etiket yayımlayana kadar
DeskMail her maddede "Şimdilik" satırındaki yolla ilerler; etiket gelince sürüm yükseltilir ve geçici yol
kaldırılır.

Önerilen sıra: 20 (cam ve WebView), 12 (dolgu hatası), 18 (üst çubuğun yanıp sönmesi), 3 (kenar çubuğu),
2 (tablo), 9 (liste durumu), 1 (rozetler), 10 (klavye düzeni), 13 (bölücü), 14 (çekerek yenileme);
kalanlar ilgili PR'a kadar bekleyebilir. 12, 13, 18 ve 20 istek değil, hata. 14–17 alpha.2'de (gerçek
posta ve hesap ekranları) eklendi; 18–20 alpha.1'in Galaxy S24'teki iki ekran kaydından (12 Kasım 2026),
kare kare bakılarak yazıldı; 21 ve 4. maddenin eki alpha.3'te, yazma ekranıyla.

Satır ve dosya adları kitin 2.4.0 etiketine göredir.

## 1. Rozetler alt çubukta ve rayda da görünsün

- **Bugün:** `DesktopShell(badges = …)` rozetleri yalnızca kenar çubuğuna iletir
  (`shell/NavigationChrome.kt`: `Sidebar` alır; `BottomNavBar` ve `NavRail`'in `badges` parametresi yok).
- **İstek:** `BottomNavBar` ve `NavRail` öğelerinde de rozet: simgenin köşesinde küçük sayı (`CountBadge`
  boyunda) ya da nokta; TalkBack öğenin adıyla birlikte okur ("Gelen, 4 okunmamış").
- **Neden:** telefonda "Gelen" sekmesinde okunmamış sayısı hiç görünmüyor.
- **Şimdilik:** sayı telefonda yalnızca Klasörler ekranındaki satırlarda gösterilir.

## 2. `DenseTable`: satır vurgusu ve simge hücresi

- **Bugün:** `TableColumn.value: (T) -> String`; `TableRow` stili sütun sırasına göre verir (ilk sütun
  kalın, ötekiler `textSecondary`). Satıra göre değişen stil ya da metin dışı hücre yok
  (`components/DenseTable.kt`).
- **İstek:**
  - satır vurgusu: `DenseTable(rowEmphasis: (T) -> Boolean)`; vurgulu satırın metni kalın ve
    `colors.text` (okunmamış ileti);
  - simge hücresi: `TableColumn(icon: (T) -> ImageVector?, iconTint, iconLabel)` ya da genel bir
    `cell: @Composable (T) -> Unit` yuvası (yıldız, ek);
  - isteğe bağlı: hücreye tıklama (`onCellClick`), yıldızı satırı açmadan değiştirmek için.
- **Neden:** e-posta listesinde okunmamış iletinin kalın görünmesi ve yıldız/ek simgeleri temel beklenti.
- **Şimdilik:** okunmamış için "●" yazan dar bir sütun, yıldız için "★", ek için ek sayısı.

## 3. Kenar çubuğu: değişen öğeler, girinti, sağ tık

- **Bugün:** kabuk gezinme grafiğini `ShellSpec` öğelerinden kurar (`shell/DesktopShell.kt`:
  `spec.items.forEach { composable(item.route) { … } }`). `ShellSpec` değişince `NavHost`'un kurucu
  lambda'sı da değişir ve yeni bir grafik kurulur. `NavItem`'da girinti ve menü yok; bir öğe her
  gezinme türünde görünür.
- **Testle doğrulandı** (`ShellNavigationTest.aNewFolderSendsTheShellBackToItsStartScreen`):
  - aynı rotalarla yeni bir `ShellSpec` (etiket ya da dil değişimi): açık ekran ve durumu **korunur**;
  - bir rota fazlası (sunucuda yeni klasör, yeni hesap): kabuk **başlangıç ekranına döner**, ekranların
    `rememberSaveable` durumu **silinir**.
- **İstek:**
  - öğe kümesi değişince açık ekranı, geri yığınını ve kaydırma konumlarını koruyan bir yol (ör. rota
    kümesi değişse de aynı kalan ekranların durumunu taşımak ya da argümanlı tek bir hedef);
  - `NavItem(indent = …)` ya da alt öğeler (iç içe klasörler), açılıp kapanan bölüm (hesap);
  - kenar çubuğu ve ray öğesinde sağ tık / uzun basma menüsü (`menu: () -> List<MenuEntry>`):
    "Tümünü okundu say", "Çöpü boşalt";
  - öğeyi yalnızca bazı gezinme türlerinde gösterme (ör. `NavItem(showIn = setOf(NavKind.Sidebar))`):
    klasörler yalnız kenar çubuğunda, "Klasörler" ve "Ara" yalnız alt çubukta ve rayda dursun. Bugün
    telefonda "⋯" sayfası ve rayda "Daha fazla" ekranı bütün klasörleri (rayda bölüm başlıksız, iki
    hesabın "Gelen Kutusu"su alt alta) listeliyor; kenar çubuğunda da "Klasörler" ve "Ara" fazladan
    duruyor;
  - alt rota açıkken üst sekmenin seçili kalması: telefonda Klasörler › bir klasör açılınca alt çubukta
    "Klasörler" yerine "⋯" seçili görünüyor (S24'te ekran kaydında da böyle: Klasörler'den açılan her
    klasörde "Daha fazla" işaretli).
- **Neden:** e-postada kenar çubuğu hesaplara ve klasörlere göre değişir: hesap eklenir, sunucuda
  klasör açılır.
- **alpha.2'de görülen:** gerçek hesapla bu her hesap eklemede, kaldırmada ve sunucuda yeni klasör
  görülünce oluyor. Uygulama pencereyi kaldığı ekrana geri götürüyor (`DeskMailApp`: rota kümesi
  değişince `ShellNavigator.navigate`), ama arada başlangıç ekranı bir an görünüyor (iki sekme geçişi) ve
  o ekranın geri yığını (telefonda Klasörler › klasör) gidiyor. Hesap eklenirken başlangıç ekranına
  dönüş bilerek bırakıldı: posta oraya gelir.
- **Şimdilik:** klasörler `NavItem` olarak verilir, her pencere boyunda aynı `ShellSpec` ile; pencerenin
  gösterdiği (açık ileti, süzgeçler, kaydırma) ekranların dışında tutulur (`MailUiState`), klasör listesi
  değişince açık klasöre uygulama kendisi döner (alpha.2'de, gerçek klasörlerle); alt klasörler yolu
  yazılarak ("Projeler / 2026") gösterilir; telefonda ve rayda klasöre Klasörler ekranından gidilir
  (klasör listenin üstüne açılır, Geri listeye döner); klasör menüsü Klasörler ekranındaki satırlarda
  olacak.

## 4. Alıcı alanı: çipli adres girişi ve öneri listesi

- **Bugün:** `DeskTextField`, `DeskSearchField`, `NumberField` var; adresleri çip olarak tutan bir alan
  ve alanın altında açılan öneri listesi yok (`components/Forms.kt`).
- **İstek:** adresleri çip olarak gösteren bir alan: virgül, noktalı virgül ya da Enter çipi tamamlar,
  Backspace son çipi siler, yapıştırılan liste bölünür, geçersiz adres işaretlenir; yazarken alanın
  altında klavyeyle gezilen öneri listesi (↑/↓, Enter, Esc); TalkBack her çipi adıyla okur.
- **Ayrıca gereken (alpha.3'te görüldü):** `DeskTextField` yalnızca `String` alır; imlecin yerini ne
  bildirir ne ayarlatır (`TextFieldValue` alan bir biçimi yok). Bu yüzden düz alanın altına öneri listesi
  de eklenemiyor: öneri seçilince son parça değiştirilir, ama imleç eski yerinde, metnin ortasında kalır.
  Aynı eksik, yanıtta imleci alıntının üstüne koymayı ve ileride biçimli yazmayı da engeller.
- **Şimdilik:** virgülle ayrılmış düz `DeskTextField`; yazarken öneri yok. Öneri verisi hazır
  (`MailComposer.suggest`: geçmiş iletilerin adresleri), arayüzü kitin alanını bekliyor.

## 5. Telefonda yüzen birincil eylem düğmesi

- **Bugün:** kabukta alt çubuğun üstünde duran, çubukla birlikte çekilen bir düğme yuvası yok.
- **İstek:** kitin çizgisinde (Material FAB değil) yüzen birincil düğme: alt çubuğun üstünde, çubuk
  kaydırmayla çekilirken onunla birlikte hareket eder, ekran klavyesi açıkken gizlenir.
- **Şimdilik:** "Yeni ileti" telefonda üst çubukta vurgu renkli simge (`TopBarAction(primary = true)`).

## 6. `StatusBanner`'da ikinci eylem

- **Bugün:** tek `actionLabel`/`onAction` ve kapatma düğmesi (`components/Feedback.kt`).
- **İstek:** ikinci eylem (`secondaryActionLabel`/`onSecondaryAction`); telefonda ikisi metnin altında.
- **Neden:** "Uzak görseller engellendi · Göster · Bu gönderenden hep göster".
- **Şimdilik:** şeritte yalnızca "Göster"; gönderen izni Aşama 2'de "⋯" menüsünden.

## 7. Liste satırında kaydırma eylemleri (Aşama 2)

- **İstek:** `DeskListRow`'da yana kaydırma eylemleri (arşivle, ertele): parmağı izler, eşikte titreşir,
  geri alınabilir; fare ve klavyede etkisiz.

## 8. `DeskListRow`'da önizleme satırı (küçük)

- **Bugün:** tek satır başlık ve tek satır alt başlık.
- **İstek:** `subtitleMaxLines` ya da ikinci alt satır (gönderen / konu / önizleme).
- **Şimdilik:** alt satır "Konu — önizleme" olarak tek satırda.

## 9. `DetailTransition`: ayrıntı açıkken listenin durumu kayboluyor

- **Bugün:** telefonda ayrıntı tamamen açılınca `list` içeriği bileşimden çıkıyor
  (`shell/DetailTransition.kt`: `transition.AnimatedContent { target -> if (target == null) list() … }`);
  `AnimatedContent` çıkan içeriğin `rememberSaveable` durumunu saklamıyor. Geri dönünce liste yeniden
  kuruluyor: içinde hatırlanan kaydırma konumu, açık bölümler, yazılmış süzgeç sıfırlanıyor.
  DeskMail'de görüldü: Ayarlar'da aşağı kaydırıp günlüğü aç, geri dön → Ayarlar en başta
  (`LogScreenTest`, 12 Kasım 2026).
- **İstek:** `list` ve `detailContent` bir `SaveableStateHolder` içinde dursun (Navigation'ın ekranlar
  için yaptığı gibi); liste geri gelince kendi durumunu bulsun.
- **Şimdilik:** korunması gereken durum (`LazyListState`, süzgeçler, açık ileti) `DetailTransition`'ın
  dışında tutuluyor.

## 10. Kısayollar tuşa göre eşleşiyor: Türkçe klavyede "/" yok

- **Bugün:** `KeyChord` tuş koduyla eşleşir (`input/Shortcuts.kt`: `event.key == key`). Türkçe Q
  klavyede "/" ayrı bir tuş değil, Shift+7 ile yazılır; `Key.Slash` hiç gelmez. Kitin kendi
  "Klavye kısayolları" kısayolu (Ctrl+/) da bu yüzden Türkçe klavyede çalışmaz.
- **İstek:** noktalama tuşları için yazılan karaktere göre eşleşme (`KeyChord(char = '/')` ya da
  `matches` içinde `unicodeChar` denemesi), ya da bir kısayola ikinci bir tuş bileşimi.
- **Şimdilik:** DeskMail tek tuşlu "/" için yazılan karaktere de bakıyor (`KeyRouter.typesSlash`);
  Ctrl+/ kite kaldı.

## 11. Ayrıntı paneli kapalı başlayabilsin (küçük)

- **Bugün:** `rememberInspectorState(prefs)` paneli ilk açılışta açık getirir
  (`DeskPreferences.inspectorCollapsed` varsayılanı `false`), ve durum ekrandan ekrana ortak değildir.
- **İstek:** `rememberInspectorState(prefs, defaultCollapsed = true)`.
- **Şimdilik:** DeskMail kendi `InspectorState`'ini kuruyor (kapalı başlar, genişliği ve durumu kendi
  ayarlarında).

## 12. `rememberShellContentPadding()` alt bileşimden çağrılınca kabuk fark etmiyor (hata)

- **Bugün:** `ShellScreenFrame`, ekranı kendisinin dolgulayıp dolgulamayacağına **ölçüm sırasında**
  bakar (`shell/ShellPadding.kt`: `layout { … if (screen.handlers > 0) … }`);
  `rememberShellContentPadding()` sayacı bir `DisposableEffect` içinde artırır.
  - Çağrı ekranın kendi bileşiminden gelirse sayaç çerçevenin ilk ölçümünden önce artar: doğru.
  - Çağrı bir alt bileşimden gelirse (`BoxWithConstraints`, `SubcomposeLayout`) içerik çerçeve
    **ölçülürken** bileşir. Sayaç o sırada artar; çerçevenin yeniden ölçüm isteği, düğüm o an ölçüldüğü
    için yutulur (`MeasureAndLayoutDelegate.requestRemeasure`: `Measuring` → `false`). Çerçeve dolguyu
    bırakmaz, ekran da kendi dolgusunu ekler: dolgu iki kat olur ve öyle kalır.
- **Görülen:** telefonda ileti listesi ve okuma bölmesi bir üst çubuk boyu aşağıda başladı, altta da
  çubuğun üstünde bitti (içerik çubukların altından geçmedi). `MailScreen` düzen seçmek için
  `BoxWithConstraints` kullanıyordu; liste ve okuma bölmesi dolguyu onun içinden istiyordu.
- **İstek:** çerçeve sayacı bileşimde okusun ya da sayaç değişince kendini yeniden ölçtürsün. Aynı tür
  hata kitte bir kez düzeltilmiş: `DesktopKitRoot`'taki "read here, not while measuring" notu
  (`RootInsets.shells`).
- **Şimdilik:** DeskMail dolguyu ekranın kendi bileşiminde bir kez ister ve aşağıya parametre olarak
  verir (`MailScreen`: `val bars = rememberShellContentPadding()`).
  `MailScreenTest.aPhonesListAndReaderLieUnderTheBars` bunu tutar.

## 13. Bölücü sürüklenirken `SplitState` sınırların dışına taşıyor (küçük hata)

Koddan okundu, cihazda denenmedi.

- **Bugün:** `HorizontalSplitPane` sürüklemede `state.startWidthDp += delta` yapar, sınırı yalnızca
  ölçümde uygular (`components/SplitPane.kt`: `wanted.coerceIn(minStartPx, maxStart)`). İşaretçi sınırı
  geçince durumdaki değer görünmeyen bir yere kaymayı sürdürür; geri sürüklerken o kadar yol boşa gider
  (bölme kıpırdamaz). Saklanan değer de sınırın dışında kalabilir.
- **İstek:** sürüklemede ve ok tuşlarında değer, son ölçümdeki sınırlara kıstırılsın.
- **Şimdilik:** DeskMail bölücüden yalnızca payı alır (genişlik / alan), payı gösterilen genişlikten
  hesaplar (`MailLayout.listShare`) ve pencere yeniden yerleşince genişliği sınırların içine geri yazar.

## 14. Çekerek yenileme

- **Bugün:** kitte liste için "aşağı çek, yenile" hareketi ve göstergesi yok.
- **İstek:** `LazyColumn`/`DenseTable` ile çalışan, kitin hareket ve dokunuş diline uyan bir çekerek
  yenileme sarmalayıcısı (`refreshing: Boolean`, `onRefresh`); kabuk çubuklarının altından geçen listede
  gösterge üst çubuğun altında dursun. Fare ve klavyede etkisiz.
- **Neden:** telefonda e-posta listesini yenilemenin alışılmış yolu.
- **Şimdilik:** telefonda üst çubukta, rayın yanında süzgeçlerin sonunda, büyük pencerede başlıkta bir
  "Eşitle" düğmesi; klavyede F5. Eşitleme sürerken listenin üstünde ince bir `DeskProgressBar`.

## 15. Alt sayfanın başlığı rayda görünmüyor (küçük)

- **Bugün:** `PageHeader` başlığı yalnızca kenar çubuğu düzeninde yazar; `CompactTopBar` yalnızca telefonda
  etkilidir. Rayda (tablet) üst çubuk rotanın etiketini gösterir: Ayarlar'ın üstüne açılan bir sayfanın
  ("Hesap ekle", "Günlük", bir hesabın sayfası) kendi başlığını yazacağı yer yok; yalnızca "Geri" düğmesi
  görünür.
- **İstek:** `CompactTopBar`'ın başlık ve geri düğmesi rayda da geçerli olsun, ya da `PageHeader`'a
  "başlığı her düzende göster" seçeneği.
- **Şimdilik:** alt sayfaların ilk bölüm başlığı sayfayı anlatıyor.

## 16. `MessageScreen`'in düğmesi vurgulu olamıyor (küçük)

- **Bugün:** `MessageScreen(actionLabel, onAction)` düz bir `DeskButton` çizer.
- **İstek:** `actionPrimary: Boolean = false`.
- **Neden:** "Henüz hesap yok · Hesap ekle" ekranında tek ve asıl eylem bu düğme.
- **Şimdilik:** düz düğme.

## 17. Ayar kartında satırın tamamına dokunma, ve sakıncalı eylem düğmesi (küçük)

- **Bugün:** `SettingRow(title, subtitle, control)` satırı tıklanamaz; `DeskListRow` kartın içinde kendi
  yatay dolgusuyla girintili durur. `DeskButton`'ın yalnızca düz ve vurgulu hâli var.
- **İstek:** `SettingRow(onClick = …)` (satırın tamamı, sonunda ok), ve `DeskButton(danger = true)`
  (ya da `tone`) geri alınamayan eylemler için.
- **Neden:** Ayarlar › Hesaplar'da hesap satırı; "Hesabı kaldır".
- **Şimdilik:** satırın sonunda "Yönet" düğmesi; "Hesabı kaldır" düz düğme, önce onay penceresi soruyor.

## 18. Telefonun üst çubuğu ekran değişirken yanıp sönüyor (hata)

- **Bugün:** `CompactTopBar` çağrıları bir yığında durur, "son bileşen kazanır", bileşimden çıkan
  geri alınır (`shell/CompactTopBar.kt`). Başlık (rotanın etiketi) rota değişir değişmez değişir; geri
  düğmesi ve eylemler ise eski ekran bileşimden çıkana, yani çıkış geçişi bitene kadar kalır. Çubuk bu
  yüzden bir geçişte iki kez değişir ve başlık her seferinde solup yeniden gelir.
- **Kayıtta** (S24, 120 Hz; süreler kaydın kendi zamanı):
  - Klasör › geri (Arşiv'den Klasörler'e): 36,09–36,30 sn arası çubukta **"← Klasörler" ve kalem**
    (yeni başlık, eski ekranın geri düğmesi ve "Yeni ileti"si); 36,36'da çubuk **boş**; 36,40–36,53'te
    "Klasörler" yeniden beliriyor. Aynısı her klasörden dönüşte (Gönderilmiş, Spam, Projeler / 2026).
  - İleti açılırken (Gelen › ileti): başlık aynı sözcük olduğu hâlde ("Gelen" › "← Gelen") 10,66–10,72'de
    soluyor, 10,74'te yok, 10,76–10,87'de geri geliyor; eylemler aynı anda değişiyor.
- **İstek:**
  - çıkmakta olan ekranın `CompactTopBar` kaydı, çıkış geçişi başlar başlamaz geçersiz sayılsın (ya da
    çubuğun içeriği rota ile birlikte tek adımda değişsin);
  - başlık metni değişmiyorsa solmasın: geri düğmesi gelince yalnızca yana kaysın.
- **Şimdilik:** uygulama bir şey yapmıyor.

## 19. `open()` yayı tam sayfa geçişinde yerine otururken taşıyor (küçük)

- **Bugün:** `DetailTransition` telefonda `DeskMotion.open()` yayını kullanır (0,9 / 700); belgesi
  "taşması görülmeyecek kadar küçük" der.
- **Kayıtta:** ileti sayfası sağdan gelip yerine oturduktan sonra **2 piksel sola taşıyor ve geri
  geliyor** (38,902–38,987 sn: −1, −2, −1, 0 piksel; 85 ms). Sayfanın tamamı (başlık ve gövde birlikte)
  kayıyor. Metinle dolu bir sayfada bu, yerine otururken kısa bir titreme gibi okunuyor.
- **İstek:** tam ekran sayfa geçişlerinde taşmayan bir yay (sönüm 1,0) ya da son pikselde kırpma;
  küçük öğelerdeki hafif sekme kalabilir.
- **Şimdilik:** uygulama bir şey yapmıyor.

## 20. Cam kaynağının içinde WebView varken kaydırınca içerik yanıp sönüyor (hata)

- **Bugün:** kabuk, içeriğinin tamamını cam kaynağı yapar (`shell/DesktopShell.kt`:
  `Modifier.glassSource(glassSource, glassMode)`, yani Haze'in `hazeSource`'u); her cam yüzey
  (`Modifier.glass` › `hazeBlur`) o katmanı bir kez daha, küçültüp bulanıklaştırarak çizer. Telefonda
  alt çubuk hep camdır, üst çubuk içerik altına girince cam olur. İçerikte bir `AndroidView` ile
  `WebView` varsa onun çizimi de her karede, her cam yüzey için bir kez daha, başka boyut ve kırpmayla
  çalıştırılır.
- **Kayıtta** (S24, alpha.1, bülten iletisi, varsayılan ayarlar):
  - ileti dururken (0–4,4 sn; 432 kare) içerik hep tam;
  - kaydırma başlayınca (üst çubuk cam olur) içerik **tam / eksik / boş** arasında gidip geliyor:
    4,70–6,55 sn arasındaki 177 karenin %46'sı tam, %31'i eksik, %23'ü boş; 1,84 saniyede 108 değişim
    (saniyede yaklaşık 59). Kaydırma durunca yine tam;
  - eksik parçalar WebView'ın karoları: ekranın yarısı genişliğinde (540 piksel) sütunlar, yaklaşık 490
    piksellik şeritler. Eksilen yalnızca WebView'ın içeriği: çubuklar ve kaydırma çubuğu hep yerinde.
- **Yorum** ("Cam çubuklar" kapalıyken titreme olmadığı cihazda görüldü, 12 Kasım 2026):
  WebView kendi çizimini karede bir kez, tek bir görünüm alanı için yapılacak diye kurar; ikinci ve
  üçüncü çizim başka alan ve ölçek bildirince karolarını atıp yeniden üretir.
- **İstek:** biri yeter:
  - ekranın bir görünümü cam kaynağının dışında tutabilmesi (ör. `Modifier.outsideGlass()` ya da kaynağa
    girmeyen ikinci bir içerik katmanı): çubuk o görünümün üstünde bulanık zemini gösterir, görünüm iki
    kez çizilmez;
  - kabuğun, kaynağında yeniden çizilemeyen bir görünüm varken (WebView, SurfaceView, TextureView)
    bulanıklığı kendiliğinden bırakması;
  - en azından belgede uyarı ve ekran başına cam modu (`LocalGlassMode` bugün kabuğun üstünden
    verilebiliyor ama statik: değişince kabuğun tamamı yeniden bileşir).
- **Şimdilik:** DeskMail, kabuğun camı bulanıkken iletinin WebView'ını kendi donanım katmanına çizer
  (`ui/mail/MessageBody.kt`: `webLayerFor`): WebView bir kez çizilir, pencere ve her cam yüzey hazır
  resmi o katmandan alır (0.1.0-alpha.3; cihazda doğrulanacak). Katmana sığmayan uzun iletide
  (`LayerLimits`) ve "İletinin üstünde cam" denemesi kapalıyken kabuğa `glass = false` verir
  (`shellGlass`); alpha.2 bunu her iletide yapıyordu. İstek yine geçerli: katman, iletinin boyu kadar
  bellek tutar ve uzun iletide çare değildir.

## 21. Bildirimin süresi verilebilsin (küçük)

- **Bugün:** `ToastState.show(duration = …)` üç değer alır: `Short` (4 sn), `Long` (10 sn), `Indefinite`
  (`components/Feedback.kt`). Süre milisaniye olarak verilemiyor.
- **İstek:** `show(…, durationMs: Long)` ya da `ToastDuration.For(ms)`; TalkBack için uzatma
  (`calculateRecommendedTimeoutMillis`) bu süreye de uygulansın.
- **Neden:** gönderiyi geri alma süresi ayarlanır (5, 10, 15 sn) ve bildirim tam o kadar durmalı:
  bildirim giderse ileti gider.
- **Şimdilik:** bildirim `Indefinite` gösterilir ve uygulama onu süre dolunca kendisi kapatır
  (`withTimeoutOrNull`). TalkBack kullanan biri için süre uzamaz; 15 saniyeyi seçebilir.
