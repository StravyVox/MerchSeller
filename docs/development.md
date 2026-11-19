# Сборка и публикация MerchSeller

## Сборка

Установите JDK 17 и Android SDK Platform 36. Выполните команду в корне проекта.

В Windows:

```powershell
.\gradlew.bat assembleDebug
```

В Linux или macOS:

```bash
./gradlew assembleDebug
```

Готовый APK находится в `app/build/outputs/apk/debug/app-debug.apk`. Чтобы установить и запустить его на подключённом устройстве:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.merchseller/.MainActivity
```

Для локальной сборки релиза доступны параметры Gradle `releaseVersion`, `releaseVersionCode`, `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias` и `releaseKeyPassword`. Без параметров подписи `assembleRelease` создаёт неподписанный APK.

## Публикация релиза

1. Создайте тег вида `v1.2.3` на коммите из ветки `main` и отправьте тег в GitHub.
2. Откройте [Publish Android release](https://github.com/StravyVox/MerchSeller/actions/workflows/publish-release.yml), нажмите **Run workflow**, выберите ветку `main` и укажите тег.

GitHub Actions вычислит версию по тегу, соберёт и подпишет APK, затем опубликует его в GitHub Releases.

Для подписи настройте окружение `release` в GitHub Actions и добавьте секреты.

| Секрет | Значение |
| --- | --- |
| `MERCHSELLER_SIGNING_KEY_BASE64` | Хранилище ключа подписи в Base64 |
| `MERCHSELLER_SIGNING_STORE_PASSWORD` | Пароль хранилища |
| `MERCHSELLER_SIGNING_KEY_ALIAS` | Псевдоним ключа |
| `MERCHSELLER_SIGNING_KEY_PASSWORD` | Пароль ключа |

Настройте подтверждение выпуска для окружения `release`. Храните закрытый ключ вне репозитория и используйте его для всех релизов, чтобы Android мог установить обновление поверх предыдущей версии.
