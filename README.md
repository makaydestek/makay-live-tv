# Makay Live TV

Dahili tuner (DVB-T/C/S) destekli, uzaktan kumanda için optimize edilmiş Android TV canlı yayın uygulaması.

`D:\MakayCleaner` projesiyle **aynı mimari ve yayın hattını** kullanır: Kotlin + Jetpack Compose, tek sürüm kaynağı `VERSION.txt`, `APK/` klasörüne imzalı çıktı + SHA-256.

---

## 1. Referans uygulama analizi (`Live TV V.1.4.2.0.apk`)

Analiz edilen APK: `com.nes.seilivetv` (Live TV, v1.4.2.0)

| Özellik | Değer |
|---|---|
| Paket | `com.nes.seilivetv` |
| `sharedUserId` | `android.uid.system` ← **platform imzalı** |
| minSdk / targetSdk | 29 / 33 |
| Boyut | ~30.8 MB, 3 dex |
| UI | XML layout + `leanback`, `constraintlayout`, `glide` |
| Ağ | Firebase Analytics + Crashlytics |

### Kritik mimari bulgu

Referans uygulama **sistem uygulamasıdır** ve `android.uid.system` olarak çalışır. Bu sayede
`com.droidlogic.dtvkit.IDTVKit` Binder servisine (`request(json)`), `DVB_DEVICE` iznine ve
`com.google.android.tv.dtvprovider` sağlayıcısına doğrudan erişir.

**Üçüncü taraf uygulamalar bu yolu kullanamaz.** Desteklenen ve taşınabilir yol
**Tv Input Framework (TIF)**'tir:

```
  DVB donanımı
       │
       ▼
  DTV TvInputService  (org.dtvkit.inputsource — DroidLogic/Amlogic, Homatics)
       │  tuner'ı sahiplenir, kanal taraması yapar
       ▼
  TvProvider (sistem DB)  ← TvContract.Channels / Programs
       │
       ▼
  Makay Live TV  ── TvView.tune(inputId, channelUri) ──►  yayın
```

Referans uygulamanın kendi oynatma kodu (`NesTvView.tuneChannel`) da tam olarak bunu yapar:

```java
customerTvView.tune(channel.getInputId(), TvContract.buildChannelUri(channel.getId()), bundle);
```

Yani **dahili tuner desteği = TIF entegrasyonu**. Biz de aynı yolu izliyoruz; fark,
tarama sihirbazını kendimiz yazmak yerine cihazın kendi DTV girdisini kullanmamız
(kanal listesi cihazın DTV uygulamasıyla paylaşılır).

### Tespit edilen özellik envanteri (396 string kaynağı)

**Oynatma / tuner**
- Kanal değiştirme (zap), numaradan kanal, kanal listesi, favori grupları
- Aspect ratio, ses modu, çoklu ses (MPEG-H / Dolby / 5.1 / 7.1), altyazı, teletext
- Zayıf sinyal / şifreli kanal / ebeveyn kilidi bindirmeleri
- PIP, hızlı kanal önizleme (Last Picture), kanal logoları

**EPG**
- 5 gün ileri (`DAYS_FORWARD_MILLIS`), gün bazlı gezinme, kanal grubu filtresi
- Program detayı, süre/saat, tür (genre), dil, yaş sınırı

**Kayıt (PVR)**
- Anlık kayıt, zamanlayıcı (Timer) — Watch / Record, haftalık tekrar
- Kayıt listesi, oynatma, USB formatlama, arka plan kayıt
- Time-shift (duraklat/geri sar)

**Tarama / kurulum**
- İlk kurulum sihirbazı, bölge seçimi, sinyal türü (DVB-T/T2, C, S/S2)
- Uydu listesi, transponder ekleme/silme, DiSEqC 1.0/1.1/1.2, UniCable, LNB özel bantlar
- TKGS (Türksat) operatör modu, LCN, tarama sonucu

**Ebeveyn kontrolü**
- PIN, kanal kilidi, yaş derecelendirme sistemleri (TR/DE/BR/KR/US), menü kilidi

