# RP6 LineageOS Fan Control + Refresh Rate + CPU Modes + Battery + RGB + Settings Injection — Implementation Plan

## 0. Kapsam Güncellemesi

Bu plan artık yedi özelliği kapsıyor:
1. **Fan kontrolü** (Bölüm 1-10) — `cooling_device` üzerinden.
2. **Ekran yenileme hızı (60/90/120Hz) Quick Settings Tile'ı** (Bölüm 11).
3. **CPU Performans Modları — Power Saving / Balanced / Ultra + Adaptif (FPS'e göre otomatik) mod** (Bölüm 12-13).
4. **Pil ömrü tahmini — Quick Settings ve/veya kalıcı bildirimde** (Bölüm 14).
5. **Joystick RGB kontrolü + döner gökkuşağı animasyonu** (Bölüm 16).
6. **LineageOS Ayarlar uygulamasına gerçek menü enjeksiyonu** (Bölüm 17) —
   resmi AOSP "dynamic setting injection" mekanizmasıyla, Settings kaynak
   kodu değiştirilmeden/yeniden derlenmeden.
7. **Oyun içi overlay** (Bölüm 19) — FPS/sıcaklık/pil/fan durumu + hızlı
   kontrol paneli.
8. **Oyun bazlı otomatik profil** (Bölüm 20) — ön plandaki uygulamaya göre
   CPU/Fan modunun otomatik değişmesi.
9. **Titreşim (rumble) yoğunluğu ayarı** (Bölüm 21).
10. **M1/M2 makro butonlarını düz input'a çevirme** (Bölüm 22).
11. **Xbox / Nintendo (Retroid) buton düzeni değiştirme** (Bölüm 23).
12. **Analog stick dead zone kalibrasyonunu tetikleme** (Bölüm 24) — sistemde
    zaten var olan kalibrasyon ekranını bizim arayüzümüzden açma.
12. **Analog stick deadzone kalibrasyonu** (Bölüm 24) — sistemde zaten var
    olan kalibrasyon aracını uygulamamızdan tetikleme.

Tüm bu döngüler (fan yazma, CPU governor ayarı, pil tahmini hesaplama) **tek
bir birleşik Foreground Service** (`SystemControlService`) içinde toplanacak
— hem pil açısından daha verimli (tek döngü, tek `su` oturumu) hem de tek bir
kalıcı bildirimde tüm bilgiyi (Fan modu / CPU modu / Pil süresi) gösterebilmek
için (bkz. Bölüm 14.3).


## 1. Özet

Retroid Pocket 6, LineageOS altında fanı kontrol edecek bir sistem uygulaması/servisi
sunmuyor. Ancak reverse-engineering ile şunlar doğrulandı:

- Fan, standart Linux thermal framework'üne `pwm-fan` adıyla bir **cooling device**
  olarak bağlı: `/sys/class/thermal/cooling_device34/`
- Kontrol dosyaları: `cur_state` (yazılabilir, 0–8 arası kademe) ve `max_state` (salt okunur, `8`)
- Kernel'in kendi termal governor'ı (`step_wise` policy, 5 farklı thermal zone
  — `cpu-1-9`, `cpu-1-10`, `gpuss-0`, `gpuss-1`, `usb-therm`) bu cooling device'ı
  sürekli otomatik güncelliyor; manuel yazılan değer ~1 saniye içinde eziliyor.
- Çözüm: hedef değeri **periyodik olarak (300 ms) yeniden yazan** bir arka plan
  döngüsü, kernel'in yazımını "ezerek" kalıcı manuel kontrol sağlıyor. Bu yöntem
  cihaz üstünde test edildi ve doğrulandı.
- Governor'ı tamamen kapatmak (`mode=disabled` / `policy=user_space`) **kasıtlı
  olarak tercih edilmedi** çünkü aynı cooling device CPU/GPU aşırı ısınma
  korumasına da bağlı; onu devre dışı bırakmak termal güvenliği riske atar.

Bu doküman, bu mekanizmayı kullanan bir Android uygulamasının nasıl
inşa edileceğini adım adım anlatır.

---

## 2. Hedefler ve Kapsam

**Yapılacak:**
- Kullanıcının Quiet / Smart(otomatik) / Sport / Max / Custom fan modu seçebildiği
  basit bir uygulama.
- Boot'ta otomatik başlayan, seçili modu sürekli uygulayan bir arka plan servisi.
- Hızlı erişim için bir Quick Settings Tile.
- Güvenlik: gerçek sıcaklık çok yükselirse kullanıcı "Quiet/Off" seçse bile
  fanın zorla düşük tutulmasını engelleyen bir taban çizgisi (watchdog).

**Yapılmayacak (bu sürümde):**
- Kernel/governor değişikliği (root gerektirmeyen bir çözüm değil, zaten root şart).
- RPSettings/GameAssistant'ın kendisinin taşınması (gereksizleşti).
- Sıcaklık eğrisi (curve) editörü gibi gelişmiş özellikler — v2'ye bırakılabilir.

---

## 3. Mimari

```
┌─────────────────────────────┐
│   MainActivity (UI)          │  Mod seçimi: Quiet/Smart/Sport/Max/Custom
└──────────────┬───────────────┘
               │ (SharedPreferences'e yazar, Servisi start/update eder)
               ▼
┌─────────────────────────────┐
│  FanControlService            │  Foreground Service (kalıcı bildirim ile)
│  - 300ms döngü (Handler/       │
│    Coroutine)                 │
│  - su ile cur_state'e yazar   │
│  - Sıcaklığı okur, watchdog    │
└──────────────┬───────────────┘
               │
               ▼
┌─────────────────────────────┐
│  RootShell (libsu)             │  Tek kalıcı su oturumu, tekrar tekrar
│                                 │  yeni su process açmaktan kaçınır
└──────────────┬───────────────┘
               │
               ▼
/sys/class/thermal/cooling_device34/cur_state   (yazılan hedef)
/sys/class/thermal/thermal_zone*/temp           (okunan sıcaklık, watchdog için)

┌─────────────────────────────┐
│  BootReceiver                  │  BOOT_COMPLETED → son seçili modu okur,
│                                 │  servisi başlatır
└─────────────────────────────┘

┌─────────────────────────────┐
│  FanQuickSettingsTile           │  Tile'a dokununca mod döngüsel değişir
│  (TileService)                  │  (Quiet→Smart→Sport→Max→Quiet...)
└─────────────────────────────┘
```

---

## 4. Gerekli İzinler ve Ön Koşullar

- Cihazda **root (Magisk)** kurulu olmalı — kullanıcının zaten yaptığı gibi.
- Uygulama `AndroidManifest.xml`'de:
  - `FOREGROUND_SERVICE` ve `FOREGROUND_SERVICE_SPECIAL_USE` (Android 14+ foreground
    service kısıtlamaları için; "system health"e en yakın tip kullanılmalı)
  - `RECEIVE_BOOT_COMPLETED`
  - `POST_NOTIFICATIONS` (Android 13+, foreground service bildirimi için)
- INTERNET izni **gerekmez** — tamamen yerel bir donanım kontrol uygulaması.
- `build.gradle`'a **libsu** (topjohnwu/libsu) bağımlılığı eklenmeli — ham
  `Runtime.exec("su")` yerine kalıcı, güvenli bir root shell yönetimi sağlar.

---

## 5. Sabitler (Reverse-engineering'den doğrulanan değerler)

```kotlin
object FanPaths {
    const val CUR_STATE = "/sys/class/thermal/cooling_device34/cur_state"
    const val MAX_STATE = "/sys/class/thermal/cooling_device34/max_state" // = 8 (salt okunur, boot'ta bir kere okunur)
}

enum class FanMode(val targetState: Int) {
    OFF(0),
    QUIET(2),
    SMART(-1),   // özel durum: kernel governor'ına bırak, döngü yazmayı DURDUR
    SPORT(5),
    MAX(8),
    CUSTOM(-2)   // kullanıcı slider'dan 0-8 arası seçer
}
```

> **Not:** `QUIET=2`, `SPORT=5` gibi ara değerler tahminidir — cihaz üzerinde
> her kademeyi tek tek dinleyip (0'dan 8'e kadar) gerçek RPM/ses farkını
> not ederek kalibre edilmeli (bkz. Adım 8.1).

**SMART modu özel bir davranış gerektirir:** bu modda döngü *hiçbir şey yazmaz*,
sadece bekler — böylece kernel'in kendi step_wise governor'ı devrede kalır ve
stock ROM'daki "Smart" moduna eşdeğer otomatik davranışı taklit eder.

---

## 6. Adım Adım Uygulama Planı

### 6.1 Proje iskeleti
- Android Studio'da boş bir "No Activity" proje aç, min SDK 26+ (LineageOS 23.2
  Android 16 tabanlı olduğu için pratikte sorun olmaz).
- `libsu` bağımlılığını ekle:
  ```gradle
  implementation("com.github.topjohnwu.libsu:core:5.2.2")
  ```

### 6.2 RootShell yardımcı sınıfı
- Uygulama başlangıcında (`Application.onCreate`) tek bir kalıcı `Shell` oturumu
  aç (libsu bunu otomatik yönetir, `Shell.getShell()`).
- Yazma işlemi için:
  ```kotlin
  Shell.cmd("echo $value > ${FanPaths.CUR_STATE}").exec()
  ```
- **Önemli:** her 300ms'de yeni bir `su` process'i açmak (fork/exec) pil ve
  performans açısından maliyetlidir. Bunun yerine libsu'nun **kalıcı shell**
  özelliğini kullan — tek bir `su` oturumu açık kalır, komutlar ona pipe edilir.

