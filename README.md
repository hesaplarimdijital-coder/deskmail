# DeskMail

Kişisel kullanım için Android e-posta uygulaması: telefon, tablet ve Samsung DeX'te çalışan, yalnızca
e-posta yapan bir istemci. Arayüzün tamamı [desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit)
ile çizilir. Play'de değildir; APK olarak kurulur.

> **Durum:** 0.1.0'a giden ön sürümler; şimdiki `0.1.0-beta.2`. Hesap eklenir (Gmail uygulama şifresiyle;
> başka sağlayıcılar şifreyle), klasörler ve iletiler eşitlenir, iletiler güvenli biçimde ve konuşmalarıyla
> gösterilir; okundu ve yıldız sunucuya yazılır, iletiler arşivlenir, taşınır, silinir, ertelenir (hepsi geri
> alınır; birden çok ileti seçilip birlikte de), ekler açılır ve kaydedilir; ileti yazılır, yanıtlanır,
> iletilir ve gönderilir (gönderi geri alınabilir, çevrimdışıyken sıraya girer; taslaklar sunucunun Taslaklar
> klasöründe de durur; yanıtı beklenip gelmezse hatırlatılır); cihazda ve sunucuda aranır, aramalar klasör
> gibi saklanır; gelen posta geldiği anda bildirilir (sunucuya açık bir hat tutulur; istenirse 15, 30 ya da
> 60 dakikada bir bakılır) ve bildirimden yanıtlanır; ayarlar başka bir cihaza taşınır. Outlook hesapları
> Microsoft girişini bekliyor (sıradaki sürüm). Sıradaki adımlar `docs/PLAN.md`'de, yapılanlar
> `CHANGELOG.md`'de.

## Kurulum ve güncelleme

Her sürümün imzalı APK'sı [Releases](https://github.com/hesaplarimdijital-coder/deskmail/releases)
sayfasındadır (`DeskMail-<sürüm>.apk`).

**Elle:** telefonda ya da tablette sürüm sayfasını aç, APK'ya dokunup indir, indirileni aç. Android "bu
kaynaktan yüklemeye izin ver" diye sorarsa tarayıcıya izin ver. Yeni sürüm eskisinin üstüne kurulur.

**Obtainium ile (isteğe bağlı):** [Obtainium](https://github.com/ImranR98/Obtainium), uygulamaları Play
Store yerine doğrudan sürüm sayfasından kuran ve yeni sürüm çıkınca haber veren açık kaynaklı bir Android
uygulamasıdır; APK'yı her seferinde elle indirmeyi gereksiz kılar.

1. Obtainium'u kendi Releases sayfasından kur.
2. **Add App** › adres: `https://github.com/hesaplarimdijital-coder/deskmail`
3. **Include Pre-releases** açık olsun (0.1.0'a kadar bütün sürümler ön sürümdür), sonra **Add** ve **Install**.

Bütün sürümler aynı anahtarla imzalıdır (aşağıda); hangi yolla kurulursa kurulsun güncelleme eskisinin
üstüne gelir, hesaplar ve ayarlar kalır.

## Hesap ekleme

Ayarlar › Hesaplar › **Hesap ekle**. Adresi yazınca uygulama sunucuları kendisi bulur; bulamazsa elle girilir.

- **Gmail:** hesap şifresi kabul edilmez, **uygulama şifresi** gerekir. Google hesabında iki adımlı
  doğrulama açıkken <https://myaccount.google.com/apppasswords> adresinde üretilir (16 harf); ekrandaki
  düğme o sayfayı açar. Gmail'de IMAP her zaman açıktır, ayrıca bir ayar gerekmez. Uygulama şifresi
  seçeneği iş ve okul hesaplarında, Gelişmiş Koruma'da ve yalnızca güvenlik anahtarıyla doğrulanan
  hesaplarda görünmeyebilir. Google bir gün uygulama şifrelerini kapatırsa yedek yol Google OAuth'tur:
  `docs/GOOGLE-OAUTH.md`.
- **Outlook, Hotmail, Live:** Microsoft'ta tarayıcıdan giriş ister (OAuth2); henüz eklenemez, sıradaki sürümle gelir.
- **Diğerleri:** sağlayıcının verdiği şifre ya da uygulama şifresi. Sunucular Thunderbird'ün sağlayıcı
  veritabanından ya da alan adının DNS kayıtlarından bulunur; dışarıya yalnızca adresin @ işaretinden
  sonraki bölümü gider.

Şifre, cihazın anahtar deposunda üretilen ve oradan çıkmayan bir anahtarla şifrelenerek saklanır;
günlüğe ve yedeğe girmez. Hesap kaldırılınca cihazdaki kopya silinir, posta sunucuda kalır.

## Hedef: yılda bir güncelleme

Her karar uygulamanın yılda bir güncellemeyle yaşayabilmesine göre verilir:

- az bağımlılık, hepsinin sürümü sabit (`gradle/libs.versions.toml`);
- yalnızca standart protokoller: IMAP ve SMTP;
- kendi sunucusu yok, Google Play hizmetlerine bağımlılık yok, analitik ve çökme raporlama yok;
- sağlayıcı farkları tek bir yerde;
- bir şey bozulunca ne olduğunu gösteren, uygulama içinden paylaşılabilen bir günlük
  (Ayarlar › Günlük; şifreler ve jetonlar maskelenir);
- her güncelleme eskisinin üstüne kurulur; hesaplar ve ayarlar kaybolmaz.

## Yapı

| Modül | İçerik |
| --- | --- |
| `:core` | Saf Kotlin/JVM, Android'siz. Posta sunucularıyla konuşan ve yanıtlarına göre karar veren her şey burada durur ve JVM testleriyle sınanır: IMAP oturumu ve SMTP gönderimi (Angus Mail'in arkasında), MIME parçaları, gönderilen iletinin yazılması, yanıt ve iletme kuralları, HTML temizleme, sağlayıcı tablosu ve sunucu bulma (ISPDB, MX, SRV), eşitleme kararları, günlük ve maskeleme, Türkçe harf katlama. |
| `:app` | Android uygulaması: veritabanı (Room; hesaplar, klasörler, iletiler, arama dizini, bekleyen işlemler, giden kutusu), kimlik kasası (Keystore), eşitlemeyi ve gönderimi yürüten katman (gönderim WorkManager ile), arayüz (desktop-kit), ayarlar, günlük dosyası. |

| Belge | İçerik |
| --- | --- |
| `docs/PLAN.md` | Kararlar, mimari, veri modeli, senkron ve bildirim tasarımı, düzenler, kısayollar, PR'lar, riskler. |
| `docs/KIT-ISTEKLERI.md` | desktop-kit'ten istenen parçalar: kitin getirdikleri ve DeskMail'in onlarla yaptığı, açık olanlar için şimdilik izlenen yol. |
| `CHANGELOG.md` | Sürüm sürüm değişiklikler. |

Araçlar desktop-kit 2.8.0 ile aynıdır: Android Gradle eklentisi 9.4.1, Gradle 9.8.2, Kotlin 2.4.20;
`minSdk` 34, `compileSdk` 37, `targetSdk` 36. Testler Java 21 ister (Robolectric 4.17, API 36).

## Derleme ve testler

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

- Bağımlılıklar Google Maven, Maven Central ve (yalnızca desktop-kit için) JitPack'ten iner.
- `:core` testleri saf JVM'de, `:app` testleri Robolectric'te koşar. IMAP ve eşitleme, test içinde
  çalışan bir posta sunucusuna (GreenMail) karşı sınanır; `:app` aynı sunucuyla gerçek veritabanına
  uçtan uca eşitler. Arayüz testleri ve ekran görüntüleri uydurma postayla çalışır. Ekran görüntüsü testleri
  uygulamayı telefon, tablet ve DeX boyutlarında, açık, koyu ve OLED temada çizer
  (`app/build/outputs/screenshots`; başvuru görüntüleri `app/src/test/screenshots`). Robolectric WebView
  çizmediği için bu görüntülerde iletinin gövdesi düz metnidir.
- CI (`.github/workflows/ci.yml`) her gönderimde derler, testleri çalıştırır ve kurulabilir APK'yı
  çıktı olarak yükler. Çekme isteklerinde derleme günlüğü ve test sonuçları `ci/logs-pr-<numara>`,
  ekran görüntüleri `ci/screenshots-pr-<numara>` dalına konur.

## Sürüm çıkarma

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; `versionCode` her sürümde bir artar.

Bir sürüm şöyle çıkar:

1. Sürümü taşıyan PR `main`'e birleşir (`app/build.gradle.kts`'teki `versionName` ve `CHANGELOG.md`'deki
   bölüm o PR'ın son commit'idir).
