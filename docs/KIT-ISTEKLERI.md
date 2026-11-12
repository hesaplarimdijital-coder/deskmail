# desktop-kit'ten istenenler

DeskMail'i kurarken desktop-kit 2.4.0'da eksik kalan genel parçalar. Karar (12 Kasım 2026): bunlar
uygulamada yamanmaz, **kite yazılır** (kitin kendi sohbetinde). Kit yeni bir etiket yayımlayana kadar
DeskMail her maddede "Şimdilik" satırındaki yolla ilerler; etiket gelince sürüm yükseltilir ve geçici yol
kaldırılır.

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
  lambda'sı da değişir ve yeni bir grafik kurulur; rota kümesi farklıysa Navigation geri yığınını ve
  kayıtlı ekran durumlarını sıfırlar. (Koddan okunan davranış; DeskMail'deki test sonucu buraya
  eklenecek.) `NavItem`'da girinti ve menü yok.
- **İstek:**
  - öğe kümesi değişince açık ekranı, geri yığınını ve kaydırma konumlarını koruyan bir yol (ör. rota
    kümesi değişse de aynı kalan ekranların durumunu taşımak ya da argümanlı tek bir hedef);
  - `NavItem(indent = …)` ya da alt öğeler (iç içe klasörler), açılıp kapanan bölüm (hesap);
  - kenar çubuğu ve ray öğesinde sağ tık / uzun basma menüsü (`menu: () -> List<MenuEntry>`):
    "Tümünü okundu say", "Çöpü boşalt";
  - telefonda `MoreStyle.Sheet` taşan bütün rotaları listeliyor; çok öğeli bölümleri sayfada gizleme
    ya da daraltma seçeneği.
- **Neden:** e-postada kenar çubuğu hesaplara ve klasörlere göre değişir: hesap eklenir, sunucuda
  klasör açılır.
- **Şimdilik:** klasörler `NavItem` olarak verilir; ekran durumu ViewModel'de tutulur ve liste
  değişince `ShellSpec(startRoute = açık rota)` ile aynı klasörde kalınır; alt klasörler yolu yazılarak
  ("Projeler / 2026") gösterilir; klasör menüsü Klasörler ekranındaki satırlardadır.

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
