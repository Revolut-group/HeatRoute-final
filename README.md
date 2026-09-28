# HeatRoute

Сервис на Java 11 для построения до трёх вариантов трассы тепловой сети. На вход принимает GeoJSON, на выходе отдаёт проверенный GeoJSON с участками сети, камерами, стоимостью и сводкой по каждому варианту. Работает офлайн, без внешних API.

## Запуск через Docker

Требования: Docker с Compose v2 и не менее 6 ГБ свободной памяти. Первый запуск собирает приложение и может потребовать доступ к Maven Central и Docker Hub.

```powershell
docker compose up -d --build
curl.exe http://127.0.0.1:18081/api/v1/health
```

Linux:

```bash
docker compose up -d --build
curl http://127.0.0.1:18081/api/v1/health
```

По умолчанию сервис доступен только на текущем компьютере. Чтобы открыть его в локальной сети:

```bash
HEATROUTE_HOST=0.0.0.0 docker compose up -d --build
```

В PowerShell:

```powershell
$env:HEATROUTE_HOST="0.0.0.0"
docker compose up -d --build
```

После этого интерфейс открывается с другого компьютера по адресу `http://<IP_СЕРВЕРА>:18081/`. Порт также можно изменить через `HEATROUTE_PORT`.

Сервис готов, когда health-метод вернул HTTP 200 и `"ready":true`.

| Что открыть | Адрес |
|---|---|
| Страница расчёта | http://127.0.0.1:18081/ |
| Swagger UI | http://127.0.0.1:18081/swagger-ui.html |
| OpenAPI JSON | http://127.0.0.1:18081/v3/api-docs |
| Проверка готовности | http://127.0.0.1:18081/api/v1/health |

Чтобы рассчитать свой набор без командной строки, откройте страницу расчёта, выберите локальный файл `.geojson`, задайте параметры и нажмите **Запустить расчёт**. Исходный файл никуда во внешний интернет не отправляется.

Остановка:

```bash
docker compose down
```

## Быстрая проверка

Чтобы не ждать расчёт полного набора, используйте небольшую тестовую сцену:

```powershell
curl.exe -X POST `
  -H "Content-Type: application/geo+json" `
  --data-binary "@benchmarks\expert-cases\C01_basic_dn_boundary.geojson" `
  "http://127.0.0.1:18081/api/v1/calculate?stage=xy&variants=3&mode=fast&profile=default" `
  -o result-check.geojson
```

Linux:

```bash
curl -X POST \
  -H 'Content-Type: application/geo+json' \
  --data-binary '@benchmarks/expert-cases/C01_basic_dn_boundary.geojson' \
  'http://127.0.0.1:18081/api/v1/calculate?stage=xy&variants=3&mode=fast&profile=default' \
  -o result-check.geojson
```

`result-check.geojson` появится в корне проекта. Тот же запрос можно выполнить в Swagger: `POST /api/v1/calculate` → **Try it out** → вставить содержимое GeoJSON → **Execute**.

Готовый пример полного расчёта без запуска алгоритма:

- [интерактивный HTML-отчёт](results/official/report.html);
- [карта в PNG](results/official/report.png);
- [результат GeoJSON](results/official/result.geojson);
- [отчёт встроенной проверки](results/official/verify-report.json).

## Полный расчёт своего набора

Для тяжёлого входного GeoJSON используется фоновый метод `/api/v1/jobs`. Укажите путь к своему файлу в `$inputPath`.

```powershell
$inputPath = "C:\path\to\input.geojson"

$job = curl.exe -s -X POST `
  -H "Content-Type: application/geo+json" `
  --data-binary "@$inputPath" `
  "http://127.0.0.1:18081/api/v1/jobs?stage=xy&variants=3&mode=fast&profile=default" `
  | ConvertFrom-Json

curl.exe "http://127.0.0.1:18081/api/v1/jobs/$($job.job_id)"
```

Когда поле `state` станет `SUCCEEDED`, скачайте результат и отчёт:

```powershell
curl.exe "http://127.0.0.1:18081/api/v1/jobs/$($job.job_id)/result" -o result.geojson
curl.exe "http://127.0.0.1:18081/api/v1/jobs/$($job.job_id)/report" -o report.html
curl.exe "http://127.0.0.1:18081/api/v1/jobs/$($job.job_id)/diagnostics" -o diagnostics.json
```

Linux/macOS (`jq` используется только для получения `job_id`):

```bash
INPUT_PATH=/path/to/input.geojson

JOB_ID=$(curl -s -X POST \
  -H 'Content-Type: application/geo+json' \
  --data-binary "@$INPUT_PATH" \
  'http://127.0.0.1:18081/api/v1/jobs?stage=xy&variants=3&mode=fast&profile=default' \
  | jq -r '.job_id')

curl "http://127.0.0.1:18081/api/v1/jobs/$JOB_ID"
```

После состояния `SUCCEEDED`:

```bash
curl "http://127.0.0.1:18081/api/v1/jobs/$JOB_ID/result" -o result.geojson
curl "http://127.0.0.1:18081/api/v1/jobs/$JOB_ID/report" -o report.html
curl "http://127.0.0.1:18081/api/v1/jobs/$JOB_ID/diagnostics" -o diagnostics.json
```

## Что находится в результате

Ответ — `FeatureCollection` в WGS 84. Основные типы объектов:

- `heat_network` — новые участки сети: геометрия, расход, ДУ, длина и стоимость;
- `heat_chamber` — новые тепловые камеры;
- `technical_node` — точки изменения параметров участка;
- `variant_summary` — ранг варианта, общая длина, стоимость, score и неподключённые точки.

Чем меньше `score`, тем выше вариант; лучший имеет `rank = 1`. Перед выдачей каждый вариант независимо проверяется по исходному файлу: повторно рассчитываются топология, отступы, расходы, диаметры и стоимость.

Основные параметры API:

- `variants=1..3` — количество вариантов;
- `mode=fast|reproducible|deep` — быстрый, воспроизводимый или расширенный поиск;
- `stage=xy|depth` — плановая геометрия или отдельный расчёт с глубинами;
- `profile=default|strict` — основной либо буквальный профиль входа в здание.

Дефолт параметры (уже в запросах выше):
variants=3
mode=fast
stage=xy
profile=default

## Запуск без Docker

Требуется JDK 11. Отдельно устанавливать Maven не нужно: в репозитории есть Maven Wrapper. Для первой сборки потребуется доступ к Maven Central.

```powershell
.\mvnw.cmd -DskipTests package
java -Xmx4g -jar target\heatroute.jar serve --server.port=18080
```

Linux/macOS:

```bash
./mvnw -DskipTests package
java -Xmx4g -jar target/heatroute.jar serve --server.port=18080
```

Swagger будет доступен по адресу http://127.0.0.1:18080/swagger-ui.html.

Проверка исходников:

```powershell
.\mvnw.cmd verify
```

Linux/macOS: `./mvnw verify`.

## Техническая информация

- Java 11, Spring Boot 2.7, Maven, PostgreSQL 16/PostGIS 3.4.
- Расчёты выполняются в EPSG:32637, результат экспортируется в WGS 84.
- Планировщик строит общий лес трасс, уточняет его векторным графом видимости и пересчитывает расход, ДУ, камеры и стоимость после изменения сети.
- Файлы больше 64 МиБ индексируются в PostGIS; вход читается потоково.
- Приложение не использует интернет во время расчёта.

Ключевые каталоги: `src/` — исходники и тесты, `config/` — правила и справочник, `benchmarks/expert-cases/` — инженерные тестовые сцены, `results/official/` — готовый пример результата.
