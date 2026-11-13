# Gmail için yedek giriş yolu: Google OAuth

DeskMail Gmail'e **uygulama şifresiyle** girer (IMAP ve SMTP'de düz şifre girişi). Bu belge, Google bir gün
uygulama şifrelerini kapatırsa ya da Barış'ın hesabında bu seçenek kalmazsa ne yapılacağını anlatır.
Bugün yapılacak bir şey yok; belge, yılda bir bakılan şeylerden biridir (`docs/BAKIM.md`).

13 Kasım 2026'da Google'ın kendi belgelerine bakılarak yazıldı; Google Cloud konsolu burada denenmedi.

## Ne zaman gerekir

- Gmail sunucusu uygulama şifresini de reddetmeye başlarsa, ya da hesap sayfasında "Uygulama şifreleri"
  seçeneği kalmazsa (iş/okul hesaplarında, Gelişmiş Koruma'da ve yalnızca güvenlik anahtarıyla doğrulanan
  hesaplarda bugün de yok).
- Google uygulama şifrelerinin kalkacağını duyurursa. Takvim duyuruyla birlikte okunur; o güne kadar bir
  şey değiştirilmez.

## Google'ın bugünkü kuralları (önemli olanlar)

- **Kapsam:** IMAP ve SMTP için `https://mail.google.com/`. Google bunu "kısıtlı" (restricted) kapsam
  sayar: herkese açık bir uygulama için doğrulama ve güvenlik incelemesi ister.
- **Kişisel kullanım istisnası:** uygulamayı yalnızca geliştiricisi ve 100'den az kişi kullanıyorsa
  doğrulama gerekmez; kullanıcı girişte "Google bu uygulamayı doğrulamadı" ekranını görür ve geçebilir.
  DeskMail tek kişinin uygulaması: bu istisnaya girer.
- **Yayın durumu:** "Testing" durumundaki bir uygulamanın verdiği yenileme jetonu **7 günde** düşer
  (her hafta yeniden giriş). Bu yüzden onay ekranı **"In production"** durumuna alınır ama doğrulamaya
  gönderilmez: uyarı ekranı ve 100 kişi sınırı kalır, 7 günlük sınır kalkar.
- **Android'de yönlendirme:** Google'ın Android istemci türünde özel şema (`com.deskmail:/…`) artık
  desteklenmiyor; geri dönüş adresi olarak yerel adres (loopback, `http://127.0.0.1:port`) Android, iOS ve
  Chrome istemci türlerinde kullanımdan kalktı. Microsoft'ta kurulan yol (Custom Tab + özel şema + PKCE,
  `docs/PLAN.md` › Azure kaydı) Google'da aynen kurulamaz.
- **PKCE** destekleniyor (S256); Android istemcisinde istemci sırrı yok.
- Jeton adresi `https://oauth2.googleapis.com/token`. IMAP `imap.gmail.com:993`, SMTP
  `smtp.gmail.com:465`; giriş SASL XOAUTH2 (Outlook için yazılacak kodla aynı).

## Seçenekler

### A. Google Identity Services (önerilen)

Google'ın Android için gösterdiği yol: Play hizmetlerinin `AuthorizationClient`'ı
(`com.google.android.gms:play-services-auth`). Uygulama kapsamı ister, Play hizmetleri hesabı seçtirir,
onayı alır ve bir **erişim jetonu** verir; jeton süresi dolunca aynı çağrı sessizce yenisini verir
(yenileme jetonunu uygulama hiç görmez).

- Google Cloud'da bir proje, "External" onay ekranı ("In production", doğrulanmamış), **Android** türünde
  bir OAuth istemcisi: paket `com.deskmail`, imza sertifikasının SHA-1'i (`docs/PLAN.md` › Azure
  kaydı'ndaki özetin aynısı; gizli değil). İstemci kimliği gizli değildir, depoda durabilir.
- Artısı: Google'ın desteklediği tek yol; jeton saklamak ve yenilemek uygulamanın işi olmaktan çıkar.
- Eksisi: kapalı kaynaklı bir bağımlılık ve cihazda Google Play hizmetleri şartı. "Play hizmetlerine
  bağımlılık yok" kararından ayrılmak demek; bu yüzden yalnızca Gmail girişini taşıyan küçük bir modülde
  durur (`:google`), uygulamanın geri kalanı ona bağlanmaz. Play hizmetleri olmayan cihazda Gmail
  girişi uygulama şifresiyle kalır.

### B. Masaüstü istemcisi ve yerel adres

"Desktop app" türünde bir istemci; tarayıcı Custom Tab'da açılır, uygulama `127.0.0.1` üzerinde kısa
süreliğine bir bağlantı noktası dinler ve yönlendirmeyi oradan alır. Bağımlılık eklemez ve Outlook'un
PKCE koduyla aynı yapıdadır.

- Eksisi: masaüstü istemcisi bir Android uygulamasında kullanılmış olur; Google'ın kurallarının
  amacına aykırı, her an engellenebilir. Yalnızca A olmazsa ve kişisel kullanımla sınırlı denenir.

### C. Bir şey yapmamak

Uygulama şifresi çalıştığı sürece en az bakım isteyen yol bu. Google bir takvim açıklayana kadar geçerli.

## Yapılacaklar (A seçilirse)

1. Barış Google Cloud'da projeyi, onay ekranını ve Android istemcisini kurar (kart istenirse durur ve
   haber verir).
2. `:google` modülü: `AuthorizationClient` ile kapsam isteme, erişim jetonunu alma; hata durumları
   (iptal, Play hizmetleri yok, onay geri alındı) hesabın "Yeniden bağlan" durumuna eşlenir.
3. `:core`'da XOAUTH2 girişi (Outlook ile ortak); hesap ekleme ekranında Gmail için "Google ile giriş"
   ve "Uygulama şifresi" seçenekleri yan yana.
4. Gerçek hesapla deneme Barış'ın cihazında.
