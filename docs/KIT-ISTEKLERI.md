# desktop-kit'ten istenenler

DeskMail'i kurarken desktop-kit 2.4.0'da eksik kalan genel parçalar. Karar (12 Kasım 2026): bunlar
uygulamada yamanmaz, **kite yazılır** (kitin kendi sohbetinde). Kit yeni bir etiket yayımlayana kadar
DeskMail her maddede "Şimdilik" satırındaki yolla ilerler; etiket gelince sürüm yükseltilir ve geçici yol
kaldırılır.

Önerilen sıra: 12 (dolgu hatası), 3 (kenar çubuğu), 2 (tablo), 9 (liste durumu), 1 (rozetler),
10 (klavye düzeni), 13 (bölücü); kalanlar ilgili PR'a kadar bekleyebilir. 12 ve 13 istek değil, hata.

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
    "Klasörler" yerine "⋯" seçili görünüyor.
- **Neden:** e-postada kenar çubuğu hesaplara ve klasörlere göre değişir: hesap eklenir, sunucuda
  klasör açılır.
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
- **Şimdilik:** virgülle ayrılmış düz `DeskTextField` ve altında `MenuPopup` ile öneriler.

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
