# free-range

Аналіз розподілу VLAN на мережевих пристроях Juniper через **NETCONF + XPath**.

Розвиток ідей оригінального [free-range](https://github.com/oldengremlin/free-range) (Ruby) із розширеними можливостями.

## Можливості

- Підключення до Junos через **NETCONF** (RFC 6241, framing 1.0) — без sshpass і shell-команд
- XPath-парсинг XML-конфігурації інтерфейсів прямо з `<running>` конфігурації
- Аналіз розподілу VLAN із шістьма статусами
- Підтримка **кількох роутерів** одночасно (`-H r1,r2,r3 -s domain.net`)
- Вивід: текстовий, кольорова ASCII-таблиця, PNG-зображення або **HTML-дашборд** з табами та інтерактивними SVG-графіками (tooltip на кожній клітинці)
- Джерела абонентів: **прямий JDBC до MS SQL** (jTDS, TDS 8.0) або зовнішня команда
- Конфігурація: CLI-аргументи → змінні оточення → YAML-файл → дефолти
- Готовий Docker-образ: **nginx + JRE**, оновлення раз на годину, вбудований вебсервер

## Статуси VLAN

| Символ | Колір | Значення |
|--------|-------|----------|
| `f` | зелений | **free** — у діапазоні dynamic-profile, абонента немає |
| `b` | жовтий | **busy** — у діапазоні, абонент активний |
| `e` | червоний | **error** — абонент є, але VLAN поза будь-яким діапазоном |
| `c` | фіолетовий | **configured** — є `vlan-id` і він у діапазоні |
| `a` | синій | **another** — є `vlan-id`, але поза діапазоном |
| `u` | сірий | **unused** — поза всіма діапазонами, абонента немає |
| `s` | помаранчевий | **shared** — VLAN ID активний одночасно на двох і більше роутерах (у вкладці Global дашборду) |

## Збірка

Потрібно: Java 21+ (перевірено на 21, 24, 25), Gradle wrapper вже в репо.
Для **NetBeans**: `gradle.properties` містить `netbeans.hint.jdkPlatform` — IDE автоматично
використає Java 21 для Gradle.

```bash
git clone https://github.com/oldengremlin/free-range-k.git
cd free-range-k

# Збірка + fat JAR (wrapper вже є в репо — не запускай gradle wrapper!)
./gradlew jar
# → build/libs/free-range-1.0.0.jar  (fat JAR, ~10 MB, всі залежності всередині)
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
  -H, --host HOST[,HOST...]     Comma-separated список роутерів
  -s, --suffix SUFFIX           Домен-суфікс для коротких імен (напр. ukrhub.net)
  -u, --username USERNAME       Ім'я користувача для NETCONF/SSH
  -p, --password PASSWORD       Пароль для NETCONF/SSH
  -n, --no-color                Вимкнути кольоровий вивід
  -d, --debug                   Увімкнути дебаг-режим
  -t, --table                   Вивести ASCII-таблицю розподілу VLAN
  -g, --table-png PATH          Зберегти таблиці як PNG у вказану директорію
      --web                     Згенерувати index.html дашборд у директорії -g
  -i, --interface INTERFACE     Ім'я інтерфейсу (напр. xe-0/0/2, ps0, ae1) або 'all'
  -c, --config CONFIG_FILE      Шлях до YAML-конфігурації
```

Host вказується будь-яким із трьох способів (пріоритет: `-H` = positional > `FREE_RANGE_HOST`):
```bash
free-range router.example.com                    # один роутер (positional)
free-range -H r1,r2,r3 -s ukrhub.net            # кілька роутерів із суфіксом
FREE_RANGE_HOST=r1,r2,r3 FREE_RANGE_SUFFIX=... # через змінні оточення
```

## Змінні оточення

Всі CLI-опції мають відповідні змінні оточення (зручно для Docker):

| Змінна | CLI-аналог | Дефолт |
|--------|------------|--------|
| `FREE_RANGE_HOST` | `<host>` або `-H` | — |
| `FREE_RANGE_SUFFIX` | `-s` | — |
| `FREE_RANGE_USERNAME` або `WHOAMI` | `-u` | — |
| `FREE_RANGE_PASSWORD` або `WHATISMYPASSWD` | `-p` | — |
| `FREE_RANGE_PORT` | — | `22` |
| `FREE_RANGE_NO_COLOR` | `-n` | вимк. |
| `FREE_RANGE_DEBUG` | `-d` | вимк. |
| `FREE_RANGE_TABLE` | `-t` | вимк. |
| `FREE_RANGE_TABLE_PNG` | `-g` | — |
| `FREE_RANGE_WEB` | `--web` | вимк. |
| `FREE_RANGE_INTERFACE` | `-i` | — |
| `FREE_RANGE_CONFIG` | `-c` | `~/.free-range.yaml` |
| `OPENCHANNEL` | — | `subsystem-netconf` |
| `FREE_RANGE_ACC_SERVER` | — | — (без MSSQL) |
| `FREE_RANGE_ACC_DATABASE` | — | `AccEquipment_V2_Release` |
| `FREE_RANGE_ACC_USER` | — | — |
| `FREE_RANGE_ACC_PASSWORD` | — | — |
| `FREE_RANGE_ACC_PORT` | — | `1433` |
| `FREE_RANGE_SUBSCRIBERS_CMD` | — | `ssh -C -x roffice ...` (fallback) |
| `FREE_RANGE_MAX_CONCURRENT` | — | `5` |

Пріоритет: **CLI > ENV > YAML > дефолт**

> `FREE_RANGE_NO_COLOR`, `FREE_RANGE_DEBUG`, `FREE_RANGE_TABLE`, `FREE_RANGE_WEB` — вмикаються
> будь-яким непорожнім значенням (`1`, `true`, `yes` — всі спрацьовують).

## Джерела абонентів

### Прямий MSSQL (рекомендовано)

Якщо задано `FREE_RANGE_ACC_SERVER`, `FREE_RANGE_ACC_USER` і `FREE_RANGE_ACC_PASSWORD` —
програма підключається до MS SQL Server через **jTDS** (підтримує TDS 8.0, без SSL):

```bash
FREE_RANGE_ACC_SERVER=10.100.1.59
FREE_RANGE_ACC_USER=nocc
FREE_RANGE_ACC_PASSWORD=...
# FREE_RANGE_ACC_DATABASE=AccEquipment_V2_Release  # дефолт
# FREE_RANGE_ACC_PORT=1433                          # дефолт
```

### Зовнішня команда (fallback)

Якщо `FREE_RANGE_ACC_SERVER` не задано — виконується команда з `FREE_RANGE_SUBSCRIBERS_CMD`:

```bash
FREE_RANGE_SUBSCRIBERS_CMD="ssh -C -x roffice /usr/local/share/noc/bin/radius-subscribers"
```

## YAML-конфігурація

За замовчуванням читається `~/.free-range.yaml` (або шлях із `-c` / `FREE_RANGE_CONFIG`).

```yaml
# ~/.free-range.yaml
username: korystuvach
password: abrakadabra
port: 22
no_color: false
debug: false
openchannel: subsystem-netconf   # або exec

# Джерело абонентів (одне з двох):
acc_server: 10.100.1.59
acc_database: AccEquipment_V2_Release
acc_user: nocc
acc_password: secret
# subscribers_command: "ssh -C -x roffice ..."   # альтернатива
```

> Файл із credentials не комітити у git — додай його до `.gitignore`.

## Приклади

```bash
# Базовий запуск — текстовий вивід
free-range router.example.com -u admin -p secret

# ASCII-таблиця з кольорами
free-range router.example.com -u admin -p secret -t

# Кілька роутерів із суфіксом
free-range -H r1,r2,r3 -s ukrhub.net -u admin -p secret -t

# PNG у директорію ./output, тільки інтерфейс xe-0/0/2
free-range router.example.com -u admin -p secret -g ./output -i xe-0/0/2

# HTML-дашборд із SVG (tooltip при наведенні на будь-яку клітинку: «VLAN 234, busy»)
free-range -H r1,r2,r3 -s ukrhub.net -u admin -p secret -g /var/www/html --web

# Через змінні оточення
FREE_RANGE_HOST=router.example.com \
FREE_RANGE_USERNAME=admin \
FREE_RANGE_PASSWORD=secret \
FREE_RANGE_TABLE=1 \
java -jar free-range-1.0.0.jar
```

## Docker

Образ базується на **nginx:mainline** і містить вбудований JRE. Колектор запускається
у фоні через `/docker-entrypoint.d/` і оновлює SVG + `index.html` щогодини.

### Збірка

```bash
docker build -t free-range .
```

### Запуск

```bash
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

Після старту `http://localhost:8080/` показує актуальний дашборд із табами по роутерах та інтерфейсах.

### docker-compose

```yaml
services:
  vlan:
    build: .
    container_name: vlan
    restart: unless-stopped
    environment:
      FREE_RANGE_HOST: r1,r2,r3
      FREE_RANGE_SUFFIX: ukrhub.net
      FREE_RANGE_USERNAME: admin
      FREE_RANGE_PASSWORD: secret
      FREE_RANGE_TABLE_PNG: /usr/share/nginx/html
      FREE_RANGE_WEB: "1"
      FREE_RANGE_ACC_SERVER: 10.100.1.59
      FREE_RANGE_ACC_USER: nocc
      FREE_RANGE_ACC_PASSWORD: secret
    ports:
      - "8080:80"
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
  SubscriberSource.kt    інтерфейс
  MssqlSubscriberSource.kt  JDBC → MS SQL (jTDS, TDS 8.0)
  LocalCommandSubscriberSource.kt  shell-команда (fallback)
vlan/
  VlanStatus.kt          FREE / BUSY / ERROR / CONFIGURED / ANOTHER / UNUSED
  VlanProcessor.kt       логіка розподілу
output/
  TextOutput.kt          combined ranges + ANSI
  TableOutput.kt         41×100 ASCII grid
  PngOutput.kt           Java AWT BufferedImage (для -g без --web)
  SvgOutput.kt           SVG із tooltip на кожній клітинці (для --web)
  WebOutput.kt           HTML-дашборд (таби по роутерах / інтерфейсах)
bin/
  free-range.sh          цикл оновлення (while true; sleep 3600)
docker-entrypoint.d/
  40-free-range.sh       запуск колектора у фоні через nginx entrypoint
Dockerfile               3-stage build: JDK builder → JRE provider → nginx runtime
```

## Паралельна обробка

У режимах `--web` і `-g` (файловий вивід) всі хости обробляються паралельно через **Java virtual threads**.
Кількість одночасних SSH-з'єднань обмежена семафором:

```bash
FREE_RANGE_MAX_CONCURRENT=5   # дефолт; встановіть = кількість хостів для максимальної швидкості
```

Типовий виграш при 14 хостах: ~41 сек → ~10 сек.

Текстовий і ASCII-табличний виводи (`-t`, без `-g`) залишаються послідовними — щоб рядки у stdout не перемішувались.

Файли записуються атомарно: спочатку у тимчасовий файл у тій самій директорії, потім `rename()` — nginx ніколи не побачить частково записаний SVG або `index.html`.

## Логування

Усі повідомлення виводяться у `stderr` через **Log4j2** у форматі, сумісному з nginx:

```
[14/May/2026:13:52:33 +0300] [INFO ] n.u.n.f.FreeRangeCommand - Connecting to rdc-1.ukrhub.net...
[14/May/2026:13:52:34 +0300] [INFO ] n.u.n.f.o.SvgOutput - SVG saved: /usr/share/nginx/html/free-range-rdc-1.svg
```

У Docker-образі це дозволяє читати `docker logs` як єдиний потік разом із nginx-логами.
Рівень `DEBUG` вмикається через `-d` / `FREE_RANGE_DEBUG=1`.

## Вимоги

- Java 21+
- Juniper Junos із підтримкою NETCONF (практично будь-який пристрій після 10.x)
- MS SQL Server (TDS 8.0+) **або** зовнішня команда для отримання абонентів

## Ліцензія

[Apache-2.0](LICENSE)
