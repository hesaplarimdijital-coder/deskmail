# Değişiklikler

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; 0.1.0'a kadar olanlar ön sürümdür
(`0.1.0-alpha.N`). Her sürümün imzalı APK'sı GitHub'daki sürüm sayfasına eklenir.

## 0.1.0-alpha.1 (yayımlanmadı)

İlk ön sürüm: iskelet ve cihazda erken denenecekler. Posta henüz yok.

### Proje
- Gradle iskeleti: `:core` (saf Kotlin/JVM) ve `:app` (Android, `com.deskmail`); araçlar desktop-kit
  2.4.0 ile aynı, bütün sürümler sabit.
- CI: iki modülün testleri, kurulabilir APK, çekme isteklerinde günlükler `ci/logs-pr-<numara>` ve ekran
  görüntüleri `ci/screenshots-pr-<numara>` dalında.
- İmza: kurulabilir her APK sabit anahtarla imzalanır; CI anahtarın ve APK'nın parmak izini denetler.
- Etiket açılınca imzalı `DeskMail-<sürüm>.apk` dosyasını sürüme ekleyen iş akışı.
- Belgeler: plan (`docs/PLAN.md`) ve desktop-kit'ten istenenler (`docs/KIT-ISTEKLERI.md`).
