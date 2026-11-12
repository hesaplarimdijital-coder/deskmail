# Değişiklikler

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; 0.1.0'a kadar olanlar ön sürümdür (`0.1.0-alpha.N`). Her sürümün imzalı APK'sı GitHub'daki sürüm sayfasına eklenir.

## 0.1.0-alpha.1 (12 Kasım 2026)

İlk ön sürüm: iskelet ve cihazda erken denenecekler. **Posta henüz yok**; listeler ve iletiler uydurmadır (iki hesap, klasörler, otuza yakın ileti). Okumak ve yıldızlamak yalnızca bellekteki kopyayı değiştirir, uygulama yeniden açılınca posta ilk hâline döner.

### Kabuk ve düzenler
- desktop-kit 2.4.0 kabuğu: telefonda alt çubuk (Gelen, Klasörler, Ara ve "⋯"), tablette ray, büyük pencerede ve DeX'te menü çubuğu ile kenar çubuğu. Kenar çubuğunda hesap başına klasörler ve okunmamış sayıları.
- İleti listesi: satırlar (okunmamışta nokta ve kalın gönderen, konu ve ilk sözcükler, tarih, ataş, yıldız). Büyük pencerede fare kullanılırken ve liste en az 520 dp genişken sıralanan, sütunları seçilebilen tablo; parmakla ya da dar listede yine satırlar. Süzgeç çipleri: Tümü, Okunmamış, Yıldızlı, Ekli.
- Okuma: telefonda ve dik tutulan tablette ileti listenin üstüne kayar; ray yanında yer varsa liste ile ileti yan yana; büyük pencerede liste, ileti ve açılıp kapanan ayrıntı paneli.
- Liste ile ileti arasındaki bölücü: liste alanın % 45'ini alır; sürüklenince pay değişir ve hatırlanır. Genişlik değil pay saklanır: pencere büyüyüp küçülünce ve başka boyda bir pencerede bölünme aynı kalır.
- Klasörler ekranı: telefonda ve rayda klasöre giden yol. Klasör listenin üstüne açılır, Geri listeye döner.

### Okuma bölmesi
- İletinin gövdesi WebView'da: betik yok, dosya ve içerik erişimi yok, ağdan hiçbir şey yüklenmez; bağlantıya dokununca açılmaz, gerçek adresi gösterilir.
- İleti kitin temasına uyar (sistemin değil); koyu temalarda karartılır.
- Düz metin iletilerde satır, okuma bölmesi ne kadar geniş olursa olsun seksen harf dolayında biter; HTML ileti göndereninin yaptığı gibi dizilir.
- Cihazda kullanılabilir WebView yoksa iletinin düz metni gösterilir.

### Denemeler (Ayarlar › Denemeler; telefonda "⋯" sayfasında da)
- Cam çubuklar: kapalıyken çubuklar, sayfalar ve menüler düz yüzey.
- İleti çubukların altından geçsin: kapalıyken ileti üst ve alt çubuğun arasında kalır.
- Koyu temada iletiyi karart: kapalıyken ileti beyaz zeminde, gönderenin yaptığı gibi.
- WebView katmanı (varsayılan, donanım, yazılım): cam iletiyi bulanıklaştırmıyorsa denemek için.

### Klavye
- Tek bir kısayol tablosu: menü çubuğu, komut paleti, sistemin kısayol listesi ve Ayarlar'daki liste ondan çizilir.
- Ctrl+N yeni ileti (henüz yer tutucu), Ctrl+Shift+N yeni pencere, Ctrl+K komut paleti, Ctrl+, ayarlar, Ctrl+B kenar çubuğu.
- Listede ve iletide tek tuşlar: J/K sonraki/önceki, S yıldız, U okundu/okunmadı, Esc kapat, / ara. Yazı yazılırken ve palet açıkken çalışmazlar.
- Komut paleti ekranları, klasörleri, iletileri ve komutları bulur; Ctrl+Enter ekranı yeni pencerede açar.

### Günlük
- Ayarlar › Günlük: uygulamanın yaptıkları ve ters giden şeyler. Şifreler ve jetonlar satır saklanmadan önce maskelenir. Son 400 satır kopyalanabilir, tamamı dosya olarak paylaşılabilir.
- Günlük diskte de durur (iki dosya, en çok 1 MB); beklenmeyen bir hata, uygulama kapanmadan önce günlüğe yazılır.

### Proje
- Gradle iskeleti: `:core` (saf Kotlin/JVM) ve `:app` (Android, `com.deskmail`); araçlar desktop-kit 2.4.0 ile aynı, bütün sürümler sabit.
- CI: iki modülün testleri, kurulabilir APK, çekme isteklerinde günlükler `ci/logs-pr-<numara>` ve ekran görüntüleri `ci/screenshots-pr-<numara>` dalında.
- İmza: kurulabilir her APK sabit anahtarla imzalanır; CI anahtarın ve APK'nın parmak izini denetler.
- Etiket açılınca imzalı `DeskMail-<sürüm>.apk` dosyasını sürüme ekleyen iş akışı.
- Ekran görüntüsü testleri: telefon, tablet ve DeX boyutlarında, açık, koyu ve OLED temada.
- Belgeler: plan (`docs/PLAN.md`) ve desktop-kit'ten istenenler (`docs/KIT-ISTEKLERI.md`).
