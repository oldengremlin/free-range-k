# Contributing to free-range

Дякую за інтерес до проекту! Цей документ описує як налаштувати оточення для розробки та як зробити внесок.

## Вимоги до оточення

| Інструмент | Версія |
|------------|--------|
| JDK | 21+ (перевірено на 21 та 24) |
| Gradle | не потрібен — `./gradlew` (wrapper) завантажує сам |
| Kotlin | 2.0.x (завантажується Gradle автоматично) |

Перевірити:
```bash
java --version   # 21+
./gradlew --version
```

> **Важливо:** не запускай `gradle wrapper` вручну — він перезапише `gradle-wrapper.properties`
> версією твого локального Gradle. Wrapper вже є в репо і налаштований правильно.

## Структура проекту

```
free-range-k/
├── build.gradle.kts              головний build-скрипт
├── settings.gradle.kts           назва проекту
├── gradle/wrapper/               Gradle wrapper (комітити!)
├── src/
│   └── main/
│       ├── kotlin/net/ukrhub/noc/freerange/
│       │   ├── Main.kt           точка входу, picocli-команда
│       │   ├── AppConfig.kt      конфігурація (CLI > ENV > YAML > default)
│       │   ├── netconf/
│       │   │   └── NetconfClient.kt   JSch + NETCONF 1.0, XPath-парсинг
│       │   ├── subscribers/
│       │   │   ├── SubscriberSource.kt            інтерфейс
│       │   │   └── LocalCommandSubscriberSource.kt реалізація через shell
│       │   ├── vlan/
│       │   │   ├── VlanStatus.kt      enum статусів
│       │   │   └── VlanProcessor.kt   логіка розподілу VLAN
│       │   └── output/
│       │       ├── TextOutput.kt      combined ranges + ANSI
│       │       ├── TableOutput.kt     ASCII 41×100 таблиця
│       │       └── PngOutput.kt       Java AWT PNG
│       └── resources/
│           └── log4j2.xml        конфігурація логування
└── README.md
```

## Збірка і запуск

```bash
# Компіляція + fat JAR (за один крок)
./gradlew build
# → build/libs/free-range-1.0.0.jar

# Запуск (для розробки)
./gradlew run --args="router.example.com -u admin -p secret -d"

# Запуск fat JAR
java -jar build/libs/free-range-1.0.0.jar router.example.com -u admin -p secret

# Очистка
./gradlew clean

# Список задач
./gradlew tasks
```

## Як додати нове джерело абонентів (REST API приклад)

Поточна реалізація через shell-команду легко замінюється завдяки інтерфейсу `SubscriberSource`:

**1. Реалізуй інтерфейс:**

```kotlin
// src/main/kotlin/net/ukrhub/noc/freerange/subscribers/RestSubscriberSource.kt
package net.ukrhub.noc.freerange.subscribers

class RestSubscriberSource(
    private val apiUrl: String,
    private val apiToken: String
) : SubscriberSource {
    override fun getSubscribers(): String {
        // HTTP GET запит, повертає рядки в тому самому форматі:
        // dhcp_xxx_xe-0/0/2:100@router-hostname
        TODO("implement")
    }
}
```

**2. Підключи в `Main.kt`** (один рядок):

```kotlin
// замінити:
val subscriberSource = LocalCommandSubscriberSource(config.subscribersCommand)
// на:
val subscriberSource = RestSubscriberSource(config.apiUrl, config.apiToken)
```

Більше нічого чіпати не потрібно.

## Як додати новий формат виводу

1. Створи файл у `src/main/kotlin/net/ukrhub/noc/freerange/output/`
2. Реалізуй функцію або object із методом `print()`/`save()` — дивись `TextOutput.kt` як приклад
3. Додай новий `when`-блок у `Main.kt` (секція "Step 4: Process and output")
4. Додай відповідну CLI-опцію в `FreeRangeCommand` і ENV-змінну в `AppConfig`

## NETCONF і XPath

`NetconfClient` використовує **JSch** для SSH і стандартний `javax.xml` для DOM + XPath.

Якщо на твоїх пристроях інша структура XML (інший Junos, не Juniper) — XPath-вирази знаходяться в `NetconfClient.kt`, у методі `parseInterfaceVlanData()`. Вони namespace-unaware, тому префікси не потрібні.

Для відлагодження NETCONF-відповіді запусти з `-d` (дебаг) — XML буде виведений у stderr.

## Залежності

| Бібліотека | Версія | Призначення |
|------------|--------|-------------|
| `com.jcraft:jsch` | 0.1.55 | SSH-транспорт для NETCONF |
| `info.picocli:picocli` | 4.7.6 | CLI-парсинг |
| `org.yaml:snakeyaml` | 2.3 | YAML-конфіг |
| `log4j-api/core` | 2.24.3 | Логування |

XML і PNG — стандартна Java (без зовнішніх залежностей).

## Гілки і коміти

- Розробка ведеться у feature-гілках від `main`
- Назва гілки: `feature/назва-фічі` або `fix/опис-виправлення`
- Коміти — короткі, у теперішньому часі: `Add REST subscriber source`, `Fix XPath for demux units`
- Перед PR — переконайся що `./gradlew build` проходить без помилок

## Питання і пропозиції

Відкривай [Issue](https://github.com/oldengremlin/free-range-k/issues) — будь-які питання, баг-репорти або ідеї вітаються.