**Diğer**
- HBBTV, MHEG, MPEG-H Audio, kanal sıralama/taşıma, kanal silme, yedekleme (USB)
- Fabrika ayarları, debug paneli, sinyal bilgisi (güç/kalite/BER)

### Faz 1'de uygulananlar ✅

- TIF girdi keşfi (`TvInputDiscovery`) — DroidLogic > Google DTV > diğer önceliği
- Kanal listesi okuma + `ContentObserver` ile canlı izleme (`ChannelRepository`)
- `TvView` ile dahili tuner oynatma + otomatik tune tekrar denemesi (`TunerTvView`)
- Kanal değiştirme, numaradan kanal, favoriler, arama, filtre, sıralama
- EPG "şimdi / sonraki" + ilerleme çubuğu + TV Rehberi ekranı
- Bilgi çubuğu, ilk kurulum ekranı, ayarlar
- Uzaktan kumanda tuş eşlemesi (DPAD, OK, MENU, INFO, GUIDE, 0-9)

### Faz 2–6 uygulama durumu

| Faz | Kapsam |
|---|---|
| 1 | Açılışta son kanala dönme (yeniden tarama dayanımlı), kanal arama/zap, tune zaman aşımı ve anlaşılır oynatma hataları ✅ |
| 2 | 5 günlük EPG, program/kanal arama, tek seferlik/haftalık hatırlatma ve zamanlanmış kayıt; zamanlayıcı listesi/iptali ✅ |
| 3 | Kayıt listesi arama/sıralama, yenileme/silme, uygulama depolama göstergesi; `TvRecordingClient` anlık/zamanlanmış kayıt ve TIF time-shift ✅* |
| 4 | Favori/kanal adı/gizleme/özel sıra/son izlenen, PIN ve yaş kontrolü; tema ve yazı boyutu seçenekleri ✅ |
| 5 | Canlı kanal listesinde şimdi/sırada ve ilerleme, favori/son izlenen kestirmeleri, EPG program keşfi ve kanal numarası/kumanda kısayolları ✅ |
| 6 | Kopyalanabilir cihaz/tuner tanılama raporu, Türkçe/İngilizce, Homatics/DTVKit ayarına geçiş ve ses/altyazı seçimi ✅ |

\* Kayıt/time-shift ve tuner hata nedenleri cihaz girdisinin yeteneklerine bağlıdır. Depolama göstergesi uygulamanın veri alanını gösterir; DTVKit'in kayıt hedefini temsil ettiği garanti edilmez.

**Cihaz sınırları:** kanal gizleme/sıralama/ad düzenleme Makay Live TV içinde tutulur; üçüncü taraf uygulama, cihazın ortak TvProvider kanal kayıtlarını güvenli biçimde taşıyamaz veya silemez. Uydu, DiSEqC ve TKGS ayarlarını Homatics/DTVKit'in kendi ayar ekranına yönlendiriyoruz. Gerçek kayıt ve time-shift davranışı, bağlı tuner girişinin sunduğu yeteneklere bağlıdır. EPG yaş derecesi okunamayan programlar için yaş filtresi uygulanamaz; sistem düzeyindeki içerik kilidini DTV girdisi yönetir.

---

## 2. Mimari

```
com.makay.livetv
├── MainActivity.kt              Tek aktivite, Compose kökü
├── MakayLiveTvApplication.kt
├── domain/model/                Channel, Program, ChannelType, TvInputEntry
├── tuner/
│   ├── TvInputDiscovery.kt      TvInputService keşfi + yetenek tespiti
│   ├── ChannelRepository.kt     TvProvider okuma + ContentObserver
│   └── ChannelPreferences.kt    Favori / son izlenen / yeniden adlandırma
├── epg/EpgRepository.kt         TvContract.Programs sorguları
├── player/TunerTvView.kt        TvView + PlaybackState + tune tekrar denemesi
├── data/AppSettingsStore.kt     SharedPreferences + StateFlow
├── ui/
│   ├── AppNavigator.kt          Tek back stack
│   ├── AppRoot.kt               Ekran yönlendirme
│   ├── LiveTvScreen.kt          Yayın + bilgi barı + tuş eşlemesi
│   ├── ChannelListScreen.kt     Kanal listesi (filtre/arama/favori)
│   ├── EpgScreen.kt             TV Rehberi
│   ├── SettingsScreen.kt
│   ├── FirstLaunchScreen.kt
│   ├── TvClickable.kt           DPAD merkez tuşu ile tıklama
│   └── TvFocus.kt               Odak halkası / ölçek
├── util/                        SafeLog, TimeFormat
└── receiver/TvAlarmReceiver.kt  Hatırlatma ve zamanlanmış kayıt alarmları
```

