# VNext Screens

**[Русский](#русский) · [English](#english)**

Клиентский мод Fabric для Minecraft **26.2**: видео с картинкой и звуком прямо на экранах в мире сервера VNext.
A client-side Fabric mod for Minecraft **26.2** that plays video with sound on in-world screens of the VNext server.

---

## Русский

### Что умеет

- **Видео на экранах в мире.** Сервер (плагин VNextScreens) сообщает, где стоит экран и что на нём играет. Мод сам загружает видео и рисует его на плоскости.
- **YouTube, Rutube и прямые ссылки.** YouTube разбирается прямо у игрока через [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), Rutube — через его API. Прямые ссылки на файлы и потоки открываются как есть.
- **До 720p 60 к/с.** Картинка и звук идут через ffmpeg, синхронизация по звуку, звук — через OpenAL самого Minecraft.
- **Звук в пространстве.** Громкость зависит от расстояния до экрана, есть простая панорама. Отдельный ползунок «Видео на экранах» во вкладке «Музыка и звуки».
- **Дорожка на языке игры**, если у видео есть несколько звуковых дорожек.
- **Меню управления.** Тем, у кого есть право на сервере, ПКМ по экрану открывает меню: очередь с превью, добавление ссылки с проверкой, перемотка ±10 с, пропуск и стоп.
- **Устойчивость к обрывам.** Сам переподключается, заново запрашивает ссылки и бесшовно перезапускает звук.

### Установка

1. Поставьте [Fabric Loader](https://fabricmc.net/use/) **0.19.0+** для Minecraft **26.2**.
2. Положите в папку `mods` [Fabric API](https://modrinth.com/mod/fabric-api) и `vnextscreens-x.y.z.jar` со страницы [Releases](../../releases).
3. Зайдите на сервер.

**ffmpeg.** На Linux мод берёт системный `ffmpeg`, а если его нет, скачивает статическую сборку. На Windows и macOS ffmpeg скачивается автоматически при первом запуске в `.minecraft/vnextscreens/ffmpeg`.

### Важно

- Мод только клиентский и работает **только вместе с серверным плагином VNextScreens** (канал `vnext:screen`). Без плагина он ничего не делает.
- Прямые эфиры и Twitch пока не поддерживаются. Видео 18+ и приватные видео YouTube не откроются.

### Сборка

Нужен JDK 25.

```bash
./gradlew build
# готовый jar: build/libs/vnextscreens-<версия>.jar
```

---

## English

### Features

- **Video on in-world screens.** The server (VNextScreens plugin) tells the client where each screen is and what it's playing. The mod fetches the video and renders it on the screen surface.
- **YouTube, Rutube and direct links.** YouTube is resolved on the player's machine with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), Rutube through its API. Direct file and stream URLs are opened as-is.
- **Up to 720p at 60 fps.** Video and audio are decoded by ffmpeg, synced to the audio clock, and played through Minecraft's own OpenAL.
- **Positional sound.** Volume falls off with distance, with simple panning. A dedicated "Video on screens" slider in the Music & Sounds options.
- **Audio track in your game language** when the video has several tracks.
- **Control menu.** Players with permission on the server can right-click a screen to open a menu: queue with thumbnails, add a link with validation, seek ±10 s, skip and stop.
- **Resilient playback.** Reconnects automatically, re-resolves links and restarts the audio seamlessly.

### Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.19.0+** for Minecraft **26.2**.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `vnextscreens-x.y.z.jar` from [Releases](../../releases) into your `mods` folder.
3. Join the server.

**ffmpeg.** On Linux the mod uses the system `ffmpeg`, or downloads a static build if it's missing. On Windows and macOS ffmpeg is downloaded automatically on first use into `.minecraft/vnextscreens/ffmpeg`.

### Notes

- This is a client-only mod and works **only together with the VNextScreens server plugin** (channel `vnext:screen`). Without it, the mod does nothing.
- Live streams and Twitch aren't supported yet. Age-restricted and private YouTube videos won't play.

### Building

Requires JDK 25.

```bash
./gradlew build
# output: build/libs/vnextscreens-<version>.jar
```

---

## Лицензия / License

[LGPL-3.0-only](LICENSE) (вместе с [GPL-3.0](COPYING)). Идея и часть подходов — из [Dream Displays](https://github.com/arnodoelinger/dreamdisplays). Внутри jar лежат NewPipeExtractor (GPL-3.0), nanojson, jsoup, protobuf-javalite и Rhino под их собственными лицензиями.

Licensed under [LGPL-3.0-only](LICENSE) (together with [GPL-3.0](COPYING)). Inspired by [Dream Displays](https://github.com/arnodoelinger/dreamdisplays). The jar bundles NewPipeExtractor (GPL-3.0), nanojson, jsoup, protobuf-javalite and Rhino under their respective licenses.