2. GitHub'da **Releases › Draft a new release**: etiket `versionName` ile aynı ("v" yok), hedef `main`,
   başka hiçbir şey yazmadan **Publish release**. Hazır bağlantı:
   `https://github.com/hesaplarimdijital-coder/deskmail/releases/new?tag=<sürüm>&target=main&title=<sürüm>`
3. `.github/workflows/release.yml` uygulamayı o etiketten sabit anahtarla derler, APK'nın imzasını
   denetler, `DeskMail-<sürüm>.apk` dosyasını sürüme ekler, sürümün metnini `CHANGELOG.md`'deki bölümden
   doldurur ve adında "-" olan sürümü ön sürüm olarak işaretler. Birkaç dakika sürer.

Etiketi ve sürümü bir insan açar: Claude'un çalıştığı oturum PR birleştirebilir ama etiket ve sürüm
açamaz. Etiket `main` üzerinde değilse ya da `versionName` ile uyuşmuyorsa iş akışı durur ve nedenini
söyler. `CHANGELOG.md`'de her madde tek satırdır: sürüm notlarında satır sonları olduğu gibi görünür.

## İmza

Kurulabilir her APK aynı sabit anahtarla imzalanır; yoksa yeni sürüm eskisinin üstüne kurulamaz.
Anahtar depoda durmaz: CI onu `SIGNING_KEYSTORE_BASE64` ve `SIGNING_KEYSTORE_PASSWORD` gizli
değişkenlerinden okur ve her derlemede APK'nın şu sertifikayla imzalandığını denetler:

```
SHA-256 CE:7A:D2:78:8A:97:DF:E2:C3:3A:CC:7A:C0:DA:93:4D:A3:5D:AC:22:50:4B:3D:8E:13:CA:97:92:52:48:2D:BA
```

Gizli değişkenler yoksa derleme makinenin geçici debug anahtarıyla imzalar; böyle bir APK sabit anahtarla
imzalanmış bir kurulumun üstüne kurulamaz. **Anahtar kaybolursa güncelleme yapılamaz:** uygulamayı kaldırıp
yeniden kurmak gerekir, hesaplar ve önbellek gider. Anahtarın ve şifresinin bir yedeği GitHub dışında da
(parola yöneticisinde) durmalıdır.
