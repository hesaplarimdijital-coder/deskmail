# desktop-kit'ten istenenler

DeskMail'i kurarken desktop-kit 2.4.0'da eksik kalan genel parçalar. Karar (12 Kasım 2026): bunlar
uygulamada yamanmaz, **kite yazılır** (kitin kendi sohbetinde). Kit yeni bir etiket yayımlayana kadar
DeskMail her maddede "Şimdilik" satırındaki yolla ilerler; etiket gelince sürüm yükseltilir ve geçici yol
kaldırılır.

**desktop-kit 2.8.0 (13 Kasım 2026)** 1–23'ün hepsini getirdi; DeskMail 2.8.0'a geçti (`0.1.0-beta.2`).
Her maddenin "2.8.0" satırı kitin ne getirdiğini ve DeskMail'in onunla ne yaptığını söyler; o maddelerdeki
"Bugün" satırları 2.4.0'ı anlatır. **Açık olanlar:** 4 (çipli alıcı alanı: 26'yı bekliyor), 24 (biçimli
metin düzenleyici), 25 (tarih ve saat seçici), 26 (çipli alanda bitirilmemiş yazı).

**desktop-kit 2.9.0 (13 Kasım 2026)** bir isteğe karşılık değil: camın telefonun alt kenarındaki hatasını
düzeltir ve `HideBottomNavigation()`'ı getirir. DeskMail 2.9.0'a geçti (`0.1.0-beta.3`); telefonda hesap
eklerken alt gezinme çubuğu yok (yazma ekranı çubuğu tutar). Açık maddeler aynı.

Nereden geldikleri: 12, 13, 18 ve 20 istek değil, hata. 14–17 alpha.2'de (gerçek posta ve hesap ekranları)
eklendi; 18–20 alpha.1'in Galaxy S24'teki iki ekran kaydından (12 Kasım 2026), kare kare
bakılarak; 21 ve 4. maddenin eki alpha.3'te, yazma ekranıyla; 22 Barış'ın isteğiyle (13 Kasım 2026); 23
beta.1'in şablonlarıyla; 24 Barış'ın kararıyla (biçimli yazma kitte, 13 Kasım 2026); 25 beta.1'in
ertelemesiyle; 26 2.8.0 bağlanırken.

Satır ve dosya adları, 26 dışında, kitin 2.4.0 etiketine göredir.

## 1. Rozetler alt çubukta ve rayda da görünsün