### 6.3 FanControlService (Foreground Service)
- `Service` olarak yaz, `onStartCommand`'da foreground'a geç (kalıcı bildirim:
  "Fan modu: Sport" gibi, dokunulunca MainActivity'ye gider).
- İçeride bir `CoroutineScope` + `while(isActive)` döngüsü:
  ```kotlin
  scope.launch(Dispatchers.IO) {
      while (isActive) {
          val mode = getCurrentMode() // SharedPreferences'tan oku
          if (mode != FanMode.SMART) {
              val target = resolveTarget(mode) // CUSTOM ise slider değeri
              writeCurState(target)
          }
          delay(300)
      }
  }
  ```
- SharedPreferences değişikliklerini `SharedPreferences.OnSharedPreferenceChangeListener`
  ile dinle, mod anında güncellensin (yeniden servis başlatmaya gerek kalmadan).

### 6.4 Watchdog (güvenlik katmanı)
- Aynı döngü içinde, her birkaç saniyede bir gerçek sıcaklığı oku:
  ```kotlin
  // en yüksek CPU/GPU thermal zone'unu izle, örn. cpuss-* / gpuss-*
  val tempMilliC = File("/sys/class/thermal/thermal_zone1/temp").readText().trim().toInt()
  val tempC = tempMilliC / 1000.0
  ```
- Eşik aş(örn. 75°C üzeri) ve kullanıcı OFF/QUIET seçmişse, **geçici olarak**
  daha yüksek bir `cur_state`'e zorla (örn. en az 5) ve bildirimde kullanıcıyı
  uyar ("Sıcaklık yüksek, fan geçici olarak hızlandırıldı"). Sıcaklık düşünce
  kullanıcının seçtiği moda geri dön.
- Bu katman, kullanıcının kendi cihazını yanlışlıkla aşırı ısıtmasını önler.

### 6.5 BootReceiver
- `RECEIVE_BOOT_COMPLETED` dinleyen bir `BroadcastReceiver`.
- Son kayıtlı modu SharedPreferences'tan okuyup `FanControlService`'i
  `ContextCompat.startForegroundService()` ile başlatır.

### 6.6 MainActivity (UI)
- Basit bir mod seçici: 5 buton/radio (Off/Quiet/Smart/Sport/Max) + Custom için
  bir slider (0–8, `max_state` boot'ta okunup dinamik ayarlanır, sabit 8 yazma).
- Anlık RPM/duty/sıcaklık göstergesi (opsiyonel, debug için faydalı):
  `speed` benzeri bir sysfs değeri varsa (RP6'da stock ROM'da `speed` RPM
  gösteriyordu — LineageOS'ta eşdeğeri olup olmadığı ayrıca kontrol edilmeli,
  bu görev kapsamı dışında bırakıldı).

### 6.7 Quick Settings Tile
- `TileService` alt sınıfı, `AndroidManifest.xml`'de `android.permission.BIND_QUICK_SETTINGS_TILE`
  ile tanımlanır.
- Her dokunuşta modu döngüsel olarak değiştirir (Quiet→Smart→Sport→Max→Quiet),
  tile ikonunu/alt yazısını günceller (`qsTile.subtitle`, `qsTile.updateTile()`).

### 6.8 Kesirli (Ondalıklı) Hedef Hız — Dithering Tekniği

**Sorun:** `cur_state` yalnızca tam sayı (0-8) kabul ediyor, kernel ondalıklı
bir değeri (`4.3` gibi) reddediyor. Ama "Custom" modda kullanıcıya 9 kaba
kademe yerine daha ince/akıcı bir kontrol hissi vermek istenirse (örn. bir
slider'da 0.0–8.0 arası serbestçe seçebilmek), **dithering (titreştirme)**
tekniğiyle bu simüle edilebilir — cihaz üzerinde test edilip doğrulandı.

**Mantık:** Hedef `4.3` gibi kesirli bir değerse, alt tam sayı (`4`) ve üst
tam sayı (`5`) arasında, zamanın `%(hedef - alt_tam_sayı)`'si kadarında üst
değeri, geri kalanında alt değeri yazarız. Örn. `4.3` için: adımların
**%30'unda `5`**, **%70'inde `4`** yazılır — kernel'in kendisi bunu ortalama
alacak şekilde algılamaz (her yazım hâlâ tam sayı), ama fanın fiziksel
ataleti (motorun anlık PWM değişimlerine karşı yavaş tepkisi) sayesinde
kullanıcı bunu "ara bir hız" gibi algılar. Bu teknik, ekran/LED parlaklığı
gibi başka PWM tabanlı donanımlarda da yaygın kullanılan bir yöntemdir.

**Kod:**

```kotlin
/**
 * cur_state'i kesirli bir hedefe (örn. 4.3) dithering ile yaklaştırır.
 * Ana fan döngüsü (6.3'teki 300ms'lik loop) her tick'te bu fonksiyonu
 * çağırıp döndürdüğü tam sayıyı cur_state'e yazmalı.
 */
class FractionalStateDither(private val target: Double) {
    private val low = kotlin.math.floor(target).toInt().coerceIn(0, 8)
    private val high = (low + 1).coerceAtMost(8)
    private val frac = (target - low).coerceIn(0.0, 1.0)
    private var accumulator = 0.0

    /** Her tick'te çağrılır, hangi tam sayının yazılacağını döner. */
    fun nextLevel(): Int {
        if (high == low) return low // hedef zaten 8.0 gibi tam bir sayıysa
        accumulator += frac
        return if (accumulator >= 1.0) {
            accumulator -= 1.0
            high
        } else {
            low
        }
    }
}

// Kullanım — FanControlService'in 300ms döngüsü içinde:
val dither = FractionalStateDither(target = 4.3)
// her tick:
val levelToWrite = dither.nextLevel()
writeCurState(levelToWrite)
```

**Log/debug çıktısı için** (geliştirme sırasında doğrulama amaçlı, cihaz
üzerinde aShellYou ile de test edildiği gibi):

```kotlin
Log.d("FanDither", "hedef=$target yazılan=$levelToWrite")
```

**Önemli sınırlamalar:**
- Bu, **gerçek bir ara donanım kademesi değil**, algısal bir yaklaşımdır —
  gerçek PWM duty'si hâlâ sadece 4 veya 5'in karşılığı arasında zıplıyor,
  sadece zamanlamayla ortalanıyor.
- Çok hızlı zıplama (örn. her 300ms'de bir 4↔5 arası) motor/fan üzerinde
  gereksiz mekanik strese yol açabilir — bu yüzden dither adım aralığı,
  ana kontrol döngüsünden (300ms) daha uzun tutulmalı (örn. 500ms-1s),
  fanın motor ataletiyle örtüşecek şekilde kalibre edilmeli.
- UI'da kullanıcıya bu kesirli değeri bir slider ile sunmak mantıklı, ama
  "Quiet/Smart/Sport/Max" gibi isimli hazır modların **tam sayı** hedeflere
  (Bölüm 5'teki `FanMode` enum'u gibi) bağlı kalması, kalibrasyonun
  basit ve öngörülebilir kalması açısından önerilir — dithering sadece
  "Custom" modda, kullanıcı bilerek ince ayar istediğinde devreye girmeli.

---

## 7. Manifest İskeleti (özet)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application ...>
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>

    <service android:name=".FanControlService"
        android:foregroundServiceType="specialUse"
        android:exported="false" />

    <receiver android:name=".BootReceiver" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
        </intent-filter>
    </receiver>

    <service android:name=".FanQuickSettingsTile"
        android:exported="true"
        android:icon="@drawable/ic_fan"
        android:label="Fan Modu"
        android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
        <intent-filter>
            <action android:name="android.service.quicksettings.action.QS_TILE" />
        </intent-filter>
    </service>
</application>
```

---

## 8. Test ve Kalibrasyon Planı

### 8.1 Kademe kalibrasyonu (uygulamadan önce, manuel)
`cur_state` 0'dan 8'e kadar her değeri tek tek yaz, her birinde ~10 saniye
bekleyip kulakla ses/hız farkını not al (ileride varsa gerçek RPM okuma
sysfs'i bulunursa onunla objektif ölç). Bu, `FanMode` enum'undaki
`QUIET/SPORT` gibi isimlerin hangi sayısal `cur_state`'e karşılık geleceğini
belirler.

### 8.2 Kalıcılık testi
Uygulama çalışırken `cat cooling_device34/cur_state`'i dışarıdan (aShellYou)
birkaç dakika izleyip döngünün gerçekten değeri sabit tuttuğunu doğrula.

### 8.3 Pil/performans testi
300ms döngünün pil tüketimine etkisini ölç; gerekirse aralığı 500ms–1s'e
çıkararak tüketim/tepki hızı dengesini ayarla.

### 8.4 Watchdog testi
Cihazı bilerek ısıt (ağır bir oyun/benchmark), OFF modundayken watchdog'un
gerçekten devreye girip fanı zorladığını doğrula.

### 8.5 Reboot testi
Cihazı yeniden başlat, servisin BootReceiver üzerinden otomatik ayağa
kalktığını ve son seçili modu uyguladığını doğrula.

---

## 9. Bilinen Riskler / Açık Sorular

- **Kernel güncellemeleri:** LineageOS güncellemesiyle `cooling_device34`
  numarası değişebilir (sıralama sabit değildir). Uygulama, sabit index yerine
  boot'ta `/sys/class/thermal/cooling_device*/type` içinde `pwm-fan` arayıp
  doğru path'i dinamik bulmalı — **bu, sabit path yerine mutlaka yapılmalı.**
- **Governor davranışı güncellemeyle değişebilir** — kalibrasyonun her büyük
  LineageOS güncellemesinden sonra tekrar doğrulanması önerilir.
- **Root gereksinimi** uygulamayı Play Store'a uygun hale getirmez; F-Droid
  tarzı sideload/GitHub Release dağıtımı düşünülmeli.
- **speed/RPM okuma** LineageOS tarafında henüz doğrulanmadı — varsa UI'da
  gösterilebilir, yoksa sadece `cur_state` hedefi gösterilir.

---

## 10. (Not: Bölüm 10'un eski "Yol Haritası" içeriği kaldırıldı — güncel ve
tam yol haritası artık Bölüm 15'te, tüm özellikleri kapsayacak şekilde.)

---

## 11. Ekran Yenileme Hızı (60 / 90 / 120 Hz) Quick Settings Tile'ı

### 11.1 Yaklaşım

RP6'nın ekranı muhtemelen 120Hz destekliyor ve LineageOS'un standart
"Display > Refresh rate" ayarları zaten Ayarlar uygulamasında bir yerde
mevcut olabilir (Settings > Display > Advanced > Smooth Display gibi).
Biz bunu tekrar yazmıyoruz — **aynı standart Android API'lerini** kendi
Quick Settings Tile'ımızdan çağırıyoruz, böylece kullanıcı Ayarlar'a
girmeden hızlıca değiştirebiliyor.

Android (ve LineageOS) bu ayarı iki `Settings.System` anahtarıyla tutar:

```
Settings.System.MIN_REFRESH_RATE   ("min_refresh_rate")
Settings.System.PEAK_REFRESH_RATE  ("peak_refresh_rate")
```

Sabit bir hız istiyorsak (örn. tam 60Hz kilitli), ikisini de aynı değere
yazarız. "En az X, sistem gerektiğinde daha da yükseltebilir" davranışı
istersek sadece `peak`'i yazıp `min`'i düşük bırakırız. Sabit/deterministik
davranış için basitlik adına **ikisini de eşit yazmayı** öneriyorum.

### 11.2 İzin Gereksinimi — WRITE_SECURE_SETTINGS

Bu ayarlar `Settings.System` içinde ama **secure** düzeyde korunuyor, yani
uygulamanın `android.permission.WRITE_SECURE_SETTINGS` iznine sahip olması
gerekiyor. Bu izin normal yoldan (`AndroidManifest` + kullanıcı onayı) verilemez;
tek seferlik olarak **adb ile** (ya da root ile `pm grant`) verilmesi gerekir:

```
adb shell pm grant <paket.adi> android.permission.WRITE_SECURE_SETTINGS
```

veya kullanıcının cihazında zaten root varsa, **uygulama ilk açılışta kendi
kendine** bu izni root ile verebilir (kullanıcıdan adb ile uğraşmasını
istemeye gerek kalmadan):

```kotlin
Shell.cmd("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS").exec()
```

Bu satırı `MainActivity.onCreate()` içinde, ilk açılışta (ve izin kontrolüyle
her açılışta) çalıştırmak, kullanıcının adb kurmasına hiç gerek bırakmaz —
zaten root olduğu için sorunsuz çalışır.

### 11.3 Kod — Refresh Rate Yardımcı Sınıfı

```kotlin
object RefreshRateController {

    private const val MIN_KEY = "min_refresh_rate"
    private const val PEAK_KEY = "peak_refresh_rate"

    enum class RefreshRate(val hz: Float) { HZ_60(60f), HZ_90(90f), HZ_120(120f) }

    fun set(context: Context, rate: RefreshRate) {
        Settings.System.putFloat(context.contentResolver, MIN_KEY, rate.hz)
        Settings.System.putFloat(context.contentResolver, PEAK_KEY, rate.hz)
    }

    fun current(context: Context): Float {
        return Settings.System.getFloat(context.contentResolver, PEAK_KEY, 60f)
    }

    fun next(context: Context): RefreshRate {
        val cur = current(context)
        return when {
            cur < 90f  -> RefreshRate.HZ_90
            cur < 120f -> RefreshRate.HZ_120
            else       -> RefreshRate.HZ_60
        }
    }
}
```

> **Not:** `Settings.System.putFloat` normal yazma iznine tabi olsa da, bu
> anahtarlar `Settings.System` içinde *secure* olarak işaretlendiği için
> `WRITE_SECURE_SETTINGS` izni olmadan `SecurityException` fırlatır — bu
> yüzden 11.2'deki izin adımı **zorunludur**.

### 11.4 Refresh Rate Quick Settings Tile

```kotlin
class RefreshRateTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val next = RefreshRateController.next(applicationContext)
        RefreshRateController.set(applicationContext, next)
        updateTileUi(next)
    }

    override fun onStartListening() {
        super.onStartListening()
        val cur = RefreshRateController.current(applicationContext)
        val match = RefreshRateController.RefreshRate.entries
            .minBy { kotlin.math.abs(it.hz - cur) }
        updateTileUi(match)
    }

    private fun updateTileUi(rate: RefreshRateController.RefreshRate) {
        qsTile?.apply {
            label = "Refresh Rate"
            subtitle = "${rate.hz.toInt()} Hz"
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
```

Davranış: tile'a her dokunuşta 60 → 90 → 120 → 60 ... şeklinde döngüsel
geçiş yapar, alt yazıda anlık değeri gösterir.

### 11.5 Manifest Eklentisi

```xml
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ProtectedPermissions" />

<service android:name=".RefreshRateTileService"
    android:exported="true"
    android:icon="@drawable/ic_refresh_rate"
    android:label="Refresh Rate"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

`tools:ignore="ProtectedPermissions"` derleme sırasında lint uyarısını
susturur (izin zaten root/adb ile ayrıca verildiği için burada beyan etmek
sadece dokümantasyon amaçlıdır, gerçek yetkiyi vermez).

### 11.6 Test Planı

1. Uygulamayı kur, ilk açılışta `pm grant ... WRITE_SECURE_SETTINGS`
   komutunun sessizce başarılı olduğunu logcat'te doğrula.
2. Quick Settings panelini aç, "Refresh Rate" tile'ını ekle (kullanıcı
   normalde QS düzenleme ekranından sürükleyip ekler).
3. Tile'a sırayla dokunup her seferinde gerçek ekran yenileme hızının
   değiştiğini doğrula — en güvenilir yöntem:
   ```
   adb shell dumpsys SurfaceFlinger | grep -i "refresh-rate\|fps"
   ```
   ya da geliştirici seçeneklerindeki "Show refresh rate" overlay'ini açıp
   ekranın köşesindeki canlı Hz göstergesini gözlemlemek.
4. Cihazı yeniden başlat, ayarın (Android'in kendi ayarı olduğu için)
   kalıcı kaldığını doğrula — bizim bir servis çalıştırmamıza gerek yok,
   bu değer sistem tarafından kalıcı tutulur (fan gibi sürekli yeniden
   yazma gerektirmez).

### 11.7 Riskler / Notlar

- **Panel desteklemiyorsa:** Eğer LineageOS'un device tree'sinde panelin
  `hbm`/`dsi` sürücüsü sadece 60Hz'i destekliyorsa, `peak_refresh_rate`'i
  120 yazmak sessizce hiçbir etki yapmayabilir (sistem izin verilen en
  yakın değere düşer). Bunu ilk elden doğrulamak için önce LineageOS'un
  kendi "Ayarlar > Ekran" menüsünde zaten 90/120Hz seçeneği sunulup
  sunulmadığına bakılmalı — sunuluyorsa donanım/sürücü desteği kesin var
  demektir.
- Bu özellik **sysfs veya root'a bağımlı değildir** (WRITE_SECURE_SETTINGS
  izni bir kere root ile verildikten sonra), bu yüzden fan özelliğinden çok
  daha az kırılgan ve gelecekteki LineageOS güncellemelerinde bozulma
  ihtimali çok düşük.

---

## 12. CPU Performans Modları (Power Saving / Balanced / Ultra)

### 12.1 Yaklaşım

Qualcomm SoC'lerde CPU çekirdekleri **cluster**'lara ayrılır (RP6'da 3 cluster
görüldü: `cpu-cluster0/1/2` thermal cooling device'ları olarak). Her cluster,
kendi `cpufreq` policy grubunu paylaşır. Kontrol noktası:

```
/sys/devices/system/cpu/cpufreq/policy*/scaling_governor          (yazılabilir)
/sys/devices/system/cpu/cpufreq/policy*/scaling_available_governors (salt okunur, referans)
/sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq           (yazılabilir, Hz cinsinden üst sınır)
/sys/devices/system/cpu/cpufreq/policy*/cpuinfo_max_freq           (salt okunur, donanımsal üst sınır)
```

**Önemli:** `policy0`, `policy4`, `policy7` gibi kaç tane ve hangi numaralarda
olduğu cihaza göre değişir — **sabit yazmak yerine boot'ta dinamik olarak
keşfedilmeli**:
```
ls /sys/devices/system/cpu/cpufreq/ | grep policy
```

### 12.2 Mod Tanımları

| Mod | Governor | scaling_max_freq |
|---|---|---|
| **Power Saving** | `powersave` (varsa) yoksa `schedutil` | `cpuinfo_max_freq`'in ~%60'ı |
| **Balanced** | `schedutil` (kernel varsayılanı) | `cpuinfo_max_freq` (sınırsız, kernel kendi yönetir) |
| **Ultra** | `performance` | `cpuinfo_max_freq` (tam, kısıtlama yok) |
| **Adaptive** | `schedutil` | FPS'e göre dinamik (bkz. Bölüm 13) |

`scaling_available_governors` cihazda hangi governor'ların gerçekten
desteklendiğini gösterir — kalibrasyon adımında (13.5) bu liste okunup
"Power Saving" için en uygun seçenek (`powersave` yoksa `conservative`, o da
yoksa sadece freq cap ile `schedutil`) otomatik seçilmeli.

### 12.3 Kod — CPU Mod Kontrolcüsü

```kotlin
object CpuModeController {

    data class Policy(val path: String, val maxFreqKHz: Int)

    fun discoverPolicies(): List<Policy> {
        val base = "/sys/devices/system/cpu/cpufreq"
        val dirs = Shell.cmd("ls $base | grep policy").exec().out
        return dirs.map { name ->
            val p = "$base/$name"
            val maxFreq = Shell.cmd("cat $p/cpuinfo_max_freq").exec().out.firstOrNull()?.toIntOrNull() ?: 0
            Policy(p, maxFreq)
        }
    }

    fun apply(mode: CpuMode, policies: List<Policy>) {
        policies.forEach { policy ->
            val (governor, freqPct) = when (mode) {
                CpuMode.POWER_SAVING -> "schedutil" to 0.6
                CpuMode.BALANCED     -> "schedutil" to 1.0
                CpuMode.ULTRA        -> "performance" to 1.0
                CpuMode.ADAPTIVE     -> "schedutil" to 1.0 // max freq Bölüm 13'te dinamik ayarlanır
            }
            val cappedFreq = (policy.maxFreqKHz * freqPct).toInt()
            Shell.cmd(
                "echo $governor > ${policy.path}/scaling_governor",
                "echo $cappedFreq > ${policy.path}/scaling_max_freq"
            ).exec()
        }
    }
}

enum class CpuMode { POWER_SAVING, BALANCED, ULTRA, ADAPTIVE }
```

### 12.4 CPU Quick Settings Tile

Fan tile'ıyla aynı desende: dokununca `POWER_SAVING → BALANCED → ULTRA →
ADAPTIVE → POWER_SAVING ...` döngüsü, `subtitle`'da anlık modu gösterir.

---

## 13. Adaptif Mod — FPS'e Göre Otomatik CPU Ayarı

### 13.1 Amaç

Kullanıcı "Adaptive" modunu seçtiğinde: ön plandaki uygulamanın gerçek FPS'i
düşükse (donma/takılma yaşanıyorsa) CPU'yu otomatik zorla; FPS zaten hedefte
ve stabilse (örn. 60 FPS'lik bir oyun sorunsuz 60 basıyorsa) CPU'yu kademeli
geri çekerek pil tasarrufu sağla. Bu, stock ROM'un "Smart" fan moduna
benzer ama CPU frekansı için.

### 13.2 FPS Ölçümü

Root ile periyodik olarak ön plandaki uygulamanın frame istatistiklerini
`dumpsys` üzerinden oku:

```kotlin
fun getForegroundPackage(): String {
    val out = Shell.cmd("dumpsys activity activities | grep mResumedActivity").exec().out
    // Regex ile paket adını çıkar
}

fun getRecentFps(pkg: String): Double {
    val raw = Shell.cmd("dumpsys gfxinfo $pkg framestats").exec().out
    // "framestats" çıktısındaki son ~120 frame'in "FrameCompleted" - "IntendedVsync"
    // zaman damgalarını parse edip ortalama frame süresinden FPS hesapla:
    // fps = 1_000_000_000.0 / ortalamaFrameSuresiNs
}
```

> Alternatif/daha hafif yöntem: `dumpsys SurfaceFlinger --latency <layerAdi>`
> (layer adını `dumpsys SurfaceFlinger --list` ile bulmak gerekir) — bazı
> cihazlarda `gfxinfo`'dan daha tutarlı sonuç verir. İkisi de root/shell
> gerektirir, uygulama içinden native API ile tüm sistem genelinde FPS
> okumanın (Choreographer sadece kendi uygulamanız için çalışır) tek yolu bu.

### 13.3 Karar Mantığı (Hysteresis ile — salınımı önlemek için)

```kotlin
class AdaptiveGovernor(private val policies: List<CpuModeController.Policy>) {

    private var currentFreqPct = 0.8  // başlangıç: %80
    private val targetFps = 60.0      // kullanıcı ayarlanabilir yapılabilir (v2)
    private val lowThreshold = 0.9    // hedefin altında kalırsa yükselt
    private val highThreshold = 1.05  // hedefi rahatça geçiyorsa düşür
    private val step = 0.1

    fun tick(measuredFps: Double) {
        val ratio = measuredFps / targetFps
        when {
            ratio < lowThreshold -> currentFreqPct = (currentFreqPct + step).coerceAtMost(1.0)
            ratio > highThreshold -> currentFreqPct = (currentFreqPct - step).coerceAtLeast(0.4)
            // aradaysa (0.9-1.05 arası): değiştirme, salınımı önle
        }
        policies.forEach { p ->
            val freq = (p.maxFreqKHz * currentFreqPct).toInt()
            Shell.cmd("echo $freq > ${p.path}/scaling_max_freq").exec()
        }
    }
}
```

- **Ölçüm sıklığı:** 1-2 saniyede bir yeterli — FPS ölçümü de `dumpsys` çağırdığı
  için çok sık yapılırsa (fan döngüsü gibi 300ms) hem gereksiz CPU yükü
  bindirir hem de kendi ölçtüğü şeyi bozar (dumpsys'in kendisi de CPU kullanır).
- **step (%10) ve hysteresis bandı (0.9-1.05)** ilk sürümde sabit; gerçek
  cihazda test edilip ince ayar yapılmalı — çok agresif olursa fren/gaz gibi
  sürekli salınır, çok yumuşak olursa tepki geç kalır.

### 13.4 Fan ile Entegrasyon (opsiyonel, v2 fikri)

Adaptive CPU modu aktifken, aynı mantık fan `cur_state`'ine de uygulanabilir
(CPU frekansı yükseldikçe fan da orantılı yükselsin) — ama bu, kernel'in
zaten kendi termal governor'ının yaptığı işe çok benziyor, bu yüzden v1'de
**fan ve CPU adaptif modlarını birbirinden bağımsız** tutmak (kullanıcı
ikisini de ayrı ayrı "Adaptive"e alabilir) daha basit ve hataya daha az açık.

### 13.5 Governor Kalibrasyonu (cihaz üzerinde yapılacak, kod yazmadan önce)

```
cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors
```
Her policy için çalıştırılıp desteklenen governor listesi not edilmeli —
"Power Saving" modunun hangi governor'ı kullanacağı (12.2 tablosu) buna göre
kesinleştirilmeli.

---

## 14. Pil Ömrü Tahmini

### 14.1 Veri Kaynağı

Root gerekmeden bile okunabilen standart Android/kernel arayüzleri:

```
/sys/class/power_supply/battery/capacity        (yüzde, 0-100)
/sys/class/power_supply/battery/current_now      (µA, negatifse deşarj oluyor demektir)
/sys/class/power_supply/battery/charge_full      (µAh, tam dolu kapasite)
/sys/class/power_supply/battery/charge_counter    (µAh, şu anki kalan şarj — varsa current_now'dan daha güvenilir)
```

Bazı cihazlarda dosya adları `current_now` yerine farklı olabilir — boot'ta
`ls /sys/class/power_supply/battery/` ile mevcut dosyalar keşfedilmeli.

### 14.2 Basit Tahmin Formülü

```kotlin
object BatteryEstimator {

    fun estimateRemainingMinutes(): Int? {
        val chargeNowUah = readLong("/sys/class/power_supply/battery/charge_counter") ?: return null
        val currentNowUa = readLong("/sys/class/power_supply/battery/current_now") ?: return null
        if (currentNowUa >= 0) return null // şarj oluyor ya da veri yok, tahmin anlamsız
        val drainRateUa = kotlin.math.abs(currentNowUa)
        val hours = chargeNowUah.toDouble() / drainRateUa.toDouble()
        return (hours * 60).toInt()
    }

    private fun readLong(path: String): Long? =
        Shell.cmd("cat $path").exec().out.firstOrNull()?.trim()?.toLongOrNull()
}
```

**Not:** `current_now` anlık değerdir, dalgalanabilir (oyun oynarken CPU/GPU
yükü sürekli değiştiği için tahmin de saniyeden saniyeye zıplayabilir). Daha
kararlı bir gösterge için **son 30-60 saniyenin hareketli ortalamasını**
(moving average) kullanmak önerilir:

```kotlin
class MovingAverage(private val windowSize: Int = 10) {
    private val samples = ArrayDeque<Long>()
    fun add(value: Long): Long {
        samples.addLast(value)
        if (samples.size > windowSize) samples.removeFirst()
        return samples.average().toLong()
    }
}
```

### 14.3 Gösterim — Kalıcı Bildirim ve Quick Settings

**Kalıcı bildirim (önerilen, ana gösterim yeri):** Bölüm 6.3'teki
`FanControlService` zaten foreground bildirim taşıyor — bunu `SystemControlService`
olarak genişletip **tek bildirimde üç satır** göster:

```
Fan: Sport (kademe 6/8)
CPU: Adaptive (%85 üst sınır)
Pil: ~3 sa 42 dk kaldı (~14%/sa tüketim)
```

Bildirim her ~5-10 saniyede bir güncellenir (çok sık güncelleme bildirim
"spam"ine ve gereksiz pil tüketimine yol açar, `NotificationManager.notify()`
aynı ID ile çağrıldığında sessizce günceller, yeni bildirim oluşturmaz).

**Quick Settings Tile (opsiyonel ek gösterge):** Ayrı bir "Battery" tile'ı
yerine, mevcut CPU tile'ının `subtitle`'ına pil tahminini eklemek daha az
karmaşa yaratır, örn: `subtitle = "Adaptive · 3s42d"`. Zorunlu değil, kullanıcı
tercihine göre v2'de eklenebilir.

### 14.4 Doğruluk Uyarısı

Bu tahmin **doğrusal ekstrapolasyon**dur — "şu anki tüketim hızı sabit
kalırsa" varsayımına dayanır. Kullanıcı oyunu kapatıp ekranı kapatırsa
gerçek kalan süre bu tahminden çok daha uzun olacaktır. Arayüzde bunun bir
"anlık tahmin" olduğu, kesin bir garanti olmadığı küçük bir notla (örn.
bildirimde `~` işareti, ya da uygulama içinde bir tooltip) belirtilmeli —
kullanıcıyı yanıltmamak için önemli bir UX detayı.

---

## 15. Güncellenmiş Yol Haritası

1. **v0.1** — Fan: dinamik path bulma + sabit "Max" ve "Off" butonu.
2. **v0.2** — Fan: tüm modlar + kalibrasyon + Foreground Service + Boot persistence.
3. **v0.3** — Fan: Quick Settings Tile + Watchdog.
4. **v0.4** — Refresh Rate Tile'ı (Bölüm 11).
5. **v0.5** — CPU Modları (Power Saving/Balanced/Ultra) + Tile (Bölüm 12).
6. **v0.6** — Adaptive CPU modu (FPS'e göre otomatik, Bölüm 13) — en riskli/
   en çok kalibrasyon gerektiren parça, en sona bırakılmalı.
7. **v0.7** — Pil ömrü tahmini + birleşik kalıcı bildirim (Bölüm 14) —
   tüm servisler `SystemControlService` altında birleştirilir.
8. **v0.8** — Joystick RGB kontrolü + döner gökkuşağı animasyonu (Bölüm 16).
9. **v1.0** — UI cilası, ayarların yedeklenmesi, tüm modların tek ekrandan
   yönetildiği "Dashboard" ana ekranı.

---

## 16. Joystick RGB Kontrolü

### 16.1 Donanım Keşfi Özeti

Fan'ın aksine, bu özellik **standart Linux "multicolor LED" framework**'ünü
kullanıyor (`CONFIG_LEDS_CLASS_MULTICOLOR`) — thermal framework gibi bir
kernel governor'ı tarafından sürekli ezilmiyor, yani **tek seferlik yazma
kalıcı kalıyor** (fan'daki 300ms döngüye burada gerek yok). Bu, joystick
RGB'yi fan'dan çok daha basit ve daha az kırılgan bir özellik yapıyor.

Her joystick'te 4 ayrı LED var, her biri `multi_intensity` (R G B, 0-255)
ve `brightness` (0-255, genel parlaklık/commit) dosyalarına sahip:

```
/sys/class/leds/left:stick:0..3/{multi_intensity, brightness, max_brightness}
/sys/class/leds/right:stick:0..3/{multi_intensity, brightness, max_brightness}
```

**Yazma deseni** (cihaz üzerinde doğrulandı):
```bash
echo "<R> <G> <B>" > /sys/class/leds/<stick>:stick:<index>/multi_intensity
echo 255 > /sys/class/leds/<stick>:stick:<index>/brightness
```
`multi_intensity` üç kanalın oranını belirler, `brightness` o anki genel
parlaklık seviyesini uygular/commit eder — ikisi birlikte yazılmalı.

### 16.2 Fiziksel Konum Haritası (cihaz üzerinde test edilerek çıkarıldı)

**Sol joystick:**

| Index | Fiziksel Konum |
|---|---|
| `left:stick:0` | Üst-Sol |
| `left:stick:1` | Alt-Sol |
| `left:stick:2` | Alt-Sağ |
| `left:stick:3` | Üst-Sağ |

**Sağ joystick (sol ile nokta-simetrik/180° döndürülmüş yerleşim):**

| Index | Fiziksel Konum |
|---|---|
| `right:stick:0` | Alt-Sağ |
| `right:stick:1` | Üst-Sağ |
| `right:stick:2` | Üst-Sol |
| `right:stick:3` | Alt-Sol |

> **Not:** Sağ joystick'in haritası sol ile simetrik değil, **noktasal
> döndürülmüş** (180°) — donanım yerleşiminden kaynaklanıyor olabilir.
> Kod tarafında bu iki farklı haritayı ayrı ayrı tanımlamak gerekiyor,
> "aynı index her iki stick'te aynı köşe" varsayımı **yanlış**.

### 16.3 Kod — Joystick RGB Kontrolcüsü

```kotlin
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
enum class Stick { LEFT, RIGHT }

object JoystickRgbController {

    // Cihazda doğrulanan fiziksel harita (Bölüm 16.2)
    private val leftMap = mapOf(
        Corner.TOP_LEFT to 0, Corner.BOTTOM_LEFT to 1,
        Corner.BOTTOM_RIGHT to 2, Corner.TOP_RIGHT to 3
    )
    private val rightMap = mapOf(
        Corner.BOTTOM_RIGHT to 0, Corner.TOP_RIGHT to 1,
        Corner.TOP_LEFT to 2, Corner.BOTTOM_LEFT to 3
    )

    private fun path(stick: Stick, corner: Corner): String {
        val prefix = if (stick == Stick.LEFT) "left" else "right"
        val index = (if (stick == Stick.LEFT) leftMap else rightMap)[corner]
        return "/sys/class/leds/$prefix:stick:$index"
    }

    fun setColor(stick: Stick, corner: Corner, r: Int, g: Int, b: Int, brightness: Int = 255) {
        val base = path(stick, corner)
        Shell.cmd(
            "echo \"$r $g $b\" > $base/multi_intensity",
            "echo $brightness > $base/brightness"
        ).exec()
    }

    fun setAll(r: Int, g: Int, b: Int, brightness: Int = 255) {
        for (stick in Stick.entries) {
            for (corner in Corner.entries) {
                setColor(stick, corner, r, g, b, brightness)
            }
        }
    }

    fun turnOff() = setAll(0, 0, 0, 0)
}
```

### 16.4 Döner (Rotating) Gökkuşağı Animasyonu

**Fikir:** Her stick'in 4 köşesine, aralarında 90° faz farkı olan bir HSV
renk tekerleği uygulanır; zamanla hue (renk tonu) ilerletilerek renklerin
köşeler arasında "dönüyormuş" hissi verilmesi sağlanır.

```kotlin
object HsvUtil {
    /** hue: 0-360, sat/value: 0-1 → (r,g,b) 0-255 */
    fun hsvToRgb(hue: Float, sat: Float = 1f, value: Float = 1f): Triple<Int, Int, Int> {
        val c = value * sat
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (r1, g1, b1) = when {
            hue < 60  -> Triple(c, x, 0f)
            hue < 120 -> Triple(x, c, 0f)
            hue < 180 -> Triple(0f, c, x)
            hue < 240 -> Triple(0f, x, c)
            hue < 300 -> Triple(x, 0f, c)
            else      -> Triple(c, 0f, x)
        }
        return Triple(
            ((r1 + m) * 255).toInt().coerceIn(0, 255),
            ((g1 + m) * 255).toInt().coerceIn(0, 255),
            ((b1 + m) * 255).toInt().coerceIn(0, 255)
        )
    }
}

class RotatingRainbowAnimator(
    private val scope: CoroutineScope,
    private val speedDegPerTick: Float = 6f,   // her tick'te hue ilerlemesi
    private val tickIntervalMs: Long = 50L      // ~20 FPS animasyon
) {
    private var job: Job? = null
    private var hue = 0f

    // Köşeler arası 90° faz farkı — "dönen" görünüm için
    private val cornerPhaseOffset = mapOf(
        Corner.TOP_LEFT to 0f,
        Corner.TOP_RIGHT to 90f,
        Corner.BOTTOM_RIGHT to 180f,
        Corner.BOTTOM_LEFT to 270f
    )

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                for (corner in Corner.entries) {
                    val cornerHue = (hue + (cornerPhaseOffset[corner] ?: 0f)) % 360f
                    val (r, g, b) = HsvUtil.hsvToRgb(cornerHue)
                    JoystickRgbController.setColor(Stick.LEFT, corner, r, g, b)
                    JoystickRgbController.setColor(Stick.RIGHT, corner, r, g, b)
                }
                hue = (hue + speedDegPerTick) % 360f
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
```

**Kullanım:**
```kotlin
val animator = RotatingRainbowAnimator(scope = serviceScope)
animator.start()  // sürekli dönen gökkuşağı efekti başlar
// ...
animator.stop()   // durdurulunca son renkte kalır; kapatmak için:
JoystickRgbController.turnOff()
```

### 16.5 Performans Notu

Her tick'te 8 LED (2 stick × 4 köşe) için 2'şer `su` komutu (toplam 16 shell
çağrısı) 50ms'de bir çalıştırmak, fan döngüsündeki tek dosyaya yazmaktan
**çok daha maliyetli**. Bunun için:

- `libsu`'nun **kalıcı shell** oturumu (Bölüm 6.2) kesinlikle kullanılmalı,
  her tick'te yeni `su` process'i açmak performansı ciddi şekilde düşürür.
- Mümkünse tüm 16 yazma komutu **tek bir `Shell.cmd()` çağrısında**,
  noktalı virgülle ayrılmış tek bir shell script olarak gönderilmeli (16
  ayrı `exec()` çağrısı yerine 1 çağrıda 16 komut) — IPC/process overhead'i
  ciddi şekilde azaltır:
  ```kotlin
  val allCommands = buildList {
      for (corner in Corner.entries) {
          // ... her köşe için iki echo komutu ekle
      }
  }
  Shell.cmd(*allCommands.toTypedArray()).exec()
  ```
- Animasyon, kullanıcı deneyimini bozmadan pil tüketimini makul tutmak için
  varsayılan olarak **ekran açıkken / uygulama ön plandayken** çalışmalı;
  ekran kapandığında (`ACTION_SCREEN_OFF` broadcast'i dinlenerek) otomatik
  durdurulup LED'ler kapatılmalı (`turnOff()`).

### 16.6 Test Planı

1. `setColor` ile tek tek her köşeyi farklı renklere ayarla, haritanın
   (16.2) doğru olduğunu tekrar doğrula.
2. `RotatingRainbowAnimator.start()` çağrısıyla animasyonu başlat, gerçekten
   renklerin köşeler arasında akıcı bir şekilde "döndüğünü" gözlemle.
3. `speedDegPerTick` ve `tickIntervalMs` değerlerini değiştirerek (örn. daha
   yavaş/hızlı döngü) kullanıcı tercihine uygun bir varsayılan hız belirle.
4. Ekranı kapat/aç, animasyonun beklendiği gibi durup başladığını doğrula.
5. Pil tüketimini (animasyon açık/kapalıyken) karşılaştırarak makul bir
   varsayılan tick aralığı (50ms çok agresifse 100ms'e çıkarılabilir) seç.

### 16.7 Saat Yönünde Dönen Tam Gradient — Yön Matematiği ve Kod Düzeltmesi

Cihaz üzerinde aShellYou ile test edilip doğrulanan, hem sol hem sağ
joystick'in **8 LED'ini birlikte** tek bir dönen gökkuşağı halkası gibi
çalıştıran nihai yaklaşım, Bölüm 16.4'teki `RotatingRainbowAnimator`'a göre
bir **yön düzeltmesi** içeriyor:

**Matematik:** Her köşeye sabit bir faz atanır (saat yönünde sayarak:
Üst-Sol=0°, Üst-Sağ=90°, Alt-Sağ=180°, Alt-Sol=270°). `cornerHue = (taban_hue
+ faz) % 360` formülünde, **taban_hue zamanla arttırılırsa** desen görsel
olarak **saatin tersi yönde** akar; **taban_hue azaltılırsa** (mod 360 ile
sarmalanarak) desen **saat yönünde** akar. Bu, kaynağı `fill_rainbow` tarzı
"moving rainbow" tekniklerinden gelen standart bir sonuç — faz sırasının
kendisi sabit kalıp taban değerin kayma yönü, görsel akışın yönünü ters
çeviriyor.

**Kotlin'e yansıması — `RotatingRainbowAnimator` içindeki tek satırlık
değişiklik:**
```kotlin
// Bölüm 16.4'teki animasyon döngüsünde:
hue = (hue + speedDegPerTick) % 360f       // <- bu, SAAT YÖNÜNÜN TERSİ yönde döner

// Saat yönünde döndürmek için:
hue = (hue - speedDegPerTick + 360f) % 360f
```

**Önemli pratik not:** Yön algısı ekranın fiziksel yerleşimine (ve sağ
joystick'in Bölüm 16.2'de bulunan **noktasal simetrik/ters** haritasına)
duyarlı olabilir — bu yüzden nihai yön, cihazda gözle doğrulanmalı, sadece
teoriye güvenilmemeli. Test scriptinde (ve `RotatingRainbowAnimator`'da) yön
kolayca tek satırla tersine çevrilebilecek şekilde (`+`/`-` işareti) bırakılmalı,
kullanıcıya "Yön: Saat yönü / Tersi" şeklinde bir tercih olarak da sunulabilir.

**Performans notu (Bölüm 16.5'e ek):** Test scriptinde `brightness`'ın
döngü **dışında bir kere** ayarlanıp döngü içinde yalnızca `multi_intensity`
yazılması, gereksiz dosya yazma sayısını yarıya indiriyor — bu optimizasyon
`JoystickRgbController`/`RotatingRainbowAnimator` implementasyonuna da
aynen taşınmalı (`setColor` çağrılarında `brightness`'ı her tick'te değil,
yalnızca ilk çağrıda veya değiştiğinde yazacak şekilde).

---

## 17. LineageOS Ayarlar Uygulamasına Menü Enjeksiyonu

### 17.1 Mekanizma — Neden Bu Bir "Hack" Değil

Android'in AOSP Settings uygulaması (`com.android.settings`), Android 8.0'dan
beri **resmi olarak belgelenmiş** bir "dynamic setting injection" mekanizması
sunuyor (bkz. `source.android.com/devices/tech/settings/info-architecture`).
Bu, OEM/carrier uygulamalarının (ve Google'ın kendi "Digital Wellbeing" gibi
girdilerinin) Ayarlar'a **Settings'in kaynak kodu değiştirilmeden/yeniden
derlenmeden** kendi menü öğelerini eklemesini sağlıyor.

Settings uygulaması açılışta `PackageManager`'a şu action'a sahip tüm
aktiviteleri sorup, bulduklarını otomatik olarak kendi listesine ekliyor:
```
com.android.settings.action.IA_SETTINGS
```

Bu, Bölüm 7'deki Quick Settings Tile enjeksiyonuyla (`BIND_QUICK_SETTINGS_TILE`)
aynı felsefede çalışıyor — ayrı bir uygulama olarak kalıyoruz, ama sistem bizi
kendi arayüzüne "davet ediyor".

### 17.2 Manifest Eklentisi

```xml
<activity android:name=".SystemControlDashboardActivity"
    android:label="Handheld Kontrolleri"
    android:icon="@drawable/ic_handheld"
    android:exported="true">
    <intent-filter>
        <action android:name="com.android.settings.action.IA_SETTINGS" />
    </intent-filter>
    <!-- Ayarlar'ın hangi bölümünde görüneceği -->
    <meta-data android:name="com.android.settings.category"
        android:value="com.android.settings.category.ia.homepage" />
    <!-- Girdinin altında görünecek kısa açıklama -->
    <meta-data android:name="com.android.settings.summary"
        android:value="Fan, CPU, RGB ve ekran ayarları" />
</activity>
```

**Kategori seçenekleri** (`CategoryKey` sabitleri, hangisinin en uygun olduğu
cihazda görsel olarak denenip seçilmeli):
```
com.android.settings.category.ia.homepage   (Ayarlar ana sayfası, en üstte)
com.android.settings.category.ia.system     (Sistem bölümü)
com.android.settings.category.ia.device     (Cihaz Hakkında bölümü)
com.android.settings.category.ia.display    (Ekran bölümü)
```

### 17.3 SystemControlDashboardActivity — Ayarlar Görünümüyle Uyumlu UI

Gerçek Ayarlar sayfalarıyla görsel/işlevsel tutarlılık için, kendi ekranımızı
ham bir `Activity` yerine **`PreferenceFragmentCompat`** üzerine kurmak
öneriliyor — bu, Settings'in kendisinin kullandığı aynı bileşen, yani liste
görünümü, kategori başlıkları, switch/slider stilleri otomatik olarak
sistemin geri kalanıyla birebir aynı görünür:

```kotlin
class SystemControlDashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SystemControlPreferenceFragment())
            .commit()
    }
}

class SystemControlPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.system_control_preferences, rootKey)
        // Fan modu, CPU modu, Refresh Rate, RGB gibi Preference'ları burada
        // FanControlService/CpuModeController/RefreshRateController'a bağla
    }
}
```

`res/xml/system_control_preferences.xml` içinde `ListPreference` (Fan/CPU
modu seçimi), `SwitchPreferenceCompat` (RGB animasyon aç/kapa) gibi standart
Preference bileşenleri kullanılır — Ayarlar'daki diğer sayfalarla aynı stil.

### 17.4 Doğrulanması Gereken Belirsizlik

Bu mekanizma **tarihsel olarak sistem imzalı/öncelikli OEM uygulamaları için**
tasarlandı. Bazı Android sürümlerinde Settings, güvenlik amacıyla bu
enjeksiyonu yalnızca `/system` altında önceden yüklenmiş veya platform
imzasına sahip uygulamalarla sınırlamış olabilir — bu, dokümantasyonda net
değil ve **sürüme göre değişebilir.**

**Bunu doğrulamanın tek yolu cihaz üzerinde denemek:**
1. Yukarıdaki manifest girdisiyle normal (sideload edilmiş, sistem uygulaması
   olmayan) bir APK kur.
2. Ayarlar uygulamasını aç, ilgili kategoriyi (örn. ana sayfa) kontrol et.
3. **Görünüyorsa:** Sorun yok, normal kullanıcı uygulaması olarak kalabiliriz.
4. **Görünmüyorsa:** İki yedek seçenek var:
   - **(a)** Uygulamayı Magisk modülü olarak `/system/priv-app/` altına
     kurup **platform imzasıyla** (LineageOS'un test-keys'i, `testkey` —
     AOSP'de herkese açık) yeniden imzalamak — sistem uygulaması statüsü
     kazandırır, ama kurulumu karmaşıklaştırır (normal `.apk` kurulumu
     yerine Magisk modülü + reboot gerektirir).
   - **(b)** Enjeksiyonu tamamen bırakıp Bölüm 2'deki gibi **ayrı bir
     launcher ikonlu uygulama + Quick Settings Tile'lar** ile yetinmek —
     zaten çalışan, garanti bir çözüm.

### 17.5 Test Planı

1. Manifest'e enjeksiyon kodu eklenmiş bir debug APK derle.
2. Cihaza normal yoldan (adb install) kur, Magisk/system app YAPMA — önce
   en kısıtlı senaryoyu test et.
3. Ayarlar > Ana Sayfa'yı aç, girdinin görünüp görünmediğini kontrol et.
4. Görünmüyorsa `com.android.settings.category.ia.system` ve
   `ia.device` kategorilerini de ayrı ayrı dene — bazı kategoriler diğerlerinden
   daha az kısıtlı olabilir.
5. Hiçbiri çalışmazsa 17.4.(a) veya (b)'ye geç, sonucu bu bölüme not düş.

---

## 18. Güncellenmiş Yol Haritası (v2)

1. **v0.1 – v0.7** — Bölüm 15'teki sıra aynen geçerli (Fan → Refresh Rate →
   CPU Modları → Adaptive CPU → Pil tahmini).
2. **v0.8** — Joystick RGB + döner animasyon (Bölüm 16).
3. **v0.9** — Ayarlar menü enjeksiyonu denemesi (Bölüm 17) — önce en kısıtlı
   senaryo (normal APK) test edilir, gerekirse yedek plana geçilir.
4. **v1.0** — UI cilası, ayarların yedeklenmesi, tüm modların tek "Dashboard"
   ekranından (hem bağımsız uygulama hem varsa Ayarlar-içi sayfa olarak
   aynı `PreferenceFragmentCompat` kodunu paylaşarak) yönetilmesi.

---

## 19. Oyun İçi Overlay

### 19.1 Amaç

Ekranın kenarında (genelde bir köşede, dokunulunca genişleyen küçük bir
"balon"), oyun oynarken şu anki durumu gösteren ve hızlı kontrol sağlayan bir
panel: FPS, sıcaklık, pil yüzdesi/kalan süre, fan modu — hepsi tek bakışta.

### 19.2 Teknik Yaklaşım — `TYPE_APPLICATION_OVERLAY`

```kotlin
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 100 }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        windowManager.addView(overlayView, params)
        // Sürükleme: onTouchListener ile params.x/y güncellenip updateViewLayout çağrılır
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(overlayView)
    }
}
```

**Gerekli izin:** `SYSTEM_ALERT_WINDOW` — Android 6+'da kullanıcının manuel
onayı gerekir (`Settings.canDrawOverlays()` ile kontrol, `ACTION_MANAGE_OVERLAY_PERMISSION`
intent'iyle yönlendirme) **veya** root ile:
```
Shell.cmd("appops set $packageName SYSTEM_ALERT_WINDOW allow").exec()
```

### 19.3 İçerik ve Veri Kaynağı

Overlay, zaten `SystemControlService`'te hesaplanan verileri (Bölüm 6.3, 13.2,
14.2) bir `Binder`/`LiveData` üzerinden okuyup 1 saniyede bir günceller —
kendi ayrı bir ölçüm döngüsü kurmaz, mevcut servisin verisini tüketir.

Panelde: `FPS: 58` · `🌡️ 68°C` · `🔋 3s42d` · `Fan: Sport` — dokunulunca
genişleyip Quiet/Smart/Sport/Max butonlarını ve RGB aç/kapa'yı gösterir.

### 19.4 Riskler

- Bazı oyunlar/emülatörler tam ekran + immersive modda overlay'i gizleyebilir
  ya da input'u yanlış yorumlayabilir — `FLAG_NOT_FOCUSABLE` ile dokunma
  olaylarının oyuna geçmesini (overlay'e dokunulmadığı sürece) sağlamak şart.
- Overlay sürekli çizim yaptığı için hafif GPU/pil maliyeti var; güncelleme
  sıklığı 1 sn'de tutulmalı, daha sık gerekmez.

---

## 20. Oyun Bazlı Otomatik Profil

### 20.1 Amaç

Kullanıcı belirli bir oyun için önceden bir profil (Fan modu + CPU modu +
Refresh Rate) tanımlar; o oyun ön plana her geldiğinde otomatik uygulanır,
oyundan çıkılınca genel varsayılana döner.

### 20.2 Ön Planı İzleme

Bölüm 13.2'de FPS ölçümü için zaten kullandığımız yöntemle aynı:
```kotlin
fun getForegroundPackage(): String? {
    val out = Shell.cmd("dumpsys activity activities | grep mResumedActivity").exec().out
    val regex = Regex("""(\S+)/(\S+)\s+t\d+""")
    return out.firstOrNull()?.let { regex.find(it)?.groupValues?.get(1) }
}
```
`SystemControlService`'in ana döngüsünde (2-3 saniyede bir yeterli, FPS
ölçümü kadar sık gerekmez) bu paket adı okunur, önceki tick'teki paketle
karşılaştırılır — **değiştiyse** profil eşleştirmesi tetiklenir.

### 20.3 Profil Veri Modeli ve Depolama

```kotlin
data class GameProfile(
    val packageName: String,
    val fanMode: FanMode,
    val cpuMode: CpuMode,
    val refreshRate: RefreshRateController.RefreshRate
)
```
`SharedPreferences`'ta JSON olarak (`Gson`/`kotlinx.serialization`)
`Map<String, GameProfile>` şeklinde saklanır — anahtar paket adı.

### 20.4 Uygulama Akışı

```kotlin
fun onForegroundAppChanged(newPackage: String) {
    val profile = savedProfiles[newPackage]
    if (profile != null) {
        applyMode(profile.fanMode, profile.cpuMode, profile.refreshRate)
    } else {
        applyMode(defaultProfile.fanMode, defaultProfile.cpuMode, defaultProfile.refreshRate)
    }
}
```

### 20.5 UI

Ana ekranda "Kurulu Uygulamalar" listesi (`PackageManager.getInstalledApplications`
ile, ikon+isim), kullanıcı bir uygulamaya dokununca o anki genel ayarları
(varsa) "bu oyun için kaydet" diyerek `GameProfile` oluşturur. Liste, sadece
launcher intent'i olan (oynanabilir) uygulamalarla filtrelenmeli.

---

## 21. Titreşim (Rumble) Yoğunluğu Ayarı

### 21.1 İki Farklı Senaryo — Önce Ayrım Yapılmalı

**(a) Sistem/UI dokunsal geri bildirimi** (tuş basma, kaydırma titreşimi):
standart `Settings.System.HAPTIC_FEEDBACK_INTENSITY` (bazı cihazlarda ek
olarak `Settings.System` içinde vendor-özel bir anahtar da olabilir) — bunu
değiştirmek `WRITE_SECURE_SETTINGS` izniyle (Bölüm 11.2'dekiyle aynı yöntem)
mümkün.

**(b) Oyun kumandası rumble motoru** (gerçek oyun geri bildirimi, örn.
RetroArch/emülatörlerin tetiklediği titreşim): Bu, muhtemelen ayrı bir
donanım (haptics IC, PMIC üzerinden) ve Linux'un **force-feedback (FF)**
arayüzü ya da bir `/sys/class/leds/vibrator` benzeri sysfs üzerinden kontrol
ediliyor — **bu, cihazda henüz keşfedilmedi, önce reverse-engineering
gerekiyor.**

### 21.2 Keşif Adımları (cihazda çalıştırılacak)

```bash
# 1. Vibrator/haptics ile ilgili LED class cihazı var mı (fan/RGB'de olduğu gibi)
for l in /sys/class/leds/*vibrat*; do echo "== $l =="; ls "$l"; done

# 2. Force-feedback destekleyen input cihazı var mı
for f in /sys/class/input/input*/capabilities/ff; do echo "$f: $(cat $f 2>/dev/null)"; done

