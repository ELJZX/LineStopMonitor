# Презентация проекта Line Stop Monitor

Визуальное объяснение: **какую проблему решает проект и зачем он нужен**.

## Файлы

| Файл | Назначение |
|------|------------|
| `slides.md` | Слайды (формат [Marp](https://marp.app/) — Markdown) |
| `README.md` | Как открыть и собрать презентацию |

## Как посмотреть

**Вариант 1. VS Code.** Установить расширение **Marp for VS Code**, открыть
`slides.md` и включить предпросмотр (иконка Marp).

**Вариант 2. Marp CLI.** Экспорт в PDF, PPTX или HTML:

```powershell
npx @marp-team/marp-cli presentation/slides.md -o presentation/linestop.pdf
npx @marp-team/marp-cli presentation/slides.md -o presentation/linestop.pptx
npx @marp-team/marp-cli presentation/slides.md --html -o presentation/linestop.html
```

Разделитель слайдов — `---`. Диаграммы нарисованы ASCII-графикой, поэтому
корректно отображаются в любом рендерере.

## Кратко о содержании

Проблема (простои не фиксируются) → что решает проект (фиксация каждой
остановки, обязательная причина, история и смены) → как работает (ПЛК + планшет)
→ главный эффект (**понять и ускорить работу линии**) → планы (web-кабинет с
выгрузкой и фильтрами).
