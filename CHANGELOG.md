# Değişiklikler

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; 0.1.0'a kadar olanlar ön sürümdür (`0.1.0-alpha.N`). Her sürümün imzalı APK'sı GitHub'daki sürüm sayfasına eklenir.

## 0.1.0-alpha.3 (13 Kasım 2026)

### Okuma: cam iletinin üstünde de kalır
- alpha.2'de ileti açıkken çubuklar düz yüzeye dönüyordu. Artık cam kalır: çubuklar camken iletinin WebView'ı kendi donanım katmanına çizilir. İleti bir kez çizilir; pencere de çubukların bulanıklığı da hazır resmi o katmandan alır. (Titreme, camın altındaki içeriği her karede birkaç kez yeniden çizdirmesinden çıkıyordu.) **Cihazda doğrulanacak:** bu yol yalnızca testlerde denendi.
- Bir katmana sığmayacak kadar uzun iletide (cihazın en büyük dokusu ya da 64 MB; telefonda yaklaşık altı ekran boyu) katman kullanılmaz ve o ileti ekrandayken cam kendiliğinden kapanır.
- Ayarlar › Denemeler: "İletinin üstünde cam" (kapalıyken alpha.2'deki gibi: ileti açıkken düz çubuklar); "WebView katmanı" artık Otomatik, Yok, Donanım, Yazılım.
- Günlük, her iletide WebView'ın neye çizildiğini yazar (`WebView 1080 x 5400 px: hardware layer, glass blurred`): titreme ya da boş kalan bir bölüm olursa ilk bakılacak satır.

## 0.1.0-alpha.2 (12 Kasım 2026)

İlk gerçek posta: hesap eklenir, klasörler ve iletiler sunucudan gelir ve okunur. **Bu sürüm yalnızca okur:** okundu ve yıldız sunucuya yazılmaz; gönderme, taşıma, silme ve bildirim yok. Gmail uygulama şifresiyle çalışır; Outlook hesapları bir sonraki ön sürümde (Microsoft girişi).

### Hesaplar
- Ayarlar › Hesaplar: hesapların listesi ve her birinin durumu (çalışıyor, yeniden giriş gerekiyor, sunucuya ulaşılamadı, son eşitleme başarısız).
- Hesap ekle: adres yazılır, sunucuları aranır. Sıra: uygulamadaki sağlayıcı tablosu (Gmail, Outlook), Thunderbird'ün sağlayıcı veritabanı (ISPDB), alan adının postasını alan sunucunun (MX) sağlayıcısı, alan adının kendi SRV kayıtları (RFC 6186). Hiçbiri bilmiyorsa sunucular elle girilir. Dışarıya yalnızca adresin @ işaretinden sonraki bölümü gider.
- Gmail uygulama şifresi ister (hesapta iki adımlı doğrulama açık olmalı). Ekrandaki "Uygulama şifresi sayfasını aç" düğmesi Google'ın o sayfasını tarayıcıda açar; şifre, gösterildiği boşluklarla yapıştırılabilir.
- Outlook, Hotmail ve Live hesapları Microsoft'ta tarayıcıdan giriş ister; bu sürümde eklenemez, ekran bunu söyler.
- Hesap ancak sunucu girişi kabul ederse kaydedilir. Reddederse nedeni ve sunucunun kendi yanıtı gösterilir.
- Hesap sayfası: Şimdi eşitle, Yeniden bağlan (yeni şifreyle giriş), Hesabı kaldır (posta sunucuda kalır).
- Şifre, cihazın anahtar deposunda üretilen ve oradan çıkmayan bir anahtarla (AES-256-GCM) şifrelenerek saklanır; günlüğe ve yedeğe girmez.
- Sunuculara yalnızca şifreli bağlanılır (TLS ya da STARTTLS); şifresiz bağlantı seçeneği yoktur. Sertifikadaki adın bağlanılan sunucuya ait olduğunu uygulama kendisi denetler: posta kitaplığının bu denetimi Android'de çalışmıyor.
- Hesap yokken Gelen ve Klasörler "Hesap ekle" der.

### Posta
- Klasörler sunucudan gelir. Rolleri (Gelen Kutusu, Taslaklar, Gönderilmiş, Arşiv, Spam, Çöp, Tüm postalar) sunucunun bildirdiği özniteliklerden, yoksa Türkçe ve İngilizce adlardan bulunur. Gmail'in "Yıldızlı" ve "Önemli" görünümleri klasör sayılmaz: içlerindeki iletiler başka klasörlerde durur.
- Gelen kutusu son 30 günle eşitlenir: yeni iletiler gelir, sunucuda silinenler ve taşınanlar listeden çıkar, başka cihazda okunan ve yıldızlanan burada da öyle görünür. Diğer klasörler açılınca eşitlenir; açılmayanların okunmamış sayısı sunucudan alınır.
- İleti metinleri önce en yeni 20 ileti için, kalanı arka planda iner. Metni henüz inmemiş bir ileti açılınca sırası beklenmeden alınır.
- Birleşik görünümler gerçek postayla: Gelen (bütün hesapların gelen kutuları), Okunmamış, Yıldızlı. Gmail'in "Tüm postalar"ı bunlarda ikinci kez sayılmaz. Kenar çubuğunda okunmamış sayıları.
- Eşitleme: uygulama öne gelince ve bir klasör açılınca (son iki dakikada eşitlenmediyse), F5 ile, telefonda üst çubuktaki, diğer düzenlerde listenin üstündeki Eşitle düğmesiyle. Arka planda eşitleme ve bildirim henüz yok.
- Eşitleme sürerken listenin üstünde ince bir çizgi görünür. Postası henüz gelmemiş boş bir liste "ileti yok" demez, yer tutucu satırlar gösterir.
- Bir hesap sorunluysa listenin üstünde şerit çıkar: yeniden giriş gerekiyorsa "Yeniden bağlan", sunucuya ulaşılamadıysa "Yeniden dene".

### Okuma
- İletinin HTML'i saklanmadan önce temizlenir: betik, çerçeve, form, olay işleyici ve dış stil kalmaz.
- "Uzak görseller yüklenmedi · Göster": dokununca o iletinin görselleri https üzerinden yüklenir, başka hiçbir şey yüklenmez. Pencere kapanana kadar yalnızca o ileti için geçerlidir.
- Metin gelirken yer tutucu görünür; alınamazsa nedeni ve "Yeniden dene". Çok büyük metinler cihaza alınmaz, ileti bunu söyler.
- Başlıkta "Bilgi" (Cc) satırı; konusuz iletide "(konu yok)". Ekler adları ve boyutlarıyla listelenir.

### alpha.1'in telefondaki denemesinden (Galaxy S24, ekran kayıtları)
- **İletideki titreme:** cam çubuklar açıkken kaydırılan iletinin içeriği saniyede onlarca kez parça parça kaybolup geri geliyordu. İleti açıkken çubuklar artık bulanık cam değil, düz yüzey; başka her yerde cam duruyor. Nedeni kitin bulanıklığı ile WebView'ın birlikte çalışmaması (kit isteklerinde 20. madde). Düzeldiği cihazda doğrulanacak.
- **İlk ileti geç açılıyordu** (sayfa 0,25 sn sonra, metni 0,25 sn daha sonra): WebView artık uygulama açılınca hazırlanıyor; süre günlüğe yazılıyor.
- Kite yazılanlar (uygulamada değişmedi): ekran değişirken üst çubuktaki başlığın yanıp sönmesi (18), ileti sayfasının yerine otururken 2 piksel taşması (19).
- Bilinen: sabit genişlikli bültenler ekrana sığdırılmıyor, sağı kesik kalıyor.

### Günlük
- Sunucuyla konuşurken çıkan her hata, ona yol açan nedenlerin tamamıyla tek satırda yazılır (hangi hata, neyin içinden çıktı). İlk bağlantıda sunucunun yetenekleri de yazılır.
- Sunucunun ya da ağın değil, cihazın ya da uygulamanın kendi hatası (kitaplığın cihazda çalışmayan bir parçası, anahtar deposunun şifreyi saklayamaması) uygulamayı kapatmaz: ekranda adıyla söylenir, günlüğe nerede olduğuyla yazılır.

### Bu sürümde bilerek olmayanlar
- Okundu ve yıldız: sunucudaki durum gösterilir, değiştirilemez; açılan ileti okunmamış kalır (alpha.4).
- Outlook girişi (alpha.3); ekleri açma, taşıma ve silme (alpha.4); yazma ve gönderme (alpha.5; hesabın SMTP ayarları ilk kez o zaman denenir); arama (alpha.6); bildirim ve arka planda eşitleme (alpha.7).
- İletinin içine gömülü resimler (`cid:`) henüz görünmez.

### Altyapı
- Veritabanı: Room 3.0.3, şema sürümü 1 (`app/schemas`): hesap, kimlik bilgisi, klasör, ileti, gövde, ek ve arama dizini (FTS4; Türkçe harfler katlanarak yazılır). Şema, sürümü artırılmadan değişirse CI durur.
- Yeni kitaplıklar: Angus Mail 2.0.5 (IMAP ve MIME), jsoup 1.24.1 (HTML temizleme), kotlinx-coroutines 1.11.0, KSP 2.3.13; testlerde GreenMail 2.1.15.
- Testler IMAP'i ve eşitlemeyi test içinde çalışan bir posta sunucusuna karşı sınar; `:app` aynı sunucuyla gerçek veritabanına uçtan uca eşitler. `:core` 105 test, `:app` 154 test (27'si ekran görüntüsü).
- Yeni izin: INTERNET (posta sunucuları ve hesap eklerken sağlayıcı veritabanı).
- Uydurma posta uygulamadan çıktı; yalnızca testlerde ve ekran görüntülerinde kullanılıyor.

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
