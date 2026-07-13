# Antigravity Görev Talimatı — RP6 Hardware Control App

**Bu dosyayı Antigravity'ye ilk mesaj olarak ver, birlikte `RP6_Complete_Guide.md`
dosyasını da proje klasörüne koy (aynı klasörde iki dosya olsun).**

---

## Görev

Retroid Pocket 6 (LineageOS 23.2, Android 16 tabanlı) için, root erişimi
kullanarak fan hızı, joystick RGB LED, CPU performans modu, ekran yenileme
hızı ve pil takibi kontrol eden bir Android uygulaması inşa et.

**Önce şunu oku:** Aynı klasördeki `RP6_Complete_Guide.md` dosyası, cihaz
üzerinde bizzat test edilerek doğrulanmış tüm donanım kontrol noktalarını
(sysfs yolları, gerçek değerler, yön/harita bilgileri, ortam kısıtlamaları)
ve 25 bölümlük detaylı implementation plan'ı içeriyor. **Kod yazmaya
başlamadan önce o dosyayı tamamen oku ve orada yazan spesifikasyonlara
sadık kal — orada yazan sysfs yolları, izin modelleri ve "güvenli değil"
diye işaretlenmiş yaklaşımlar (örn. `user_space` governor'a geçiş) tahmin
değil, gerçek test sonucu.**

## Proje Türü ve Teknoloji

- Native Android uygulaması, **Kotlin**.
- Root erişimi için **libsu** kütüphanesi (`com.github.topjohnwu.libsu:core`).
- Min SDK 26+.
- Gradle/Android Studio proje yapısı standart olsun (`app/src/main/...`).
- Kalıcı `su` oturumu kullan (her komut için yeni process açma —
  `RP6_Complete_Guide.md` Bölüm 6.2'de bu net anlatılıyor, performans için
  kritik).

## Çalışma Sırası (Definition of Done ile)

Aşağıdaki fazları **sırayla** uygula, her fazı bitirdiğinde bir sonrakine
geçmeden önce kendi kendine "bu faz gerçekten tamamlandı mı" diye kontrol et
(derleniyor mu, mantık `RP6_Complete_Guide.md`'deki spesifikasyonla tutarlı mı):

1. **Proje iskeleti** — boş Kotlin projesi, libsu bağımlılığı, temel
   `Application` sınıfı (kalıcı shell oturumu açan).
2. **Fan kontrolü** — Rehber Bölüm 1 + Implementation Plan Bölüm 5-10:
   dinamik `pwm-fan` cooling device keşfi (index'i hardcode ETME), 300ms
   döngülü `FanControlService`, mod enum'ları, dithering (Bölüm 6.8),
   basit UI.
3. **Joystick RGB** — Rehber Bölüm 2 + Implementation Plan Bölüm 16:
   `JoystickRgbController`, iki farklı köşe haritası (sol/sağ ayrı, KARIŞTIRMA),
   `RotatingRainbowAnimator`, `brightness`'ı döngü dışında bir kere yazma
   optimizasyonu.
4. **Refresh Rate Tile** — Implementation Plan Bölüm 11.
5. **CPU Modları + Adaptive** — Implementation Plan Bölüm 12-13.
6. **Pil tahmini + birleşik bildirim** — Implementation Plan Bölüm 14,
   `SystemControlService` altında tüm döngüleri birleştir.
7. **Ayarlar enjeksiyonu (opsiyonel/riskli)** — Implementation Plan Bölüm 17.
   Rehber Bölüm 3'te bu mekanizmanın `SET_KEYBOARD_LAYOUT` gibi bazı
   izinlerin priv-app gerektirdiği, normal APK'da çalışmayabileceği
   belirtiliyor — bu fazı en sona bırak, çalışmazsa atla.
8. **Kalan özellikler** (overlay, per-game profil, titreşim, M1/M2, buton
   düzeni, deadzone) — Implementation Plan Bölüm 19-24, bu sırayla, her biri
   ayrı commit/adım olarak.

## Kritik Kısıtlamalar (Rehber'den — tekrar vurgulanıyor çünkü kolay atlanır)

- **Root şart, alternatif yok.** Cihazda bizzat test edildi: fan dosyası
  root olmadan salt-okunur, LED dosyaları root olmadan okunamıyor bile.
  Shizuku/adb-shell seviyesi de yetersiz. Kodun her yerinde root varsayımı
  güvenli.
- **`cur_state`'e tek seferlik yazma yeterli DEĞİL** — kernel governor'ı
  ~1 saniyede geri alıyor. Her fan özelliği mutlaka bir döngü (foreground
  service içinde) üzerinden çalışmalı.
- **Sol ve sağ joystick LED haritaları FARKLI** (noktasal simetrik) — aynı
  index'i iki tarafta da aynı köşeye yazma, Rehber Bölüm 2.2'deki iki ayrı
  tabloyu kullan.
- **Sysfs path'lerini hardcode etme** — `cooling_device34` gibi index
  numaraları kernel derlemesine göre kayabilir, kod her zaman `type` dosyası
  üzerinden (`pwm-fan` gibi) dinamik arama yapmalı.

## Doğrulama / Test Konusunda Dürüst Ol

Bu sysfs yolları ve fiziksel davranışlar **yalnızca gerçek RP6 donanımında**
anlamlıdır — bir Android emulator'de bu dosyalar yoktur, test edilemez.
Eğer geliştirme ortamında fiziksel cihaza (adb ile, USB debugging açık)
erişimin varsa, `adb shell` üzerinden ilgili sysfs yollarının varlığını ve
yazılabilirliğini terminal aracılığıyla doğrula. **Erişimin yoksa, kodu
yaz ve derlemenin başarılı olduğunu doğrula, ama "cihazda test edildi" gibi
bir iddiada bulunma — bunun yerine hangi kısımların gerçek cihazda manuel
doğrulama beklediğini açıkça bir NOT olarak belirt** (örn. bir
`VERIFICATION_NEEDED.md` dosyası oluşturup her fazın hangi adımının fiziksel
test gerektirdiğini listele).

## Çıktı Beklentisi

Her fazın sonunda:
- Derlenen, çalışan bir Android Studio projesi (mevcut fazlar dahil).
- Kısa bir özet: bu fazda ne yapıldı, hangi dosyalar eklendi/değişti,
  bir sonraki fazda nelere dikkat edilmeli.
- Eğer Rehber'deki bir spesifikasyondan (yol, değer, mantık) saptıysan,
  nedenini açıkça belirt — sessizce farklı bir varsayımla ilerleme.
