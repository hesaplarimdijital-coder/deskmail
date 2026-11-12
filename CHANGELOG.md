# Değişiklikler

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; 0.1.0'a kadar olanlar ön sürümdür (`0.1.0-alpha.N`). Her sürümün imzalı APK'sı GitHub'daki sürüm sayfasına eklenir.

## 0.1.0-alpha.3 (13 Kasım 2026)

Posta artık yalnızca okunmuyor: okundu ve yıldız sunucuya yazılır, iletiler arşivlenir, taşınır ve silinir, ekler açılır; ileti yazılır, yanıtlanır, iletilir ve gönderilir. Cam, açık iletinin üstünde de kalır. Arama ve bildirim henüz yok.

### Yazma ve gönderme
- **Yeni ileti, Yanıtla, Tümünü yanıtla, İlet.** Düz metin yazılır.
  - Yeni ileti: telefonda listenin üst çubuğundaki kalem; klavyede Ctrl+N.
  - Yanıtla, Tümünü yanıtla, İlet: iletinin altındaki üç düğme; telefonda gönderenin yanındaki ok (Yanıtla) ve üst çubuğun "⋮" menüsü; geniş pencerede okuma bölmesinin üstündeki simgeler; satıra uzun basınca ya da sağ tıklayınca açılan menü; klavyede R, A, F.
  - Yanıt: alıcı gönderendir (ileti başka bir yanıt adresi veriyorsa o), konu "Re:" ile başlar, altında "… tarihinde … yazdı:" satırı ve alıntılanmış ileti durur. Tümünü yanıtla'da iletinin gittiği öteki kişiler Bilgi'ye yazılır; kendi adreslerin çıkarılır. Kendi yazdığın iletiye yanıt, o iletinin alıcılarına gider.
  - İlet: konu "Fwd:" ile başlar; iletinin kimden, ne zaman, kime gittiği ve metni eklenir; ekleri birlikte gelir (cihazda yoksa o an sunucudan indirilir).
  - Kimden: birden çok hesap varsa yazma ekranında seçilir. Bir klasörden başlatılan yeni ileti o klasörün hesabından, yanıt iletinin geldiği hesaptan başlar.
  - Alıcılar virgülle ayrılır; "Ad Soyad <adres>" biçimi de olur. Bilgi (Cc) ve Gizli (Bcc) alanları bir düğmeyle açılır. Adres olmayan bir parça ya da boş alıcı varsa Gönder bunu alanın altında söyler; ileti gitmez.
  - Dosya ekle: sistemin dosya seçicisi açılır, birden çok dosya seçilebilir; uygulama depolama izni istemez. Dosyanın kopyası eklendiği anda alınır, sonradan silinse de ileti onunla gider.
