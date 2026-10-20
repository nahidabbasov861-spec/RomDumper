# ROM Dumper (sadə, pulsuz, open-source)

Android OTA yeniləmə fayllarının (`payload.bin`) içindəki bölmə şəkillərini
(`boot.img`, `system.img`, və s.) çıxaran sadə, tamamilə pulsuz Android
tətbiqi. Kod sıfırdan, Google-ın açıq (Apache-2.0) `update_engine` payload
formatının ictimai spesifikasiyası əsasında yazılıb — heç bir başqa
tətbiqin kodu istifadə olunmayıb və ya kopyalanmayıb.

## Funksiyalar

- OTA `.zip` və ya təkbaşına `payload.bin` faylı seçmək
- Bölmələrin siyahısını və ölçülərini göstərmək
- Seçilmiş bölmələri `.img` fayllarına çıxartmaq
  (dəstəklənən əməliyyatlar: `REPLACE`, `REPLACE_BZ`, `REPLACE_XZ`, `ZERO`)
- (İstəyə bağlı, qabaqcıl) root olan cihazlarda çıxarılmış `.img`-i birbaşa
  uyğun bölməyə `dd` ilə yazmaq

## Məhdudiyyətlər

- **Yalnız tam (full) OTA payload** dəstəklənir. Artımlı/delta paketlər
  (`SOURCE_COPY`, `SOURCE_BSDIFF`, `PUFFDIFF`) dəstəklənmir, çünki onlar
  cihazda artıq olan köhnə bölməni tələb edir.
- Flaş funksiyası yalnız **root** olan cihazlarda işləyir və cihazınızın
  **öz** bölmə adlarına (`/dev/block/by-name/...`) yazır. Heç bir
  təhlükəsizlik və ya kilid mexanizmini yan keçmir.
- Yanlış bölməni flaş etmək cihazı işləməz vəziyyətə sala bilər — ehtiyat
  nüsxə olmadan flaş etməyin.

## Necə qurulur (build)

Bu kontener mühitində Android SDK/Gradle/internet olmadığı üçün burada
hazır `.apk` compile edilə bilmir. Layihəni qurmaq üçün:

1. [Android Studio](https://developer.android.com/studio) yükləyin (pulsuz).
2. `File → Open` ilə bu qovluğu (`RomDumper/`) açın.
3. Gradle sync avtomatik gedəcək (ilk dəfə internet lazımdır ki,
   kitabxanalar Maven Central-dan yüklənsin).
4. `Run ▶` düyməsi ilə qoşulu cihaza birbaşa qura bilərsiniz, və ya
   `Build → Build Bundle(s) / APK(s) → Build APK(s)` ilə `.apk` alın.

## Fayl strukturu

```
RomDumper/
├── app/src/main/java/com/example/romdumper/
│   ├── ProtoReader.kt     – ümumi protobuf wire-format oxuyucu
│   ├── PayloadParser.kt   – payload.bin başlığı + manifest + çıxarma
│   ├── FlashHelper.kt     – root ilə (istəyə bağlı) dd flaş
│   └── MainActivity.kt    – UI
└── app/src/main/res/      – layout və strings
```