# 3. Qualcomm haptics sürücüsü (yaygın: qcom,haptics) sysfs'i var mı
find /sys -iname "*haptic*" 2>/dev/null

# 4. Genel vibrator class'ı (eski usül timed_output) var mı
ls -la /sys/class/timed_output/ 2>/dev/null
```

### 21.3 Beklenen Kontrol Noktası ve Kod (bulunursa)

Eğer bir "max voltage/amplitude" dosyası bulunursa (örn. `vmax_mv`, `amplitude`),
kontrol basit bir sysfs yazma olur — fan'a benzer ama **muhtemelen bir kernel
governor'ı olmadığı için döngüsüz, kalıcı** (RGB'deki gibi):
```kotlin
object VibrationIntensityController {
    private const val PATH = "/sys/class/leds/vibrator/vmax_mv" // örnek, doğrulanmalı
    fun setIntensity(percent: Int) {
        val mv = (percent.coerceIn(0, 100) * MAX_MV / 100)
        Shell.cmd("echo $mv > $PATH").exec()
    }
}
```

**Bulunamazsa yedek plan:** Yalnızca kendi uygulamamızın tetiklediği
titreşimler için Android'in standart `VibrationEffect.createOneShot(duration, amplitude)`
(amplitude 1-255) API'siyle **kendi kontrolümüzü** sunabiliriz, ama bu diğer
oyunların/emülatörlerin kendi tetiklediği rumble'ı kapsamaz — sistem geneli
bir yoğunluk çarpanı, ancak gerçek donanım sysfs'i bulunursa mümkün olur.

### 21.4 Test Planı

Yukarıdaki 4 keşif komutunun çıktısına göre gerçek kontrol noktası
belirlenip, bulunan dosyaya yazarak titreşim motorunun cihazda fiziksel
olarak hissedilir şekilde güçlenip zayıfladığı doğrulanmalı — aynı fan/RGB
sürecinde izlenen "önce keşfet, sonra cihazda doğrula" metodolojisi.

---

## 22. M1/M2 Makro Butonlarını Düz Input'a Çevirme

### 22.1 Mevcut Durum

M1/M2 (arka ek butonlar), muhtemelen `com.rp.mapping` (RsMapping) uygulaması
tarafından varsayılan olarak bir **makro** işlevine (örn. başka bir tuşu
tekrar üretme, ya da GameAssistant overlay'ini açma gibi bir kısayol) atanmış
durumda — yani oyunlar/emülatörler bu butonlara **kendi bağımsız bir
tuş** olarak değil, dolaylı bir eylemin tetikleyicisi olarak erişiyor
olabilir. Amaç: bu iki butonun **standart, benzersiz bir gamepad tuş kodu**
olarak (örn. RetroArch, Steam Input gibi uygulamaların doğrudan
eşleyebileceği) davranmasını sağlamak.

### 22.2 Keşif Adımları

**1. Ham tuş kodlarını bul** (`getevent`, Android'in dahili input izleme
aracı — M1/M2'ye basıp bırakınca çıkan kodu gösterir):
```bash
getevent -lt
```
Bu komutu çalıştırıp M1'e sonra M2'ye bas, çıktıda `EV_KEY` satırlarını
(örn. `BTN_TRIGGER_HAPPY1`, `KEY_MACRO1`, ya da vendor'a özel bir kod) not
al — hangi `/dev/input/eventX` cihazından geldiğini de kaydet.

**2. Bu input cihazının key layout (.kl) dosyasını bul:**
```bash
find /system/usr/keylayout /vendor/usr/keylayout -iname "*.kl" 2>/dev/null
```
Cihazın adını (`getevent -i` ile input cihazının adını öğrenip) ilgili
`.kl` dosyasını bulup içeriğine bakılmalı:
```bash
cat /system/usr/keylayout/<cihaz_adi>.kl
```
İçinde M1/M2'nin ham koduna karşılık gelen satırı ara (örn.
`key 316   APP_SWITCH` gibi bir eşleme varsa, bu "makro" davranışının
kaynağı budur).

### 22.3 Düzeltme — Özel Key Layout Override

Android, `.kl` dosyalarını `/system/usr/keylayout/` (ya da `/vendor/usr/keylayout/`)
altında arar. Bu dosyayı (root ile) **override edip**, M1/M2'nin ham koduna
Android'in **genel amaçlı ekstra gamepad butonları** için ayrılmış standart
kodlarını (`BUTTON_15`, `BUTTON_16` gibi — tam olarak bu tür "fazladan"
butonlar için var olan, `KeyEvent.KEYCODE_BUTTON_1`..`KEYCODE_BUTTON_16`
aralığı) atarız:

```
# Örnek .kl satırı (mevcut satırın üzerine yazılacak, kesin kod 22.2'de bulunanla değişir)
key 316   BUTTON_15
key 317   BUTTON_16
```

Bu dosya değişikliği bir Magisk modülü olarak paketlenip `/system` veya
`/vendor` üzerine (systemless overlay ile) uygulanmalı — uygulamamızın
kendisi bunu çalışma zamanında değiştiremez (bu, input framework'ünün boot
zamanında okuduğu statik bir dosya).

### 22.4 Uygulama İçi Kullanım

Değişiklik sonrası, herhangi bir uygulama (bizimki dahil) standart
`KeyEvent.KEYCODE_BUTTON_15`/`16` olarak M1/M2'yi yakalayabilir:
```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.KEYCODE_BUTTON_15) { /* M1 */ }
    if (event.keyCode == KeyEvent.KEYCODE_BUTTON_16) { /* M2 */ }
    return super.dispatchKeyEvent(event)
}
```
Bu, RetroArch/Steam Input gibi harici uygulamaların da M1/M2'yi kendi
eşleme ekranlarında **görüp doğrudan atayabilmesini** sağlar — artık gizli
bir makro değil, sıradan bir gamepad tuşu.

### 22.5 Risk

`.kl` dosyası sistem/vendor partisyonunda olduğu için değişiklik **Magisk
modülü gerektirir** (uygulama kendi başına yapamaz) ve her LineageOS
güncellemesinde modülün uyumluluğunu tekrar kontrol etmek gerekir. Ayrıca
mevcut makro davranışına alışmış kullanıcılar için bu bir "kırıcı değişiklik"
olacağından, varsayılan olarak değil, **kullanıcının bilinçli olarak
etkinleştirdiği bir seçenek** olarak sunulmalı.

---

## 23. Xbox / Nintendo (Retroid) Buton Düzeni Değiştirme

### 23.1 Yaklaşım — "En Kötü Senaryo" Zaten Yeterli

Bu özellik **zaten LineageOS/stock ayarlarında mevcut** (A/B/X/Y'nin
fiziksel-mantıksal eşlemesini değiştiren bir switch). Kendi buton takas
mantığımızı sıfırdan yazmak yerine (input framework seviyesinde uğraşmak
riskli ve gereksiz), **mevcut ayarın kendisini bizim UI'ımızdan tetiklemek**
yeterli — kullanıcının istediği "en kötü ihtimalde o ayarı değiştir" tam
olarak bu.

### 23.2 Keşif Adımları

**1. Mevcut ayarın hangi `Settings` anahtarına yazdığını bul** — Ayarlar'da
bu toggle'ı aç/kapat yaparken `content://settings` sorgularını izle:
```bash
# Toggle'ı değiştirmeden önce anlık genel ayarları kaydet
settings list system > /data/local/tmp/before.txt
settings list secure >> /data/local/tmp/before.txt
settings list global >> /data/local/tmp/before.txt
```
Ayarlar'a git, "Xbox/Nintendo düzeni" toggle'ını değiştir, sonra:
```bash
settings list system > /data/local/tmp/after.txt
settings list secure >> /data/local/tmp/after.txt
settings list global >> /data/local/tmp/after.txt
diff /data/local/tmp/before.txt /data/local/tmp/after.txt
```
`diff` çıktısı, hangi anahtarın değiştiğini gösterecek (örn.
`button_layout_style=0` → `1` gibi).

