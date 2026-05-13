# Contributing to free-range

Дякую за інтерес до проекту! Цей документ описує як налаштувати оточення для розробки та як зробити внесок.

## Вимоги до оточення

| Інструмент | Версія |
|------------|--------|
| JDK | 21+ (перевірено на 21, 24, 25) |
| Gradle | не потрібен — `./gradlew` (wrapper) завантажує сам |
| Kotlin | 2.1.x (завантажується Gradle автоматично) |
| Docker | будь-яка актуальна версія (опційно) |

Перевірити:
```bash
java --version   # 21+
./gradlew --version
```

> **Важливо:** не запускай `gradle wrapper` вручну — він перезапише `gradle-wrapper.properties`
> версією твого локального Gradle. Wrapper вже є в репо і налаштований правильно.

## NetBeans

Проект налаштовано для роботи в NetBeans "з коробки". `gradle.properties` містить підказку:

```properties
netbeans.hint.jdkPlatform=Oracle_OpenJDK_21.0.2_13
```

Вона каже NetBeans використовувати Java 21 для запуску Gradle (Gradle 9.x підтримує Java 21–25,
але сам процес Gradle daemon рекомендується запускати на LTS-версії).

**Якщо у тебе інша версія JDK 21/24:** `Tools → Java Platforms → Add Platform`, після чого
`Project Properties → Build → Gradle Execution → Java Runtime` → вибрати потрібний JDK.
NetBeans автоматично оновить `netbeans.hint.jdkPlatform` у `gradle.properties` — закомітти зміну.

## Структура проекту

```
free-range-k/
├── build.gradle.kts              головний build-скрипт
├── settings.gradle.kts           назва проекту
├── gradle/wrapper/               Gradle wrapper (комітити!)
├── Dockerfile                    3-stage build: JDK → JRE → nginx
├── bin/
│   └── free-range.sh             цикл оновлення (while true; sleep 3600)
├── docker-entrypoint.d/
│   └── 40-free-range.sh          запуск колектора через nginx entrypoint
└── src/
    └── main/
        ├── kotlin/net/ukrhub/noc/freerange/
        │   ├── Main.kt           точка входу, picocli-команда
        │   ├── AppConfig.kt      конфігурація (CLI > ENV > YAML > default)
        │   ├── netconf/
        │   │   └── NetconfClient.kt      JSch + NETCONF 1.0, XPath-парсинг
        │   ├── subscribers/
        │   │   ├── SubscriberSource.kt              інтерфейс
        │   │   ├── MssqlSubscriberSource.kt         JDBC → MS SQL (jTDS)
        │   │   └── LocalCommandSubscriberSource.kt  shell-команда (fallback)
        │   ├── vlan/
        │   │   ├── VlanStatus.kt      enum статусів
        │   │   └── VlanProcessor.kt   логіка розподілу VLAN
        │   └── output/
        │       ├── TextOutput.kt      combined ranges + ANSI
        │       ├── TableOutput.kt     ASCII 41×100 таблиця
        │       ├── PngOutput.kt       Java AWT PNG
        │       └── WebOutput.kt       HTML-дашборд із табами
        └── resources/
            └── log4j2.xml        конфігурація логування
```

## Збірка і запуск

```bash
# Компіляція + fat JAR (за один крок)
./gradlew jar
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

## Docker

```bash
# Збірка образу
docker build -t free-range .

# Запуск із MSSQL-джерелом
docker run -d --name vlan \
  -e FREE_RANGE_HOST=r1,r2,r3 \
  -e FREE_RANGE_SUFFIX=ukrhub.net \
  -e FREE_RANGE_USERNAME=admin \
  -e FREE_RANGE_PASSWORD=secret \
  -e FREE_RANGE_TABLE_PNG=/usr/share/nginx/html \
  -e FREE_RANGE_WEB=1 \
  -e FREE_RANGE_ACC_SERVER=10.100.1.59 \
  -e FREE_RANGE_ACC_USER=nocc \
  -e FREE_RANGE_ACC_PASSWORD=secret \
  -p 8080:80 \
  free-range
```

3-stage Dockerfile: `eclipse-temurin:21-jdk` (Gradle-збірка) → `eclipse-temurin:21-jre-noble`
(JRE provider) → `nginx:mainline` (runtime). Колектор стартує через `docker-entrypoint.d/` і
оновлює PNG + `index.html` щогодини у фоні, поки nginx обслуговує запити.

## Як додати нове джерело абонентів

Завдяки інтерфейсу `SubscriberSource` це зводиться до двох кроків.

**1. Реалізуй інтерфейс:**

```kotlin
// src/main/kotlin/net/ukrhub/noc/freerange/subscribers/RestSubscriberSource.kt
package net.ukrhub.noc.freerange.subscribers

class RestSubscriberSource(
    private val apiUrl: String,
    private val apiToken: String
) : SubscriberSource {
    override fun getSubscribers(): String {
        // HTTP GET, повертає рядки у форматі першого токена:
        // dhcp_xxx_xe-0/0/2:100@router-hostname
        TODO("implement")
    }
}
```

**2. Підключи в `Main.kt`** у методі `fetchSubscribers()` — додай ще одну гілку вибору джерела
за аналогією з існуючим `MssqlSubscriberSource` / `LocalCommandSubscriberSource`.

## Як додати новий формат виводу

1. Створи файл у `src/main/kotlin/net/ukrhub/noc/freerange/output/`
2. Реалізуй object або клас із методом `print()`/`save()` — дивись `TextOutput.kt` як приклад
3. Додай новий `when`-блок у `processHost()` або `processHostForWeb()` в `Main.kt`
4. Додай відповідну CLI-опцію в `FreeRangeCommand` і ENV-змінну в `AppConfig`

## NETCONF і XPath

`NetconfClient` використовує **JSch** для SSH і стандартний `javax.xml` для DOM + XPath.

Якщо на твоїх пристроях інша структура XML — XPath-вирази знаходяться в `NetconfClient.kt`,
у методі `parseInterfaceVlanData()`. Вони namespace-unaware, тому префікси не потрібні.

Для відлагодження NETCONF-відповіді запусти з `-d` — XML буде виведений у stderr.

## Залежності

| Бібліотека | Версія | Призначення |
|------------|--------|-------------|
| `com.jcraft:jsch` | 0.1.55 | SSH-транспорт для NETCONF |
| `net.sourceforge.jtds:jtds` | 1.3.1 | JDBC для MS SQL Server (TDS 8.0, без SSL) |
| `info.picocli:picocli` | 4.7.6 | CLI-парсинг |
| `org.yaml:snakeyaml` | 2.3 | YAML-конфіг |
| `log4j-api/core/slf4j2-impl` | 2.24.3 | Логування |

XML і PNG — стандартна Java (без зовнішніх залежностей).

## Гілки і коміти

- Розробка ведеться у feature-гілках від `main`
- Назва гілки: `feature/назва-фічі` або `fix/опис-виправлення`
- Коміти — короткі, у теперішньому часі: `Add REST subscriber source`, `Fix XPath for demux units`
- Перед PR — переконайся що `./gradlew jar` проходить без помилок

## Питання і пропозиції

Відкривай [Issue](https://github.com/oldengremlin/free-range-k/issues) — будь-які питання, баг-репорти або ідеї вітаються.