- **Bugün:** `DesktopShell(badges = …)` rozetleri yalnızca kenar çubuğuna iletir
  (`shell/NavigationChrome.kt`: `Sidebar` alır; `BottomNavBar` ve `NavRail`'in `badges` parametresi yok).
- **İstek:** `BottomNavBar` ve `NavRail` öğelerinde de rozet: simgenin köşesinde küçük sayı (`CountBadge`
  boyunda) ya da nokta; TalkBack öğenin adıyla birlikte okur ("Gelen, 4 okunmamış").
- **Neden:** telefonda "Gelen" sekmesinde okunmamış sayısı hiç görünmüyor.
- **2.8.0:** `DesktopShell(iconBadges)`. Gelen ve Okunmamış simgelerinin köşesinde okunmamış sayısı
  (`navIconBadges`); TalkBack "Gelen, 4 okunmamış" der. (Önceden sayı telefonda yalnızca Klasörler
  ekranındaydı.)

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
- **2.8.0:** `DenseTable(rowEmphasis)`, `TableColumn(cell, onCellClick, cellClickLabel)`. Okunmamış
  ileti baştan sona kalın; okunmamış noktası, yıldız, yanıtlandı/iletildi ve ataç kendi sütunlarında
  simge; yıldızın hücresi iletiyi açmadan yıldızlar ya da kaldırır. Sütunun değeri sıralamaya kalır.
  (Önceden "●", "★" ve ek sayısı yazıydı.)

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
- **2.8.0:** hepsi.
  - Kabuk grafiği bir kez kurar ve rotaları ekler: yeni klasörde, hesap eklenince ya da kaldırılınca açık
    ekran ve durumu kalır (`ShellNavigationTest.aNewFolderKeepsTheOpenScreenAndWhatItSaved`).
    Uygulamanın açık ekrana geri dönüşü kalktı.
  - Klasörler `NavItem(parent, showIn = Sidebar)`: yalnız kenar çubuğunda; kullanıcının bir klasörünün
    içindeki klasör onun altında girintili, kendi adıyla ("2026"); başlıkta ve palette yoluyla
    ("Projeler / 2026"). "Klasörler" ve "Ara" yalnız alt çubukta ve rayda. Hesabın bölümü açılıp
    kapanır (`NavSection(collapsible = true)`).
  - Telefonda ve rayda klasör `navigator.open` ile Klasörler'in sayfası olarak açılır: "Klasörler"
    işaretli kalır, üst çubuğun Geri düğmesini kit verir. "⋯" sayfası klasörleri listelemiyor.
  - Girişlerin menüsü (`DesktopShell(navMenu)`): "Eşitle", tablette ve DeX'te "Yeni pencerede aç".
    "Tümünü okundu say" ve "Çöpü boşalt" uygulamada henüz yok (`docs/PLAN.md`).

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
- **2.8.0:** `TokenField(suggestions)` ve `DeskTextField(TextFieldValue)` geldi. **DeskMail
  `TokenField`'ı bağlamadı:** virgülle ya da Enter'la bitirilmemiş adres alanın kendi içinde kalıyor ve
  uygulamaya ulaşmıyor; bağlansaydı "Gönder" o adresi sessizce düşürürdü (madde 26).
- **Şimdilik:** virgülle ayrılmış düz `DeskTextField`; yazarken öneri yok. Öneri verisi hazır
  (`MailComposer.suggest`: geçmiş iletilerin adresleri); 26 gelince bağlanır.

## 5. Telefonda yüzen birincil eylem düğmesi

- **Bugün:** kabukta alt çubuğun üstünde duran, çubukla birlikte çekilen bir düğme yuvası yok.
- **İstek:** kitin çizgisinde (Material FAB değil) yüzen birincil düğme: alt çubuğun üstünde, çubuk
  kaydırmayla çekilirken onunla birlikte hareket eder, ekran klavyesi açıkken gizlenir.
- **2.8.0:** `ShellPrimaryAction`. "Yeni ileti" telefonda yüzen düğme (ileti açıkken, seçim çubuğu
  varken ve hesap yokken yok), rayın ve kenar çubuğunun en üstünde vurgulu düğme. (Önceden telefonun
  üst çubuğunda vurgulu simgeydi; rayda ve DeX'te yalnızca menüde ve Ctrl+N ile.)

## 6. `StatusBanner`'da ikinci eylem

- **Bugün:** tek `actionLabel`/`onAction` ve kapatma düğmesi (`components/Feedback.kt`).
- **İstek:** ikinci eylem (`secondaryActionLabel`/`onSecondaryAction`); telefonda ikisi metnin altında.
- **Neden:** "Uzak görseller engellendi · Göster · Bu gönderenden hep göster".
- **2.8.0:** `StatusBanner(secondaryActionLabel, onSecondaryAction)`. Şeritte "Göster" ve "Bu
  göndericinin görsellerini hep göster" (önceden şeridin altında ayrı bir düğmeydi).

## 7. Liste satırında kaydırma eylemleri (Aşama 2)

- **İstek:** `DeskListRow`'da yana kaydırma eylemleri (arşivle, ertele): parmağı izler, eşikte titreşir,
  geri alınabilir; fare ve klavyede etkisiz.
- **2.8.0:** `DeskListRow(swipeStart, swipeEnd)`. Sağa çekmek arşivler (geri alma bildirimiyle), sola
  çekmek "Ertele…" seçimini açar (`removesRow = false`); arşivlenemeyen ya da ertelenemeyen satırda o yön
  yok, seçim yapılırken ikisi de kapalı.

## 8. `DeskListRow`'da önizleme satırı (küçük)

- **Bugün:** tek satır başlık ve tek satır alt başlık.
- **İstek:** `subtitleMaxLines` ya da ikinci alt satır (gönderen / konu / önizleme).
- **2.8.0:** `DeskListRow(preview, previewMaxLines)`. Satır: gönderen, konu, bir satır önizleme. Tablo
  "Konu — önizleme"yi tek hücrede tutar.

## 9. `DetailTransition`: ayrıntı açıkken listenin durumu kayboluyor

- **Bugün:** telefonda ayrıntı tamamen açılınca `list` içeriği bileşimden çıkıyor
  (`shell/DetailTransition.kt`: `transition.AnimatedContent { target -> if (target == null) list() … }`);
  `AnimatedContent` çıkan içeriğin `rememberSaveable` durumunu saklamıyor. Geri dönünce liste yeniden
  kuruluyor: içinde hatırlanan kaydırma konumu, açık bölümler, yazılmış süzgeç sıfırlanıyor.
  DeskMail'de görüldü: Ayarlar'da aşağı kaydırıp günlüğü aç, geri dön → Ayarlar en başta
  (`LogScreenTest`, 12 Kasım 2026).
- **İstek:** `list` ve `detailContent` bir `SaveableStateHolder` içinde dursun (Navigation'ın ekranlar
  için yaptığı gibi); liste geri gelince kendi durumunu bulsun.
- **2.8.0:** liste bir `SaveableStateHolder` içinde. DeskMail'in durumu dışarıda tutması da doğru
  çalışıyor; değiştirilmedi.

## 10. Kısayollar tuşa göre eşleşiyor: Türkçe klavyede "/" yok

- **Bugün:** `KeyChord` tuş koduyla eşleşir (`input/Shortcuts.kt`: `event.key == key`). Türkçe Q
  klavyede "/" ayrı bir tuş değil, Shift+7 ile yazılır; `Key.Slash` hiç gelmez. Kitin kendi
  "Klavye kısayolları" kısayolu (Ctrl+/) da bu yüzden Türkçe klavyede çalışmaz.
- **İstek:** noktalama tuşları için yazılan karaktere göre eşleşme (`KeyChord(char = '/')` ya da
  `matches` içinde `unicodeChar` denemesi), ya da bir kısayola ikinci bir tuş bileşimi.
- **2.8.0:** noktalama kısayolları yazdıkları karakterle de eşleşir. `KeyRouter.typesSlash` kalktı;
  Ctrl+/ de Türkçe klavyede çalışır.

## 11. Ayrıntı paneli kapalı başlayabilsin (küçük)

- **Bugün:** `rememberInspectorState(prefs)` paneli ilk açılışta açık getirir
  (`DeskPreferences.inspectorCollapsed` varsayılanı `false`), ve durum ekrandan ekrana ortak değildir.
- **İstek:** `rememberInspectorState(prefs, defaultCollapsed = true)`.
- **2.8.0:** `rememberInspectorState(defaultCollapsed = true)`. DeskMail kendi `InspectorState`'ini
  tutmaya devam ediyor (kapalı başlar, genişliği ve durumu kendi ayarlarında, pencereler arasında ortak):
  kitinkine geçmek saklı seçimi sıfırlardı, bir şey kazandırmaz.

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
- **2.8.0:** düzeltildi (kabuk sayacı bileşimde de okur). DeskMail dolguyu yine ekranın kendi
  bileşiminde ister ve aşağıya verir; kitin notuna göre ikisi de doğru.
  `MailScreenTest.aPhonesListAndReaderLieUnderTheBars` duruyor.

## 13. Bölücü sürüklenirken `SplitState` sınırların dışına taşıyor (küçük hata)

Koddan okundu, cihazda denenmedi.

- **Bugün:** `HorizontalSplitPane` sürüklemede `state.startWidthDp += delta` yapar, sınırı yalnızca
  ölçümde uygular (`components/SplitPane.kt`: `wanted.coerceIn(minStartPx, maxStart)`). İşaretçi sınırı
  geçince durumdaki değer görünmeyen bir yere kaymayı sürdürür; geri sürüklerken o kadar yol boşa gider
  (bölme kıpırdamaz). Saklanan değer de sınırın dışında kalabilir.
- **İstek:** sürüklemede ve ok tuşlarında değer, son ölçümdeki sınırlara kıstırılsın.
- **2.8.0:** düzeltildi; ayrıca `rememberSplitState(genislik, prefs, anahtar)`. DeskMail payı (genişlik /
  alan) kendi ayarlarında tutmaya devam ediyor (`MailLayout.listShare`); değiştirilmedi.

## 14. Çekerek yenileme

- **Bugün:** kitte liste için "aşağı çek, yenile" hareketi ve göstergesi yok.
- **İstek:** `LazyColumn`/`DenseTable` ile çalışan, kitin hareket ve dokunuş diline uyan bir çekerek
  yenileme sarmalayıcısı (`refreshing: Boolean`, `onRefresh`); kabuk çubuklarının altından geçen listede
  gösterge üst çubuğun altında dursun. Fare ve klavyede etkisiz.
- **Neden:** telefonda e-posta listesini yenilemenin alışılmış yolu.
- **2.8.0:** `PullToRefresh`. Telefonda ve rayın yanında liste aşağı çekilince görünüm eşitlenir; disk,
  çekişin başlattığı eşitleme bitene kadar döner. Uygulamanın kendi başlattığı eşitlemelerde listenin
  üstündeki ince çubuk kaldı; "Eşitle" düğmesi ve F5 de.

## 15. Alt sayfanın başlığı rayda görünmüyor (küçük)

- **Bugün:** `PageHeader` başlığı yalnızca kenar çubuğu düzeninde yazar; `CompactTopBar` yalnızca telefonda
  etkilidir. Rayda (tablet) üst çubuk rotanın etiketini gösterir: Ayarlar'ın üstüne açılan bir sayfanın
  ("Hesap ekle", "Günlük", bir hesabın sayfası) kendi başlığını yazacağı yer yok; yalnızca "Geri" düğmesi
  görünür.
- **İstek:** `CompactTopBar`'ın başlık ve geri düğmesi rayda da geçerli olsun, ya da `PageHeader`'a
  "başlığı her düzende göster" seçeneği.
- **2.8.0:** `CompactTopBar(showOnRail = true)`. Ayarlar'ın alt sayfaları (hesap ekle, hesap, günlük,
  lisanslar) rayda da üst çubukta başlığını, Geri'sini ve eylemlerini gösterir; sistemin Geri'si sayfayı
  kapatır. Sayfa başlığındaki Geri düğmesi yalnız kenar çubuklu düzende kaldı.

## 16. `MessageScreen`'in düğmesi vurgulu olamıyor (küçük)

- **Bugün:** `MessageScreen(actionLabel, onAction)` düz bir `DeskButton` çizer.
- **İstek:** `actionPrimary: Boolean = false`.
- **Neden:** "Henüz hesap yok · Hesap ekle" ekranında tek ve asıl eylem bu düğme.
- **2.8.0:** `MessageScreen(actionPrimary = true)`. "Hesap ekle" vurgulu.

## 17. Ayar kartında satırın tamamına dokunma, ve sakıncalı eylem düğmesi (küçük)

- **Bugün:** `SettingRow(title, subtitle, control)` satırı tıklanamaz; `DeskListRow` kartın içinde kendi
  yatay dolgusuyla girintili durur. `DeskButton`'ın yalnızca düz ve vurgulu hâli var.
- **İstek:** `SettingRow(onClick = …)` (satırın tamamı, sonunda ok), ve `DeskButton(danger = true)`
  (ya da `tone`) geri alınamayan eylemler için.
- **Neden:** Ayarlar › Hesaplar'da hesap satırı; "Hesabı kaldır".
- **2.8.0:** `SettingRow(onClick)`, `DeskButton(danger = true)`. Hesabın satırının tamamı sayfasını açar
  ("Yönet" kalktı); "Hesabı kaldır" tehlike renginde, onay penceresi duruyor.

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
- **2.8.0:** düzeltildi (bırakılan ekranın çağrısı sayılmaz; başlık sözcükleri değişmedikçe solmaz).
  Uygulamanın yapacağı bir şey yok; cihazda bakılacak.

## 19. `open()` yayı tam sayfa geçişinde yerine otururken taşıyor (küçük)

- **Bugün:** `DetailTransition` telefonda `DeskMotion.open()` yayını kullanır (0,9 / 700); belgesi
  "taşması görülmeyecek kadar küçük" der.
- **Kayıtta:** ileti sayfası sağdan gelip yerine oturduktan sonra **2 piksel sola taşıyor ve geri
  geliyor** (38,902–38,987 sn: −1, −2, −1, 0 piksel; 85 ms). Sayfanın tamamı (başlık ve gövde birlikte)
  kayıyor. Metinle dolu bir sayfada bu, yerine otururken kısa bir titreme gibi okunuyor.
- **İstek:** tam ekran sayfa geçişlerinde taşmayan bir yay (sönüm 1,0) ya da son pikselde kırpma;
  küçük öğelerdeki hafif sekme kalabilir.
- **2.8.0:** `DeskMotion.page()` yerini geçmez. Uygulamanın yapacağı bir şey yok; cihazda bakılacak.

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
- **2.8.0:** `PauseGlassBlur()`. İleti ekrandayken cam bulanıklaştırmaz, yarı saydam tonda kalır; uzun
  iletide de. DeskMail'in yolu (WebView'ı kendi donanım katmanına çizmek: `webLayerFor`, `LayerLimits`)
  ve "WebView katmanı" denemesi kalktı. "İletinin üstünde cam" kapalıyken ileti açıkken çubuklar düz
  (alpha.2'deki gibi). Cihazda bakılacak.

## 21. Bildirimin süresi verilebilsin (küçük)

- **Bugün:** `ToastState.show(duration = …)` üç değer alır: `Short` (4 sn), `Long` (10 sn), `Indefinite`
  (`components/Feedback.kt`). Süre milisaniye olarak verilemiyor.
- **İstek:** `show(…, durationMs: Long)` ya da `ToastDuration.For(ms)`; TalkBack için uzatma
  (`calculateRecommendedTimeoutMillis`) bu süreye de uygulansın.
- **Neden:** gönderiyi geri alma süresi ayarlanır (5, 10, 15 sn) ve bildirim tam o kadar durmalı:
  bildirim giderse ileti gider.
- **2.8.0:** `ToastState.show(durationMillis = …)`; imleç üstündeyken, odak içindeyken ve TalkBack için
  süre uzar.
- **Karar: DeskMail kendi saatini tutar** (`Indefinite` + `withTimeoutOrNull`). Gönderim zamanı sabittir
  (`sendAfter = şimdi + süre`); kitin süresi uzayınca bildirim, ileti gittikten sonra da "Geri al" diye
  ekranda kalırdı. TalkBack kullanan biri daha uzun süreyi (15 sn) seçebilir.

## 22. Tabloda fareyle çerçeve çizerek seçme

- **Bugün:** `DenseTable` çoklu seçimde Ctrl+tık, Shift+tık, Ctrl+A, Shift+↑/↓ ve dokunmada uzun basmayı
  bilir (`components/DenseTable.kt`, `TableSelection`). Fareyle sol düğme basılıyken sürükleyince çizilen
  seçim çerçevesi yok; satır `dragSource` ise sürükleme satırı taşır.
- **İstek:** çoklu seçim açıkken `DenseTable(marqueeSelection = true)` (ya da kendiliğinden):
  - fareyle (dokunmayla ve kalemle değil) başlayan sürükleme bir çerçeve çizer; çerçevenin değdiği
    satırlar seçilir, çerçeve daralınca seçimden çıkar; Ctrl basılıyken var olan seçime eklenir ya da
    ondan çıkarılır, Shift ekler;
  - çerçeve tablonun üst ya da alt kenarına gelince liste kendiliğinden kayar; çerçeve içerikle birlikte
    kayar (başladığı satıra bağlı kalır);
  - Esc sürüklemeyi bırakır ve seçimi sürükleme öncesine döndürür;
  - sürüklenebilir satırlarla kural (kit karar verip belgelesin; dosya yöneticilerindeki alışkanlık):
    seçili bir satırdan başlayan sürükleme seçileni taşır, seçili olmayan satırdan ya da boşluktan
    başlayan sürükleme çerçeve çizer;
  - çerçeve vurgu renginde ince çizgi ve yarı saydam dolgu; `onSelectionChange` her değişimde çağrılır;
  - aynı davranış `DeskListRow` listeleri ve `Grid` için de düşünülebilir.
- **Neden:** DeX'te ve tablette fareyle çalışırken dosya yöneticilerinden alışılan yol. Barış: "sol klik
  ile mouse'u sürüklediğimde bir kare çıkıyor, o karenin içindekiler seçili oluyor ya, ondan olsun."
- **2.8.0:** tablo fareyle çerçeve çizerek seçer (seçili olmayan satırdan ya da satırların altından);
  seçili satırdan sürüklemek seçimi klasöre taşır. Uygulamanın yapacağı bir şey yok.

## 23. Metin alanında imleç ve seçim

- **Bugün:** `DeskTextField` yalnızca `String` alır ve verir (`components/Forms.kt`); imlecin yeri ve
  seçili metin uygulamaya gelmez, uygulama da imleci bir yere koyamaz ya da bir bölümü seçemez.
- **İstek:** `TextFieldValue` (ya da `TextFieldState`) alan bir eşi: `DeskTextField(value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit, …)`; görünüşü ve davranışı bugünküyle aynı.
- **Neden:** yazarken seçilen şablon imlecin olduğu yere eklenmeli; biçimli (HTML) yazmada kalın, eğik,
  bağlantı gibi biçimler seçili metne uygulanır; ikisi de imlecin ve seçimin bilinmesini istiyor.
- **2.8.0:** `DeskTextField(value: TextFieldValue, onValueChange)`. Yazma ekranının metni imleci ve
  seçimi tutar (`ComposeFields.bodyValue`); şablon imlecin olduğu yere kendi paragrafı olarak, seçim
  varsa onun yerine eklenir (`Templates.insertAt`).

## 24. Biçimli metin düzenleyici (HTML yazma)

- **Bugün:** kitte yalnızca düz metin alanı var (`DeskTextField`, `String`); biçim, imleç ve seçim
  uygulamaya gelmiyor (madde 23).
- **İstek:** kitin kendi biçimli metin düzenleyicisi (adı kitin; ör. `DeskRichTextEditor` ve onun
  durumunu tutan `RichTextState`):
  - **Biçimler:** kalın, eğik, altı çizili, üstü çizili; madde işaretli ve numaralı liste (girinti
    artır/azalt); alıntı bloğu; bağlantı ekle, düzenle, kaldır; biçimi temizle. Başlık düzeyleri
    (1–3) olabilir; renk ve yazı boyutu gerekmez.
  - **Araç çubuğu:** kitin düğmeleriyle; her düğme seçimin biçimini gösterir (açık/kapalı). Dar pencerede
    sığmayanlar bir "Diğer" menüsünde. Telefonda klavyenin üstünde durabilmeli.
  - **Kısayollar:** Ctrl+B, Ctrl+I, Ctrl+U, Ctrl+K (bağlantı), Ctrl+Shift+7 / Ctrl+Shift+8 (numaralı /
    madde işaretli liste), Ctrl+Z / Ctrl+Y (geri al / yinele); Türkçe klavyede de (madde 10).
  - **Girdi ve çıktı:** HTML'nin e-postada güvenle taşınan bir alt kümesi (`p`, `br`, `b`/`strong`,
    `i`/`em`, `u`, `s`, `a[href]`, `ul`/`ol`/`li`, `blockquote`, `h1`–`h3`) olarak `toHtml()`, aynı içeriğin
    düz metin karşılığı olarak `toPlainText()` (iletinin `multipart/alternative` düz metin parçası için:
    listeler "- " ve "1. " ile, bağlantılar "metin <adres>" diye) ve `setHtml(html)` (taslağı yeniden açmak,
    yanıtın alıntısı ve imza için). Alt kümenin dışındaki etiketler metniyle kalır, biçimi düşer.
  - **İmleç ve seçim:** imlecin yerine metin ya da HTML koyma (`insertAtCursor`; şablonlar için), seçili
    metni okuma, programla seçme (madde 23 ile aynı ihtiyaç).
  - **Yapıştırma:** başka bir uygulamadan gelen HTML yukarıdaki alt kümeye indirgenir (betik, stil,
    resim gelmez); Ctrl+Shift+V düz metin yapıştırır.
  - **Fare ve dokunma:** DeX'te fareyle seçme ve sağ tıkta biçim menüsü; dokunmada seçim tutamaçları ve
    sistemin kopyala/yapıştır çubuğu.
  - **Erişilebilirlik:** TalkBack biçimi okur ("kalın", "bağlantı"); araç çubuğu düğmeleri durumlarını
    söyler.
  - **Uzunluk:** binlerce satırlık bir alıntıda yazarken takılmamalı.