- **Taslak kendiliğinden saklanır:** yazarken ve ekran kapanırken. Ekran kapatılınca "Taslak Giden kutusu'nda duruyor" der. Hiçbir şey yazılmadan kapatılan yeni ileti saklanmaz. Taslaklar bu sürümde yalnızca cihazdadır; sunucudaki Taslaklar klasörüne henüz yazılmaz.
- **Gönder ve Geri al:** Gönder'e basınca ileti hemen gitmez; altta "Gönderiliyor… · Geri al" bildirimi çıkar. Geri al (ya da Ctrl+Z) iletiyi yeniden yazma ekranına getirir. Süre Ayarlar › Yazma'dan 5, 10 ya da 15 saniye seçilir (başlangıçta 10). Bildirim gidince ileti gönderilir. Ctrl+Enter, hangi alanda olursan ol Gönder'dir.
- **Giden kutusu** (kenar çubuğunda; telefonda "⋯" altında): taslaklar, gönderilmeyi bekleyenler ve gönderilemeyenler; kenar çubuğunda sayısıyla. Taslağa dokununca yazmaya devam edilir. Bekleyen iletiye dokununca "Şimdi gönder" ya da "Geri al ve düzenle". Gönderilemeyen iletinin altında sunucunun kendi yanıtı ve "Yeniden dene" durur.
- **Çevrimdışı:** ağ yokken gönderilen ileti Giden kutusu'nda bekler ve ağ gelince kendiliğinden gider; uygulama kapalı olsa da. Sunucuya ulaşılamazsa artan aralıklarla yeniden denenir. Sunucu iletiyi reddederse (alıcıyı ya da girişi kabul etmezse) ileti "Gönderilemedi" olarak durur; kendiliğinden yeniden denenmez.
- **Gönderilmiş klasörü:** Gmail ve Outlook, gönderileni Gönderilmiş'e kendileri kaydeder; uygulama ikinci bir kopya yazmaz. Öteki sunucularda gönderilen ileti Gönderilmiş klasörüne eklenir (gizli alıcılarıyla birlikte).
- Yanıtlanan ileti sunucuda "yanıtlandı", iletilen "iletildi" olarak işaretlenir; öteki posta programları bunu gösterir. ("İletildi" işaretini yalnızca etiket tutan sunucular saklar: Gmail saklar, Outlook.com saklamaz.)
- **Ad ve imza:** Ayarlar › Hesaplar › hesabın sayfası › Yazarken. Ad, yazdığın kişilerin adresinin yanında gördüğü addır; boşsa yalnızca adres görünür. İmza yeni iletinin sonuna, yanıtta ve iletmede alıntının üstüne eklenir.
- Gönderim yarıda kesilirse (ileti sunucuya verilirken uygulama kapanırsa) ileti kendiliğinden ikinci kez gönderilmez: "Gönderilemedi" olarak durur ve yeniden göndermeden önce Gönderilmiş'e bakmanı söyler.

### İleti işlemleri
- **Okundu ve yıldız:** açılan ileti okundu sayılır; okundu/okunmadı ve yıldız değiştirilir ve sunucuya yazılır, öteki cihazda da öyle görünür. Yıldız, okuma ekranında gönderenin yanında.
- **Arşivle, sil, taşı:** okuma ekranının düğmeleri (telefonda üst çubukta Arşivle ve Sil, kalanı "⋮" altında); satıra uzun basınca ya da sağ tıklayınca açılan menü; klavyede E (arşivle), Delete (sil), V (taşı).
  - Arşivle: hesabın Arşiv klasörüne; Gmail'de "Tüm postalar"a (Gmail'de arşivlemek, iletiyi Gelen'den çıkarmaktır). Gelen kutusundaki ve kendi klasörlerindeki iletilerde görünür.
  - Sil: çöpe taşır. Çöpteki ileti silinirse kalıcı olarak silinir.
  - Taşı: iletinin hesabındaki klasörlerden biri seçilir (telefonda alttan açılan sayfa, geniş pencerede iletişim kutusu).
- **Geri al:** her işlemden sonra altta bildirim çıkar ("Arşivlendi · Geri al"). Bildirim dururken sunucuya henüz bir şey söylenmemiştir; geri almak anında olur. Bildirim gidince sunucuya bildirilir (bildirim hiç görülmese de en geç 15 saniye sonra). Ctrl+Z son işlemi geri alır; sunucuya bildirilmişse ileti geldiği klasöre geri taşınır. Bunun için sunucunun iletinin yeni yerini söylemesi gerekir (UIDPLUS; Gmail ve Outlook söyler); söylemeyen sunucuda bildirimden sonra geri alınamaz, ekran bunu söyler.
- **Çevrimdışı:** yapılanlar cihazda hemen görünür ve sıraya girer; sunucuya ulaşılınca, bir sonraki eşitleme sunucuyu okumadan önce bildirilir. Sıra, uygulama kapansa da durur.
- Taşınan ileti yeni klasöründe metniyle birlikte görünür; yeniden indirilmez.
- İşlem yapılan ileti açıksa okuma ekranı kapanır.

