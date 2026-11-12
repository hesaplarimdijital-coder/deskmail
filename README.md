# DeskMail

Kişisel kullanım için Android e-posta uygulaması: telefon, tablet ve Samsung DeX'te çalışan, yalnızca
e-posta yapan bir istemci. Arayüzün tamamı [desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit)
ile çizilir. Play'de değildir; APK olarak kurulur.

> **Durum:** 0.1.0'a giden ön sürümler. `0.1.0-alpha.1` iskelettir: kabuk, düzenler, okuma bölmesi,
> kısayollar ve günlük **uydurma postayla** çalışır. Henüz hesap eklenmiyor, posta alınmıyor ve
> gönderilmiyor. Sıradaki adımlar `docs/PLAN.md`'de, yapılanlar `CHANGELOG.md`'de.

## Kurulum ve güncelleme

Her sürümün imzalı APK'sı [Releases](https://github.com/hesaplarimdijital-coder/deskmail/releases)
sayfasındadır (`DeskMail-<sürüm>.apk`). Güncellemeleri izlemek için
[Obtainium](https://github.com/ImranR98/Obtainium):

1. Obtainium'da **Uygulama ekle** › kaynak adresi: `https://github.com/hesaplarimdijital-coder/deskmail`
2. **Ön sürümleri dahil et** açık olsun (0.1.0'a kadar bütün sürümler ön sürümdür).
3. Ekle, sonra kur. Yeni sürüm çıkınca Obtainium haber verir; güncelleme eskisinin üstüne kurulur.

Elle kurmak için APK'yı indirip açmak yeter. Bütün sürümler aynı anahtarla imzalıdır (aşağıda).

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
| `:core` | Saf Kotlin/JVM, Android'siz. Posta sunucularıyla konuşan ve yanıtlarına göre karar veren her şey burada durur ve JVM testleriyle sınanır. Bugün: günlük ve maskeleme, tarih yazımı, Türkçe harf katlama, klasör rolleri. |
| `:app` | Android uygulaması: arayüz (desktop-kit), ayarlar, günlük dosyası; ileride depolama ve arka plan işleri. |

| Belge | İçerik |
| --- | --- |
| `docs/PLAN.md` | Kararlar, mimari, veri modeli, senkron ve bildirim tasarımı, düzenler, kısayollar, PR'lar, riskler. |
| `docs/KIT-ISTEKLERI.md` | desktop-kit'te eksik kalan ve kite yazılacak parçalar; her biri için şimdilik izlenen yol. |
| `CHANGELOG.md` | Sürüm sürüm değişiklikler. |

Araçlar desktop-kit 2.4.0 ile aynıdır: Android Gradle eklentisi 9.4.1, Gradle 9.8.2, Kotlin 2.4.20;
`minSdk` 34, `compileSdk` 37, `targetSdk` 36. Testler Java 21 ister (Robolectric 4.17, API 36).

## Derleme ve testler

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

- Bağımlılıklar Google Maven, Maven Central ve (yalnızca desktop-kit için) JitPack'ten iner.
- `:core` testleri saf JVM'de, `:app` testleri Robolectric'te koşar. Ekran görüntüsü testleri
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
