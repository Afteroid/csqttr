# Сборка Android из исходников

Gooby включён в `rust-client/vendor/gooby`: отдельный процесс или скачивание
его исходников не нужны. Автор исходного проекта — [Endlad2/Gooby](https://github.com/Endlad2/Gooby).
Ревизия и изменения указаны в `rust-client/vendor/gooby/UPSTREAM.txt`;
лицензия Apache-2.0 и NOTICE сохранены. Стратегии описаны в `rust-client/GOOBY.txt`.

## Linux / WSL

Нужны JDK 21, Android SDK Platform 37 (пакет `platforms;android-37.0`), Build Tools 36.0.0, Linux NDK r28c,
Rust 1.97.1 или новее, cargo-ndk, CMake, Clang, pkg-config и компилятор C/C++.
Для 32-битного LuaJIT при сборке armeabi-v7a на Ubuntu x86_64 нужен gcc-multilib.
В WSL используйте Linux NDK, а не Windows NDK.

```bash
sudo apt-get update
sudo apt-get install build-essential gcc-multilib cmake clang pkg-config
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked

# Укажите собственные пути к установленным SDK, NDK и JDK.
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

bash rust-client/build_android_wsl.sh arm64-v8a armeabi-v7a x86_64
bash gradlew :app:testDebugUnitTest :app:assembleDebug \
  -Pcsqtt.abis=arm64-v8a,armeabi-v7a,x86_64 \
  -Pcsqtt.compactApk=true \
  -Pcsqtt.debugApplicationIdSuffix=.forktest \
  -Pcsqtt.debugAppLabel=CSQTTR \
  -Pcsqtt.debugVersionNameSuffix=-debug.47
```

Результат — три APK по ABI и universal в `app/build/outputs/apk/debug/`.
Для одного ABI передайте его и Rust-скрипту, и `csqtt.abis`.
Обычный запуск Gradle не собирает Rust: сначала обязательно выполните скрипт ядра.
Нативные бинарники, SDK, `local.properties` и ключ подписи не хранятся в Git.
Локальный манифест происхождения ядра можно обновить через
`scripts/native_client_provenance.ps1 -Mode Write -Producer rust-client/build_android_wsl.sh`.
Его хеши относятся к конкретной сборке, а не к любой пересборке тех же исходников.

Debug APK подписывается локальным debug-ключом. Чтобы обновить уже установленное
приложение с сохранением данных, нужны тот же applicationId, тот же ключ подписи
и подходящий versionCode. Чужая самостоятельная сборка не обязана иметь нашу подпись.
Для release задайте KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS и KEY_PASSWORD
через переменные окружения или локальные Gradle properties, затем assembleRelease.

## Серверные ядра и собственный репозиторий релизов

Серверные исполняемые файлы исключены из APK. При деплое приложение определяет
архитектуру VPS, загружает ядро по HTTPS, проверяет размер и SHA-256, затем
сохраняет его в приватный кэш. Кэш повторно проверяется перед использованием.
Ошибка загрузки или проверки останавливает операцию до изменения сервера.
Для первой загрузки требуется доступ к GitHub Releases; после успешного
кэширования то же ядро можно использовать без повторной загрузки.

Ссылки, размеры и SHA-256 закреплены в `app/src/main/assets/server-downloads.json`.
Сейчас это публичный релиз Fikisq/csqttr `server-core-debug46-20261104`.
Мейнтейнеры Afteroid/csqttr или другого форка могут размещать ядра у себя:

1. Соберите `rust-server` для Linux amd64, arm64 и armv7 либо возьмите проверенные
   совместимые бинарники из указанного релиза.
2. Опубликуйте `csqtt-linux-amd64`, `csqtt-linux-arm64`, `csqtt-linux-armv7`
   в публичном версионном релизе своего репозитория.
3. Замените URL в `server-downloads.json`. Для неизменённых файлов оставьте
   размеры и SHA-256; для новых файлов пересчитайте оба значения.
4. Проверьте скачивание всех трёх URL без авторизации и соберите APK.

Не используйте изменяемую ссылку `latest` и не помещайте GitHub-токен в APK.
Изменение релиза или файла манифеста в GitHub не меняет адреса в уже выпущенном APK:
для перехода на другой источник необходима новая сборка приложения.
Версия ядра сервера не должна обновляться без проверки совместимости с клиентом.