- **Neden:** Aşama 2'nin "Biçimli (HTML) yazma" maddesi. Barış (13 Kasım 2026): "Biçimli yazma editörü
  kitte olsun."
- **Şimdilik:** iletiler düz metin yazılır ve gönderilir; yanıtın alıntısı ve imza da düz metindir. Kit
  yayımlayınca uygulamanın payı: yazma ekranına bağlamak, taslakta HTML'yi saklamak (şema), iletiyi
  `multipart/alternative` olarak yazmak (`MessageWriter`) ve yanıtta alıntıyı HTML olarak kurmak.

## 25. Tarih ve saat seçici

- **Bugün:** kitte gün ya da saat seçtiren bir bileşen yok.
- **İstek:** kitin kendi tarih ve saat seçicisi (adı kitin; ör. `DeskDateTimePicker`), kitin iletişim
  kutusunda açılabilen:
  - **Takvim:** ay görünümü, ayın adı ve yılı, önceki ve sonraki ay; bugün işaretli; en erken seçilebilecek
    gün verilebilsin (geçmiş günler seçilemesin). Haftanın ilk günü ve gün adları dilden (Türkçede pazartesi).
  - **Saat:** saat ve dakika; 24 saat ya da ÖÖ/ÖS sistemin ayarından. Klavyeyle yazılarak da girilebilsin.
  - **Klavye ve fare:** DeX'te ok tuşlarıyla günler arasında gezinme, Enter ile seçme, Esc ile kapatma.
  - **Dönen değer:** seçilen an, saat dilimiyle (`ZonedDateTime`).
  - **Erişilebilirlik:** TalkBack günü tam okur ("14 Kasım Cumartesi").
