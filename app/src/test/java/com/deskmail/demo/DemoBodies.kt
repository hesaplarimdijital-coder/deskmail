package com.deskmail.demo

/**
 * The HTML of the made-up messages, each chosen for what it does to a reader: fixed-width tables,
 * remote images and a tracking pixel, coloured backgrounds, quoted replies, wide preformatted
 * text, an image inside the message.
 */
internal object DemoBodies {

    /** A shop's newsletter the way they are built: nested 600 px tables, colours, remote images, a pixel. */
    val newsletter = """
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#F3EFE6">
          <tr><td align="center">
            <table width="600" cellpadding="0" cellspacing="0" style="background:#FFFFFF;font-family:Georgia,serif;color:#2B2B2B">
              <tr><td style="background:#7A2E2E;color:#FFFFFF;padding:20px 24px;font-size:22px">Sahaf Kitabevi · Kasım bülteni</td></tr>
              <tr><td><img src="https://img.sahaf.example/kasim/vitrin.jpg" width="600" height="220" alt="Kasım vitrini" style="display:block"></td></tr>
              <tr><td style="padding:20px 24px;font-size:16px;line-height:1.6">
                <p>Merhaba Deniz,</p>
                <p>Bu ay raflara 140 kitap girdi: ilk baskı şiir kitapları, 1950'lerden dergi ciltleri ve imzalı birkaç roman.</p>
                <table width="100%" cellpadding="8" cellspacing="0" style="border-collapse:collapse;font-size:15px">
                  <tr style="background:#F3EFE6"><th align="left">Kitap</th><th align="left">Baskı</th><th align="right">Fiyat</th></tr>
                  <tr><td style="border-bottom:1px solid #E2DCCD">Otuz Beş Yaş</td><td style="border-bottom:1px solid #E2DCCD">1946, ilk baskı</td><td align="right" style="border-bottom:1px solid #E2DCCD">2.400 TL</td></tr>
                  <tr><td style="border-bottom:1px solid #E2DCCD">Varlık dergisi, 1953 cildi</td><td style="border-bottom:1px solid #E2DCCD">12 sayı</td><td align="right" style="border-bottom:1px solid #E2DCCD">1.150 TL</td></tr>
                  <tr><td>Anayurt Oteli</td><td>1973, imzalı</td><td align="right">3.800 TL</td></tr>
                </table>
                <p style="margin-top:20px"><a href="https://sahaf.example/yeni?utm_source=bulten&amp;utm_campaign=kasim" style="background:#7A2E2E;color:#FFFFFF;padding:10px 18px;text-decoration:none;border-radius:4px">Yeni gelenlere bak</a></p>
                <p><b>İmza günü:</b> 21 Kasım Cumartesi, saat 15.00. Yer sınırlı, gelmeden haber ver.</p>
              </td></tr>
              <tr><td style="background:#F3EFE6;padding:16px 24px;font-size:12px;color:#6B6455">
                Bu bülteni sahaf.example üyesi olduğun için alıyorsun.
                <a href="https://sahaf.example/ayril?u=4821" style="color:#6B6455">Listeden çık</a>
                <img src="https://izle.sahaf.example/o.gif?u=4821&amp;k=kasim" width="1" height="1" alt="">
              </td></tr>
            </table>
          </td></tr>
        </table>
    """.trimIndent()

    /** A reply on top of two rounds of quoted text. */
    val replyChain = """
        <div style="font-family:Calibri,Arial,sans-serif;font-size:11pt">
          <p>Deniz selam,</p>
          <p>Planın üçüncü sürümü ekte. İki değişiklik var: senkron işi iki haftaya çıktı, bildirimler bir hafta öne geldi. Perşembe günü üstünden geçelim mi?</p>
          <p>Mehmet</p>
        </div>
        <hr>
        <div><b>Kimden:</b> Deniz Aksoy<br><b>Gönderildi:</b> 11 Kasım 2026 Çarşamba 18:42<br><b>Konu:</b> Re: Proje planı (v2)</div>
        <blockquote>
          <p>Senkron için bir hafta az. Klasör listesi, bayraklar ve gövdeler ayrı ayrı sınanmalı; en az iki hafta diyelim.</p>
          <blockquote>
            <p>İkinci sürümde senkrona bir hafta ayırdım, yeter mi? Bildirimleri en sona bıraktım.</p>
            <blockquote><p>İlk taslağı gönderiyorum, tarihler kabaca.</p></blockquote>
          </blockquote>
        </blockquote>
    """.trimIndent()