**2. Eğer bir `Settings` anahtarı üzerinden değil de doğrudan bir sysfs/init
property üzerinden yapılıyorsa** (bazı OEM'ler `getprop`/`setprop` kullanır):
```bash
getprop | grep -i -E "layout|button|xbox|nintendo" > /data/local/tmp/props_before.txt
# toggle'ı değiştir
getprop | grep -i -E "layout|button|xbox|nintendo" > /data/local/tmp/props_after.txt
diff /data/local/tmp/props_before.txt /data/local/tmp/props_after.txt
```

### 23.3 Uygulama — Bulunan Anahtarı Kendi UI'ımızdan Yazma

Hangi anahtar/mekanizma olduğu bulunduktan sonra (örneğin bir `Settings.System`
ya da `Settings.Secure` anahtarıysa):
```kotlin
object ButtonLayoutController {
    private const val KEY = "button_layout_style" // 23.2'de bulunan gerçek adla değiştirilecek

    fun setXboxLayout(context: Context) =
        Settings.System.putInt(context.contentResolver, KEY, 0)

    fun setNintendoLayout(context: Context) =
        Settings.System.putInt(context.contentResolver, KEY, 1)
}
```
Eğer bir `system property` ise, root ile `setprop` kullanılır:
```kotlin
Shell.cmd("setprop persist.rp.button_layout nintendo").exec()
```
(gerçek property adı da 23.2'de bulunanla değiştirilmeli)

### 23.4 Quick Settings Tile Olarak Sun

Fan/CPU tile'larıyla aynı desende, tek dokunuşla Xbox ↔ Nintendo arası
geçiş yapan bir tile — kullanıcının Ayarlar'a girmeden hızlıca değiştirmesini
sağlar, ki zaten tüm bu projenin amacı bu (dağınık ayarları tek yerden hızlı
erişilebilir kılmak).

---

## 24. Analog Stick Deadzone Kalibrasyonu — Mevcut Aracı Tetikleme

### 24.1 Yaklaşım

Tıpkı Bölüm 23'teki gibi: bu özellik zaten sistemde var (muhtemelen
`com.rp.mapping` veya `RPSettings` içinde bir kalibrasyon ekranı/aktivitesi
olarak). Kendi deadzone algoritmamızı yazmak yerine, **var olan aktiviteyi
doğrudan başlatan bir kısayol** eklemek en pratik ve en güvenilir çözüm —
kalibrasyon donanıma özel hassas bir işlem olduğu için mevcut, test edilmiş
aracı kullanmak yeniden yazmaktan daha güvenli.

### 24.2 Keşif Adımları

**1. Kalibrasyon aktivitesini bul** — `com.rp.mapping` ve `com.rp.settings`
paketlerinin aktivite listesini çıkar:
```bash
dumpsys package com.rp.mapping | grep -A2 "Activity Resolver Table" 
pm dump com.rp.mapping | grep -i "Activity"
```
ya da daha basit, tüm activity'leri isim bazlı ara:
```bash
pm dump com.rp.mapping | grep -i -E "calibrat|deadzone|stick"
pm dump com.rp.settings | grep -i -E "calibrat|deadzone|stick"
```
(RP6'nın "RsMapping" paketinde "calibration" veya "deadzone" geçen bir
Activity adı arıyoruz, örn. `com.ro.mapping.activity.StickCalibrationActivity`
gibi bir şey bulunması bekleniyor.)

### 24.3 Tetikleme — Explicit Intent ile

Aktivite adı bulunduktan sonra, kendi uygulamamızdan doğrudan başlatılır:
```kotlin
fun launchDeadzoneCalibration(context: Context) {
    val intent = Intent().apply {
        // Paket/aktivite adları 24.2'de bulunan gerçek değerlerle değiştirilecek
        setClassName("com.rp.mapping", "com.ro.mapping.activity.StickCalibrationActivity")
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Aktivite bulunamadı/exported değil — 24.4'teki yedek plana geç
    }
}
```

### 24.4 Olası Engel ve Yedek Plan

Aktivite `android:exported="false"` olarak tanımlanmışsa (dışarıdan
başlatılamaz), bu doğrudan intent yöntemi çalışmaz. Bu durumda:
- **Yedek 1:** Kullanıcıyı doğrudan `com.rp.mapping` uygulamasının kendi ana
  ekranına yönlendiren bir intent dene (`packageManager.getLaunchIntentForPackage("com.rp.mapping")`)
  — tam kalibrasyon ekranına değil ama en azından doğru uygulamaya götürür,
  kullanıcı oradan 1-2 dokunuşla ilerler.
- **Yedek 2:** Root ile `am start -n com.rp.mapping/.activity.StickCalibrationActivity`
  komutunu Shell üzerinden çalıştırmak — `exported=false` korumasını root
  zaten aşabilir (adb/root shell, uygulama izin kontrolüne tabi değildir).

### 24.5 UI

Ana Dashboard'da "Deadzone Kalibrasyonu" butonu — dokunulunca 24.3/24.4'teki
yöntemle mevcut sistem aracını açar. Kendi kalibrasyon UI'ımızı **yazmıyoruz**,
sadece var olana kısayol sağlıyoruz.

---

## 25. Güncellenmiş Yol Haritası (v3)

1. **v0.1 – v0.9** — Bölüm 18'deki sıra aynen geçerli.
2. **v1.0** — Oyun içi overlay (Bölüm 19) + Oyun bazlı otomatik profil
   (Bölüm 20) — ikisi birlikte, overlay zaten profil değişimini de
   gösterebileceği için mantıklı bir çift.
3. **v1.1** — Titreşim yoğunluğu (Bölüm 21) — önce cihazda keşif yapılmalı,
   sonuca göre kapsam netleşir.
4. **v1.2** — Xbox/Nintendo buton düzeni (Bölüm 23) + Deadzone kalibrasyon
   kısayolu (Bölüm 24) — ikisi de "mevcut ayarı tetikleme" prensibiyle
   çalıştığı için birlikte, düşük riskli bir sürümde yapılabilir.
5. **v1.3** — M1/M2 makro→düz input dönüşümü (Bölüm 22) — Magisk modülü
   gerektirdiği için en karmaşık/en riskli parça, en sona bırakıldı.
6. **v2.0** — UI cilası, tüm özelliklerin tek Dashboard'da toplanması,
   ayarların yedeklenmesi/dışa aktarılması.