### Tasarım kararları

1. **TvView her zaman altta yaşar.** Kanal listesi / EPG / Ayarlar tam ekran
   bindirme olarak açılır; böylece ekran değişiminde yayın kesilmez.
2. **Tune tekrar denemesi onay tabanlıdır.** `TvView` oturum açarken ilk `tune`
   çağrısını yutabilir. İlk oynatmada `onVideoAvailable` gelmezse bir kez yeniden
   denenir; onay geldikten sonra **dokunulmaz** (yayın kesilmez).
3. **Favoriler TvProvider'a yazılmaz.** `TvContract.Channels`'ta favori kolonu
   yoktur; cihazın kendi DTV listesini bozmamak için yerel `DataStore`'da tutulur.
4. **Kanal anahtarı `inputId + displayNumber`.** Yeniden tarama kanal ID'lerini
   değiştirir; numara kalır.
5. **`ContentObserver` ile canlı senkron.** DTV girdisi tarama bitirdiğinde
   liste kendiliğinden güncellenir.

---

## 3. Build

```powershell
# Debug APK
.\gradlew.bat :app:assembleDebug

# Testler
.\gradlew.bat :app:testDebugUnitTest

# İmzalı release + APK/ klasörüne SHA-256
.\gradlew.bat :app:assembleRelease

# Sürümü artır, imzalı APK üret, doğrula ve GitHub Release'e yükle
APK-Hazirla.bat
```

Çıktı: `APK/MakayLiveTV_v1.0.0.apk` (~1.3 MB) + `.sha256` + `-mapping.txt`

Sürüm: `VERSION.txt` (tek kaynak) → `versionName` + `versionCode` (`major*1e6 + minor*1e3 + patch`).

İmza: `keystore/makay-livetv-release.jks` (kimlik bilgileri `keystore/CREDENTIALS.txt`,
git'e girmez) — `local.properties` üzerinden okunur.

`APK-Hazirla.bat`, Makay Cleaner ile aynı altı adımlı yayın düzenini izler: bekleyen
kaynak değişikliklerini commit eder, sürümü artırıp doğrular, imzalı APK/SHA/mapping
üretir, teslimatı doğrular, `VERSION.txt` değişikliğini commit edip `main` dalına iter,
son olarak GitHub Release'i oluşturur/günceller ve eski sürüm release/etiketlerini
temizler. Yalnızca son sürüm bırakılır. GitHub CLI (`gh`) kurulu olmalı ve `repo`
yazma yetkisiyle oturum açmalıdır; ilk Git kurulumu `origin` uzak deposu ve `main`
dalıyla eşleşmiş olmalıdır. İmza anahtarı ve `local.properties` Git dışında tutulur.

---

## 4. Cihaz notları

- **Homatics / DroidLogic (Amlogic):** DTV girdisi `org.dtvkit.inputsource` hazır gelir.
  Uygulama bu girdiyi otomatik seçer. Kanallar cihazın kendi Live TV uygulamasındaki
  listeyle aynıdır.
- **Kanal yoksa:** Cihazın DTV girişinden (Fabrika/İlk kurulum → Kanal tarama)
  tarama yapılmalıdır. Uygulama "Kanal listesi boş" ekranında bunu bildirir.
- **`READ_TV_LISTINGS`:** Android 11+ bazı cihazlarda kanal sorgusu için gerekir;
  reddedilirse boş liste döner (uygulama çökmez, uyarı gösterir).