- **Neden:** ertelemede yalnızca hazır süreler var (bu akşam, yarın sabah, hafta sonu, gelecek hafta);
  "Gün ve saat seç…" bu seçiciyi bekliyor.
- **Şimdilik:** hazır süreler.

## 26. `TokenField`: bitirilmemiş yazı uygulamaya ulaşmıyor

2.8.0'ın kaynağından okundu, cihazda denenmedi.

- **Bugün:** `TokenField` yazılan metni kendi durumunda tutar (`components/TokenField.kt`:
  `var text by remember { mutableStateOf(TextFieldValue("")) }`). Metin yalnızca ayırıcı, Enter ya da
  klavyenin eylem tuşuyla çip olur; `onTokensChange` ancak o zaman çağrılır. Odak alandan çıkınca da metin
  olduğu gibi kalır.
- **Sonuç:** "ayse@ornek.com" yazıp virgül koymadan Gönder'e (ya da Ctrl+Enter'a) basan kullanıcının
  adresi alıcılara girmez: ileti o adres olmadan gider ya da "alıcı yok" denir; taslak kaydı da o adresi
  tutmaz. E-postada adres sessizce düşer. Gönder düğmesine dokunmak odağı alandan almaz, o yüzden "odak
  çıkınca çip yap" tek başına yetmez.
- **İstek:** yazılan metin de uygulamaya gelsin: durumu uygulamanın tuttuğu bir `TokenFieldState`
  (`tokens`, yazılan `text`, `commit()`: yazılanı çip yapar) ya da `TokenField(text, onTextChange)`.
  Uygulama Gönder'de ve taslağı kaydederken yazılanı da sayar. Ek olarak odak alandan çıkınca yazılanın
  çip olması (Gmail ve Outlook böyle) iyi olur.
- **Şimdilik:** alıcılar virgülle ayrılmış düz `DeskTextField`'da (madde 4).
