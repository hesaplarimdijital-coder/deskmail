# DeskMail

Kişisel kullanım için Android e-posta uygulaması: telefon, tablet ve Samsung DeX'te çalışan, yalnızca
e-posta yapan bir istemci. Arayüzün tamamı [desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit)
ile çizilir. Play'de değildir; APK olarak kurulur.

> **Durum:** geliştirme aşamasında (0.1.0'a giden ön sürümler). Henüz posta almıyor ve göndermiyor.

## Hedef: yılda bir güncelleme

Her karar uygulamanın yılda bir güncellemeyle yaşayabilmesine göre verilir:

- az bağımlılık, hepsinin sürümü sabit (`gradle/libs.versions.toml`);
- yalnızca standart protokoller: IMAP ve SMTP;
- kendi sunucusu yok, Google Play hizmetlerine bağımlılık yok, analitik ve çökme raporlama yok;
- sağlayıcı farkları tek bir yerde;
- bir şey bozulunca ne olduğunu gösteren, uygulama içinden paylaşılabilen bir günlük;
- her güncelleme eskisinin üstüne kurulur; hesaplar ve ayarlar kaybolmaz.

## Yapı

| Modül | İçerik |
| --- | --- |
| `:core` | Saf Kotlin/JVM, Android'siz. Posta sunucularıyla konuşan ve yanıtlarına göre karar veren her şey burada durur ve JVM testleriyle sınanır. |
| `:app` | Android uygulaması: arayüz (desktop-kit), depolama, arka plan işleri. |

Araçlar desktop-kit 2.4.0 ile aynıdır: Android Gradle eklentisi 9.4.1, Gradle 9.8.2, Kotlin 2.4.20;
`minSdk` 34, `compileSdk` 37, `targetSdk` 36. Testler Java 21 ister.

## Derleme

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

- Bağımlılıklar Google Maven, Maven Central ve (yalnızca desktop-kit için) JitPack'ten iner.
- CI her gönderimde derler, testleri çalıştırır ve kurulabilir APK'yı çıktı olarak yükler. Çekme
  isteklerinde derleme günlüğü ve test sonuçları `ci/logs-pr-<numara>`, ekran görüntüleri
  `ci/screenshots-pr-<numara>` dalına konur.

## İmza

Kurulabilir her APK aynı sabit anahtarla imzalanır; yoksa yeni sürüm eskisinin üstüne kurulamaz.
Anahtar depoda durmaz: CI onu `SIGNING_KEYSTORE_BASE64` ve `SIGNING_KEYSTORE_PASSWORD` gizli
değişkenlerinden okur ve her derlemede APK'nın şu sertifikayla imzalandığını denetler:

```
SHA-256 CE:7A:D2:78:8A:97:DF:E2:C3:3A:CC:7A:C0:DA:93:4D:A3:5D:AC:22:50:4B:3D:8E:13:CA:97:92:52:48:2D:BA
```

Gizli değişkenler yoksa derleme makinenin geçici debug anahtarıyla imzalar; böyle bir APK sabit anahtarla
imzalanmış bir kurulumun üstüne kurulamaz. **Anahtar kaybolursa güncelleme yapılamaz:** uygulamayı kaldırıp
yeniden kurmak gerekir, hesaplar ve önbellek gider.