    /** A build server's report: preformatted lines wider than a phone. */
    val buildReport = """
        <p>feature/skeleton dalındaki son çalışma bitti.</p>
        <pre style="font-family:monospace;font-size:13px;background:#F6F8FA;padding:12px;border-radius:6px">İş                         Süre     Sonuç
:core:test                 0 dk 41 sn   başarılı   (10 test)
:app:testDebugUnitTest     2 dk 07 sn   başarılı   (4 test)
:app:assembleRelease       1 dk 12 sn   başarılı   (app-release.apk, 9,4 MB)</pre>
        <p>APK sabit anahtarla imzalandı.</p>
        <p><a href="https://ci.example/calisma/43244950457">Çalışmanın ayrıntıları</a></p>
    """.trimIndent()

    /** A utility's bill: a coloured header and a table of amounts. */
    val invoice = """
        <div style="font-family:Arial,sans-serif;max-width:560px">
          <div style="background:#0B5CAD;color:#FFFFFF;padding:16px 20px;font-size:18px">Kasım 2026 faturanız hazır</div>
          <div style="border:1px solid #D0D7DE;border-top:0;padding:16px 20px">
            <p>Sayın Deniz Aksoy,</p>
            <p>1002003004 numaralı tesisatınızın Kasım dönemi faturası ektedir.</p>
            <table width="100%" cellpadding="6" cellspacing="0" style="font-size:14px">
              <tr><td>Tüketim</td><td align="right">212 kWh</td></tr>
              <tr><td>Enerji bedeli</td><td align="right">548,20 TL</td></tr>
              <tr><td>Dağıtım bedeli</td><td align="right">196,40 TL</td></tr>
              <tr><td>Vergiler</td><td align="right">148,92 TL</td></tr>
              <tr style="font-weight:bold;background:#EAF2FB"><td>Ödenecek tutar</td><td align="right">893,52 TL</td></tr>
            </table>
            <p style="color:#B42318"><b>Son ödeme tarihi: 27 Kasım 2026</b></p>
          </div>
        </div>
    """.trimIndent()

    /** Meeting minutes with lists and a small chart drawn inside the message itself (no network needed). */
    val minutes = """
        <h3>10 Kasım toplantısı</h3>
        <p><b>Katılanlar:</b> Can, Deniz, Elif, Mehmet</p>
        <ol>
          <li>İskelet ve imza: <span style="color:#1A7F37">bitti</span></li>
          <li>Hesap ekleme: Outlook için kayıt bekleniyor</li>
          <li>Bildirimler: gece denemesi yapılacak</li>
        </ol>
        <p>Haftalık ilerleme:</p>
        <img alt="Haftalık ilerleme çizelgesi" width="280" height="120" src="data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 280 120'%3E%3Crect width='280' height='120' fill='%23F6F8FA'/%3E%3Crect x='24' y='70' width='40' height='34' fill='%238AB0FF'/%3E%3Crect x='84' y='52' width='40' height='52' fill='%238AB0FF'/%3E%3Crect x='144' y='38' width='40' height='66' fill='%238AB0FF'/%3E%3Crect x='204' y='18' width='40' height='86' fill='%232F6FEB'/%3E%3Cpath d='M16 104h248' stroke='%2357606A'/%3E%3C/svg%3E">
        <ul>
          <li>Deniz: cihaz denemeleri (telefon, tablet, DeX)</li>
          <li>Can: sağlayıcı tablosu</li>
        </ul>
    """.trimIndent()

    /** What lands in Spam: loud colours, a remote image, a link that is not what it says. */
    val junk = """
        <div style="background:#FFF200;color:#D40000;font-family:Impact,Arial,sans-serif;font-size:26px;text-align:center;padding:24px">
          TEBRİKLER! ÖDÜLÜNÜZ HAZIR
        </div>
        <p style="text-align:center"><img src="https://odul.example/kupa.png" width="200" height="200" alt="Ödül"></p>
        <p>Ödülünüzü almak için 24 saat içinde <a href="https://giris-dogrulama.example/odul?id=77131">www.bankaniz.example</a> adresinden bilgilerinizi doğrulayın.</p>
    """.trimIndent()

    /** A ticket: a card with a border and two columns. */
    val ticket = """
        <div style="font-family:Arial,sans-serif;border:2px dashed #8C959F;border-radius:10px;padding:16px;max-width:480px">
          <div style="font-size:12px;color:#57606A">ELEKTRONİK BİLET</div>
          <div style="font-size:22px;margin:6px 0"><b>İstanbul (Söğütlüçeşme) → Ankara</b></div>
          <table width="100%" cellpadding="4" cellspacing="0" style="font-size:14px">
            <tr><td>Tarih</td><td align="right"><b>28 Kasım 2026 Cumartesi</b></td></tr>
            <tr><td>Kalkış</td><td align="right"><b>08:15</b></td></tr>
            <tr><td>Vagon / koltuk</td><td align="right"><b>4 / 12A</b></td></tr>
            <tr><td>Yolcu</td><td align="right">Deniz Aksoy</td></tr>
          </table>
          <div style="font-size:12px;color:#57606A;margin-top:8px">Biletin PDF'i ektedir. İyi yolculuklar.</div>
        </div>
    """.trimIndent()
}
