# free-range

Аналіз розподілу VLAN на мережевих пристроях Juniper через **NETCONF + XPath**.

Kotlin-порт оригінального [free-range](https://github.com/oldengremlin/free-range) (Ruby).

## Можливості

- Підключення до Junos через **NETCONF** (RFC 6241, framing 1.0) — без sshpass і shell-команд
- XPath-парсинг XML-конфігурації інтерфейсів прямо з `<running>` конфігурації
- Аналіз розподілу VLAN із шістьма статусами
- Вивід: текстовий, кольорова ASCII-таблиця або PNG-зображення (Java AWT)
- Конфігурація: CLI-аргументи → змінні оточення → YAML-файл → дефолти
- Готовий до запуску у Docker

## Статуси VLAN

| Символ | Колір | Значення |
|--------|-------|----------|
| `f` | зелений | **free** — у діапазоні dynamic-profile, абонента немає |
| `b` | жовтий | **busy** — у діапазоні, абонент активний |
| `e` | червоний | **error** — абонент є, але VLAN поза будь-яким діапазоном |
| `c` | фіолетовий | **configured** — є `vlan-id` і він у діапазоні |
| `a` | синій | **another** — є `vlan-id`, але поза діапазоном |
| `u` | сірий | **unused** — поза всіма діапазонами, абонента немає |

## Збірка

Потрібно: Java 21+ (перевірено на 21 та 24), Gradle 8+.

```bash
git clone https://github.com/oldengremlin/free-range-k.git
cd free-range-k

# Збірка + тести (wrapper вже є в репо — не запускай gradle wrapper!)
./gradlew build

# Fat JAR (все в одному файлі)
./gradlew shadowJar
# → build/libs/free-range-1.0.0.jar
```

> **Увага:** не запускай `gradle wrapper` вручну — він перезапише `gradle-wrapper.properties`
> версією твого локального Gradle, що може зламати збірку. Wrapper вже налаштований у репо.

## Запуск

```bash
# Через Gradle (для розробки)
./gradlew run --args="router.example.com -u admin -p secret"

# Через fat JAR (для production)
java -jar build/libs/free-range-1.0.0.jar router.example.com -u admin -p secret
```

## Опції

```
Використання: free-range [<host>] [options]

Параметри:
  [<host>]                      Hostname або IP-адреса роутера (positional або -H)

Опції:
  -h, --help                    Показати довідку
  -V, --version                 Показати версію
  -H, --host HOST               Hostname або IP-адреса роутера (альтернатива positional)
  -u, --username USERNAME       Ім'я користувача для NETCONF/SSH
  -p, --password PASSWORD       Пароль для NETCONF/SSH
  -n, --no-color                Вимкнути кольоровий вивід
  -d, --debug                   Увімкнути дебаг-режим
  -t, --table                   Вивести ASCII-таблицю розподілу VLAN
  -g, --table-png PATH          Зберегти таблицю як PNG у вказану директорію
  -i, --interface INTERFACE     Ім'я інтерфейсу (напр. xe-0/0/2, ps0, ae1) або 'all'
  -c, --config CONFIG_FILE      Шлях до YAML-конфігурації
```

Host вказується будь-яким із трьох способів (пріоритет: `-H` = positional > `FREE_RANGE_HOST`):
```bash
free-range router.example.com            # positional
free-range -H router.example.com         # опція (зручно для Docker/env-only запуску)
FREE_RANGE_HOST=router.example.com ...   # змінна оточення
```

## Змінні оточення

Всі CLI-опції мають відповідні змінні оточення (зручно для Docker):

| Змінна | CLI-аналог | Дефолт |
|--------|------------|--------|
| `FREE_RANGE_HOST` | `<host>` або `-H` | — |
| `FREE_RANGE_USERNAME` або `WHOAMI` | `-u` | — |
| `FREE_RANGE_PASSWORD` або `WHATISMYPASSWD` | `-p` | — |
| `FREE_RANGE_PORT` | — | `22` |
| `FREE_RANGE_NO_COLOR` | `-n` | вимк. |
| `FREE_RANGE_DEBUG` | `-d` | вимк. |
| `FREE_RANGE_TABLE` | `-t` | вимк. |
| `FREE_RANGE_TABLE_PNG` | `-g` | — |
| `FREE_RANGE_INTERFACE` | `-i` | — |
| `FREE_RANGE_CONFIG` | `-c` | `~/.free-range.yaml` |
| `FREE_RANGE_SUBSCRIBERS_CMD` | — | `ssh -C -x roffice /usr/local/share/noc/bin/radius-subscribers` |
| `OPENCHANNEL` | — | `subsystem-netconf` |

Пріоритет: **CLI > ENV > YAML > дефолт**

> `FREE_RANGE_NO_COLOR`, `FREE_RANGE_DEBUG`, `FREE_RANGE_TABLE` — вмикаються будь-яким непорожнім значенням (`1`, `true`, `yes` — всі спрацьовують).

## YAML-конфігурація

За замовчуванням читається `~/.free-range.yaml` (або шлях із `-c` / `FREE_RANGE_CONFIG`).

```yaml
# ~/.free-range.yaml
username: korystuvach
password: abrakadabra
port: 22
subscribers_command: "ssh -C -x roffice /usr/local/share/noc/bin/radius-subscribers"
no_color: false
debug: false
openchannel: subsystem-netconf   # або exec
```

> Файл із credentials не комітити у git — додай його до `.gitignore`.

## Приклади

```bash
# Базовий запуск — текстовий вивід combined ranges
free-range router.example.com -u admin -p secret

# ASCII-таблиця з кольорами
free-range router.example.com -u admin -p secret -t

# PNG у директорію ./output, тільки інтерфейс xe-0/0/2
free-range router.example.com -u admin -p secret -g ./output -i xe-0/0/2

# Всі інтерфейси з dynamic-profile ranges, PNG
free-range router.example.com -u admin -p secret -g ./output -i all

# Через YAML-конфіг, дебаг
free-range router.example.com -c config.yaml -d

# Через змінні оточення (без аргументів)
FREE_RANGE_HOST=router.example.com \
FREE_RANGE_USERNAME=admin \
FREE_RANGE_PASSWORD=secret \
FREE_RANGE_TABLE=1 \
java -jar free-range-1.0.0.jar
```

## Docker

```dockerfile
FROM eclipse-temurin:21-jre
COPY build/libs/free-range-1.0.0.jar /app/free-range.jar
ENTRYPOINT ["java", "-jar", "/app/free-range.jar"]
```

```bash
docker build -t free-range .

docker run --rm \
  -e FREE_RANGE_USERNAME=admin \
  -e FREE_RANGE_PASSWORD=secret \
  -e FREE_RANGE_TABLE=1 \
  free-range router.example.com
```

```yaml
# docker-compose.yml
services:
  free-range:
    image: free-range
    environment:
      FREE_RANGE_USERNAME: admin
      FREE_RANGE_PASSWORD: secret
      FREE_RANGE_INTERFACE: xe-0/0/2
      FREE_RANGE_TABLE_PNG: /output
    volumes:
      - ./output:/output
    command: ["router.example.com"]
```

## NETCONF

Підключення відбувається по SSH (порт 22) із використанням **NETCONF 1.0** (`]]>]]>` framing).

Підтримується два режими каналу (керується `OPENCHANNEL`):
- `subsystem-netconf` — стандартний SSH subsystem (дефолт, Junos ≥ 10.x)
- `exec` — `xml-mode netconf need-trailer` (альтернатива для старих пристроїв)

Запит — `<get-config>` з фільтром по `<interfaces>`, XPath-парсинг:
- Діапазони dynamic-profile: `//interfaces/interface/unit//dynamic-profile//vlan-id-range`
- Demux-інтерфейси: `//interfaces/interface/unit[family/inet/unnumbered-address]`
- Окремі VLAN ID: `//interfaces/interface/unit[vlan-id]/vlan-id`

## Архітектура

```
Main.kt                  точка входу (picocli)
AppConfig.kt             злиття CLI / ENV / YAML / defaults
netconf/
  NetconfClient.kt       JSch + NETCONF 1.0 + XPath-парсинг
subscribers/
  SubscriberSource.kt    інтерфейс (легко замінити на REST)
  LocalCommandSubscriberSource.kt
vlan/
  VlanStatus.kt          FREE / BUSY / ERROR / CONFIGURED / ANOTHER / UNUSED
  VlanProcessor.kt       логіка розподілу
output/
  TextOutput.kt          combined ranges + ANSI
  TableOutput.kt         41×100 ASCII grid
  PngOutput.kt           Java AWT BufferedImage
```

## Вимоги

- Java 21+
- Juniper Junos із підтримкою NETCONF (практично будь-який пристрій після 10.x)
- Доступ до команди `radius-subscribers` (або кастомна команда через `FREE_RANGE_SUBSCRIBERS_CMD`)

## Ліцензія

[Apache-2.0](LICENSE)
