# Yıllık bakım

DeskMail yılda bir güncellemeyle yaşayacak biçimde yapıldı (README › Hedef). Bu belge o güncellemede
sırayla bakılacakları toplar. Her madde ya "değişmedi" diye geçilir ya da kendi PR'ına dönüşür.

## 1. Önce bakılacaklar

- **İmza anahtarı.** `SIGNING_KEYSTORE_BASE64` ve `SIGNING_KEYSTORE_PASSWORD` GitHub'da duruyor mu,
  parola yöneticisindeki yedek açılıyor mu? CI her derlemede sertifikayı denetler (README › İmza);
  anahtar kaybolursa güncelleme yapılamaz, uygulama kaldırılıp yeniden kurulur.
- **Günlük.** Barış'ın cihazında Ayarlar › Günlük: yıl boyunca biriken uyarılar ve hatalar (özellikle
  "watch" satırları, sunucu yanıtları). Burada görülen her tekrar eden hata bir maddedir.
- **Gmail'de uygulama şifresi** hâlâ çalışıyor mu? Google bir takvim açıkladıysa `docs/GOOGLE-OAUTH.md`.
- **Outlook (Azure kaydı).** Uygulama kaydı duruyor mu (Microsoft etkinliği olmayan dizinleri
  kapatabiliyor), giriş çalışıyor mu? Kayıt gittiyse `docs/PLAN.md` › Azure kaydı'ndaki adımlarla yenisi
  kurulur; yeni istemci kimliği depoya yazılır.

## 2. Araçlar ve kitaplıklar

Sürümlerin hepsi `gradle/libs.versions.toml`'dadır. Sıra önemlidir: önce kit, sonra ona uyanlar.

1. **desktop-kit.** Yeni etiketi varsa önce o: kit, Compose BOM'u, Navigation'ı ve Haze'i kendi sürümleriyle
   getirir; AGP, Gradle ve Kotlin sürümleri de kitinkilerle aynı tutulur. Kitin `MIGRATION.md`'si okunur.
   `docs/KIT-ISTEKLERI.md`'de kitin karşıladığı maddeler uygulamaya bağlanır, "Şimdilik" yolları kalkar.
2. **AGP, Gradle, Kotlin, KSP** (kitle birlikte). KSP sürümü Kotlin'inkine uymalı.
3. **Room** ve **androidx.sqlite**: şema dosyaları (`app/schemas`) değişmemeli; değişirse yeni sürüm
   numarası, göç ve göç testi.
4. **Angus Mail** (ve getirdiği Jakarta Mail/Activation): sürüm notlarında güvenlik düzeltmeleri. Yükseltince
   `SocketFetcher.configureSSLSocket` yeniden okunur (sunucu kimliği denetimini uygulama veriyor:
   `ImapConnection.properties`, `SmtpSender`); kitaplığın jar içindeki kaynak dosyalarına dayanan bir şey eklenmiş mi
   (`MailLibrary`, CI'ın `apk-resources.txt` çıktısı).
5. **jsoup**: HTML temizleme güvenliğin parçasıdır; sürüm notlarında `Safelist` ve ayrıştırıcı değişiklikleri.
6. **WorkManager, activity-compose, core, coroutines.**
7. **Test kitaplıkları** (Robolectric, GreenMail, JUnit, androidx.test): Robolectric'in yeni API
   düzeyini desteklemesi `targetSdk` yükseltmesinin önkoşuludur.
8. **Lisanslar.** Yeni bir kitaplık eklendiyse ya da birinin lisansı değiştiyse Ayarlar › Hakkında › Açık
   kaynak lisansları'ndaki liste (`ui/settings/Licences.kt`) ve metinler (`res/raw`) güncellenir.

## 3. Android

- **`targetSdk`** bir üst düzeye çıkarılır; o sürümün "davranış değişiklikleri" sayfası okunur. Bugüne
  kadar dokunanlar: ön plan hizmeti türleri (`specialUse`), kesin alarmlar, bildirim izni, paylaşımda
  URI izinleri (Android 18'de otomatik izin kalkıyor), uygulama bellek sınırı (Android 17).
- **`minSdk`** yükselince eskiyen yollar temizlenir: `DnsResolver.getInstance()` yerine
  `DnsResolver(Context, Looper)` (API 37), Android 17 öncesi kişi seçicisi (`ACTION_PICK`).
- **Samsung.** DeX ve "Hiç uyumayan uygulamalar" ayarının yeri değişmiş mi (README ve CHANGELOG'da yolu yazılı).

## 4. Sağlayıcılar

- `core`'daki sağlayıcı tablosu: Gmail ve Outlook sunucu adları, bağlantı noktaları, klasör adları,
  `savesSentItself`. Microsoft 365 (iş/okul) hesapları denenmedi.
- ISPDB adresi ve DNS yolu (MX, SRV) çalışıyor mu: Ayarlar'dan bilinmeyen bir alan adıyla hesap eklemeye
  başlamak yeter.

## 5. Bitirirken

- CI yeşil; ekran görüntüleri gözle bakıldı.
- `versionName` ve `versionCode`, `CHANGELOG.md`'de sürümün bölümü, README'nin "Durum" paragrafı.
- Sürüm README › Sürüm çıkarma'daki gibi açılır; Obtainium'da güncelleme göründü mü bakılır.

## Bilinen ayrıntılar

`docs/PLAN.md`'nin "Bakım notları" başlığından taşındı.

- **`DnsResolver.getInstance()`** Android 17'de (API 37) eskidi; yerine gelen `DnsResolver(Context, Looper)`
  yalnızca API 37'den beri var. `minSdk` 37 olunca değiştirilir (`net/DnsLookup.kt`). MX ve SRV sorguları
  `rawQuery`'ye tür numarasıyla (15, 33) verilir; platform yalnızca A, AAAA ve HTTPS için sabit tanımlar.
- **Angus Mail yükseltilirken** `SocketFetcher.configureSSLSocket` yeniden okunmalı: sunucu kimliği
  denetimini uygulama `mail.imap.ssl.hostnameverifier` ve `mail.smtp.ssl.hostnameverifier` ile kendisi
  veriyor ve kitaplığınkini kapatıyor (`ImapConnection.properties`, `SmtpSender`).
- **Compose test kuralı:** `createAndroidComposeRule` (v1) eskidi; v2'ye geçiş testlerde açık eşzamanlama
  isteyebilir. Kit yükseltilirken birlikte yapılır.
- **GreenMail** bir parçanın boyutuna parçanın kendi başlık satırlarını da katıyor (gerçek sunucular
  katmaz); ek boyutu kestirimi bu yüzden ayrı bir testte gerçek sayılarla sınanıyor.
- **Veritabanı şeması** (`app/schemas`): CI, derlemenin yazdığı şema depodakinden farklıysa durur. Şema
  değişikliği yeni sürüm numarası, göç ve göç testi demektir.
- **Microsoft 365 (iş/okul) hesapları:** posta sunucusu Microsoft olan alan adları Outlook satırına
  eşleniyor; kişisel hesaplardan farkları (SMTP sunucusu, kiracı) denenmedi.