### Ekler
- Ek çipine dokununca: Aç, Kaydet…, Paylaş. Dosya ilk istendiğinde sunucudan iner (çiplerin altında ince bir çizgi görünür) ve bir hafta cihazda kalır; ikinci kez istenince inmez.
- Aç: dosya, türünü açan uygulamaya yalnızca o dosyayı okuma izniyle verilir. Kaydet: nereye kaydedileceğini sistem sorar; uygulama depolama izni istemez.
- Büyük pencerede ayrıntı panelinin Ekler sekmesi: tıklamak açar, sağ tık kaydeder ya da paylaşır.

### Okuma: cam iletinin üstünde de kalır
- alpha.2'de ileti açıkken çubuklar düz yüzeye dönüyordu. Artık cam kalır: çubuklar camken iletinin WebView'ı kendi donanım katmanına çizilir. İleti bir kez çizilir; pencere de çubukların bulanıklığı da hazır resmi o katmandan alır. (Titreme, camın altındaki içeriği her karede birkaç kez yeniden çizdirmesinden çıkıyordu.) **Cihazda doğrulanacak:** bu yol yalnızca testlerde denendi.
- Bir katmana sığmayacak kadar uzun iletide (cihazın en büyük dokusu ya da 64 MB; telefonda yaklaşık altı ekran boyu) katman kullanılmaz ve o ileti ekrandayken cam kendiliğinden kapanır.
- Ayarlar › Denemeler: "İletinin üstünde cam" (kapalıyken alpha.2'deki gibi: ileti açıkken düz çubuklar); "WebView katmanı" artık Otomatik, Yok, Donanım, Yazılım.
- Günlük, her iletide WebView'ın neye çizildiğini yazar (`WebView 1080 x 5400 px: hardware layer, glass blurred`): titreme ya da boş kalan bir bölüm olursa ilk bakılacak satır.

### Bu sürümde bilerek olmayanlar
- Çoklu seçim ve klasöre sürükle bırak (ileti işlemlerinin ikinci adımı).
- Yazmada: biçimli (HTML) yazma; taslakların sunucudaki Taslaklar klasörüne kaydı; yazarken alıcı önerisi ve rehberden alıcı seçme; `mailto:` bağlantısıyla ve paylaşılan dosyayla yeni ileti; DeX'te yazmanın ayrı pencerede açılması. Yazma ekranı şimdilik pencerenin içinde, başlatıldığı ekranın yerinde açılır; başka bir ekrana geçip geri dönünce yazılan yerinde durur.
- Arama, bildirim ve arka planda eşitleme, Outlook girişi.
- İletinin içine gömülü resimler (`cid:`) henüz görünmez.

### Altyapı
- Veritabanı şeması 3: bekleyen işlemler tablosu (`pending_op`, şema 2) ve giden kutusu (`outbox`, `outbox_file`, şema 3). 1 → 2 ve 2 → 3 göçleri testleriyle var: alpha.2'nin üstüne kurulunca hesaplar ve posta yerinde kalır.
- IMAP: bayrak yazma, MOVE (yoksa kopyala ve sil), yalnızca adı verilen iletileri silen UID EXPUNGE, APPEND; ek parçaları okundukça sunucudan akar.
- Gönderilen ileti uygulamanın kendi yazıcısıyla bayt bayt yazılır (başlıklar, quoted-printable metin, base64 ekler, Türkçe dosya adları) ve SMTP sunucusuna dosyadan akıtılır; posta kitaplığının Android'de eksik paketlenebilen içerik işleyicilerine dayanmaz. Başlığa satır sokuşturma (header injection) denemeleri testlidir.
- Yeni bağımlılık: WorkManager (`androidx.work:work-runtime` 2.12.0). Gönderimi ağ gelince ve uygulama kapalıyken de yapmak için; brifin istediği yol. Uygulama açılışında değil, ilk gerektiğinde kurulur.
- Yeni kısayollar: E, V, Delete, Ctrl+Z; R, A, F, Ctrl+Enter. Tek tuşlar ve Ctrl+Z yazı alanındayken alanın kendisine kalır; Ctrl+Enter yazı alanında da çalışır.

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
