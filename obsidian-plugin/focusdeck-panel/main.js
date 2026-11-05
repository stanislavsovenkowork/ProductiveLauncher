'use strict';

// FocusDeck panel: the launcher's four sections inside Obsidian, reading the very same notes.
// Formats follow the Android app (DailyNoteParser.kt, RitualNotes, LifeNoteCodec.kt):
// tasks live only under "## Задачи", ritual values under "## FocusDeck", life notes in FocusDeck/*.

const { Plugin, ItemView, TFile, Notice, setIcon } = require('obsidian');

const VIEW_TYPE = 'focusdeck-panel';
const TASK = /^\s*-\s\[( |x|X)]\s*(.*)$/;
const TIME = /[⏳🕐]\s*(\d{1,2}):(\d{2})|\[time::\s*(\d{1,2}):(\d{2})]/;
const DUR = /\[dur::\s*(\d+)]/;
const PROJECT = /\[project::\s*([A-Za-z0-9_-]+)]/;
const TASK_ID = /\^task-([A-Za-z0-9_-]+)\s*$/;
const NOISE = [
  /[⏳🕐]\s*\d{1,2}:\d{2}/g, /\[time::[^\]]*]/g, /\[dur::[^\]]*]/g, /\[project::[^\]]*]/g,
  /\[goal::[^\]]*]/g, /\[area::[^\]]*]/g, /\[focusdeck-note::\s*\[\[[^\]]*]]\s*]/g,
  /\^task-[A-Za-z0-9_-]+\s*$/g, /✅\s*\d{4}-\d{2}-\d{2}/g,
];
// Clean mode: while editing in Live Preview, markdown syntax and FocusDeck's machine metadata
// stay hidden. Only the display changes; the note text is never rewritten, so the Android
// parser, block links and other plugins keep seeing exactly the same file.
const HIDE_FIELDS = /[ \t]*\[(?:dur|time|project|goal|area)::[^\]\n]*]/g;
const TIME_MARK = /[⏳🕐]\uFE0F?[ \t]*(?=\d{1,2}:\d{2})/gu;
const TASK_LINE = /^\s*(?:>\s*)*[-*+]\s\[.\]\s/;
const QUOTE_PREFIX = /^[ \t]*(?:>[ \t]?)+/;
const CALLOUT_HEAD = /^([ \t]*(?:>[ \t]?)+)\[!([^\]\s]+)\][+-]?[ \t]*/;
const BULLET = /^[-*+][ \t]+(?!\[.\])/;
// Obsidian's own markers that clean mode leaves alone: list bullets and checkboxes are real
// controls, tags and code fences read better with their marks, quotes are handled below.
const KEEP_MARK = /formatting-(?:list|task|hashtag|code|math|quote|escape)|hashtag/;

// Start of the metadata tail ("[dur:: 45] ^task-…") at the end of a task line, or -1.
function metaTail(text) {
  if (!TASK_LINE.test(text)) return -1;
  const end = text.replace(/[ \t]+$/, '').length;
  const piece = /[ \t]*(?:\^task-[A-Za-z0-9_-]+|\[(?:dur|time|project|goal|area)::[^\]\n]*]|\[focusdeck-note::[ \t]*\[\[[^\]\n]*]][ \t]*])$/;
  let tail = end;
  for (let m; (m = piece.exec(text.slice(0, tail)));) tail = m.index;
  return tail < end ? tail : -1;
}

function journalLines(doc) {
  const tags = new Set();
  const body = new Set();
  let open = -1;
  for (let n = 1; n <= doc.lines; n++) {
    const t = doc.line(n).text;
    if (open < 0) {
      if (/^\s*<details[^>]*daily-journal[^>]*>\s*$/i.test(t)) { open = n; tags.add(n); }
    } else if (/^\s*<summary>.*<\/summary>\s*$/i.test(t) && n - open <= 2) {
      tags.add(n);
    } else if (/^\s*<\/details>\s*$/i.test(t)) {
      tags.add(n); open = -1;
    } else {
      body.add(n);
    }
  }
  return { tags, body };
}

const calloutLooks = new Map();
function calloutLook(type) {
  const key = type.toLowerCase();
  if (!calloutLooks.has(key)) {
    // Ask the theme for a real rendered callout, so custom callouts and snippets match exactly.
    const host = document.body.createDiv({ cls: 'markdown-rendered' });
    host.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;';
    const box = host.createDiv({ cls: 'callout', attr: { 'data-callout': key } });
    const title = box.createDiv({ cls: 'callout-title' });
    const cs = getComputedStyle(box);
    const look = {
      bg: cs.backgroundColor,
      title: getComputedStyle(title).color,
      icon: cs.getPropertyValue('--callout-icon').trim() || 'lucide-pencil',
    };
    host.remove();
    calloutLooks.set(key, look);
  }
  return calloutLooks.get(key);
}

function cleanModeExtension(plugin) {
  let cmView, cmState, cmLanguage, obsidian;
  try {
    cmView = require('@codemirror/view');
    cmState = require('@codemirror/state');
    cmLanguage = require('@codemirror/language');
    obsidian = require('obsidian');
  } catch (e) { return null; }
  const { ViewPlugin, Decoration, WidgetType, EditorView, keymap } = cmView;
  const { EditorSelection, EditorState, StateEffect, StateField, Annotation, Prec } = cmState;

  const refresh = StateEffect.define();
  const skipClamp = Annotation.define();
  plugin.refreshEffect = refresh;

  const isLive = (state) => {
    try { return state.field(obsidian.editorLivePreviewField); } catch (e) { return false; }
  };
  const active = (state) => plugin.hideMeta && isLive(state);

  class Icon extends WidgetType {
    constructor(icon, label) { super(); this.icon = icon; this.label = label; }
    eq(o) { return o.icon === this.icon && o.label === this.label; }
    toDOM() {
      const el = document.createElement('span');
      el.className = 'fd-co-icon';
      obsidian.setIcon(el, this.icon);
      if (this.label) el.appendText(this.label);
      return el;
    }
  }
  class Bullet extends WidgetType {
    eq() { return true; }
    toDOM() { const el = document.createElement('span'); el.className = 'fd-bullet'; return el; }
  }
  class Label extends WidgetType {
    constructor(icon, text, cls) { super(); this.icon = icon; this.text = text; this.cls = cls || 'fd-jr-name'; }
    eq(o) { return o.icon === this.icon && o.text === this.text && o.cls === this.cls; }
    toDOM() {
      const el = document.createElement('span');
      el.className = this.cls;
      obsidian.setIcon(el.createSpan('fd-jr-icon'), this.icon);
      el.createSpan({ text: this.text });
      return el;
    }
  }

  // Goals, projects, decisions: a header card over the note, and empty sections read as "to fill".
  class LifeHeader extends WidgetType {
    constructor(info) { super(); this.info = info; this.key = JSON.stringify(info); }
    eq(o) { return o.key === this.key; }
    ignoreEvent() { return true; }
    toDOM(view) {
      const i = this.info;
      const app = window.app;
      const file = () => { try { return view.state.field(obsidian.editorInfoField).file; } catch (e) { return null; } };
      const act = (fn) => (ev) => { ev.preventDefault(); ev.stopPropagation(); const f = file(); if (f) fn(f); };
      const card = document.createElement('div');
      card.className = `fd-life-head is-${i.kind}`;
      const top = card.createDiv('fd-lh-top');
      const kind = top.createDiv('fd-lh-kind');
      obsidian.setIcon(kind.createSpan('fd-jr-icon'), KINDS[i.kind]?.icon || 'file');
      kind.createSpan({ text: KIND_SINGULAR[i.kind] || 'Запись' });
      top.createDiv({ cls: `fd-b-pill is-${i.status}`, text: statusLabel(i.kind, i.status) });
      const dates = top.createDiv('fd-lh-dates');
      for (const d of i.kind === 'decision' ? [] : i.dates) {
        const chip = dates.createDiv({ cls: 'fd-b-date' + (d.late ? ' is-late' : '') });
        obsidian.setIcon(chip.createSpan(), d.icon);
        chip.createSpan({ text: d.text });
      }
      const button = (parent, label, icon, cls, fn) => {
        const b = parent.createEl('button', { cls: 'fd-lh-btn ' + cls });
        if (icon) obsidian.setIcon(b.createSpan('fd-jr-icon'), icon);
        b.createSpan({ text: label });
        b.onclick = act(fn);
        return b;
      };

      if (i.kind === 'decision') {
        // The decision path: basis first, then the choice is fixed, then reality is compared with it.
        const path = card.createDiv('fd-dc-path');
        const short = { 'Варианты': 'Варианты', 'Критерии выбора': 'Критерии', 'Мой выбор и основания': 'Выбор', 'Ожидаемый результат': 'Ожидание', 'Цена выбора': 'Цена' };
        for (const h of DECISION_BASIS) {
          const step = path.createEl('button', { cls: 'fd-dc-step' + (i.filledSet.includes(h) ? ' is-done' : '') });
          obsidian.setIcon(step.createSpan('fd-jr-icon'), i.filledSet.includes(h) ? 'check' : 'circle');
          step.createSpan({ text: short[h] });
          step.onclick = (ev) => { ev.preventDefault(); jumpToSection(view, h); };
        }
        const band = card.createDiv('fd-dc-band');
        const text = band.createDiv('fd-dc-text');
        const actions = band.createDiv('fd-lh-actions');
        if (i.status === 'draft') {
          text.createDiv({ cls: 'fd-dc-title', text: 'Решение ещё не принято' });
          text.createDiv({ cls: 'fd-dc-sub', text: i.missing.length ? 'Осталось: ' + i.missing.join(', ') : 'Всё готово — зафиксируй выбор, чтобы потом сверить с реальностью' });
          const b = button(actions, 'Принять решение', 'check', 'is-primary', (f) => lifeActions.accept(app, f));
          if (i.missing.length) b.addClass('is-off');
          button(actions, 'Дата проверки', 'calendar', '', (f) => openReviewDateDialog(app, f, i.review));
        } else if (i.status === 'active') {
          text.createDiv({ cls: 'fd-dc-title', text: i.decidedOn ? `Принято ${inDays(daysFrom(ymd(new Date()), i.decidedOn))}` : 'Решение принято' });
          const bits = [];
          if (i.confidence) bits.push(`уверенность ${i.confidence}%`);
          if (i.reviewIn != null) bits.push(i.reviewIn <= 0 ? 'пора проверить, сбылось ли ожидание' : `проверка ${inDays(i.reviewIn)}`);
          if (bits.length) text.createDiv({ cls: 'fd-dc-sub' + (i.reviewIn != null && i.reviewIn <= 0 ? ' is-late' : ''), text: bits.join(' · ') });
          button(actions, 'Записать результат', 'flag', 'is-primary', (f) => openOutcomeDialog(app, f));
          button(actions, 'Перенести проверку', 'calendar', '', (f) => openReviewDateDialog(app, f, i.review));
        } else {
          text.createDiv({ cls: 'fd-dc-title', text: i.verdict ? `Ожидание сбылось: ${i.verdict}` : 'Решение проверено' });
          if (i.lesson) text.createDiv({ cls: 'fd-dc-sub', text: i.lesson });
          if (i.verdict) band.addClass('is-' + ({ 'да': 'yes', 'частично': 'partly', 'нет': 'no' }[i.verdict] || ''));
        }
        return card;
      }

      if (i.metric) {
        const m = card.createDiv('fd-lh-metric');
        const cur = i.metric.current ?? i.metric.start;
        m.createDiv({ cls: 'fd-lh-metric-n', text: `${cur ?? '—'} / ${i.metric.target}${i.metric.unit ? ' ' + i.metric.unit : ''}` });
        const bar = m.createDiv('fd-b-bar');
        bar.createDiv('fd-b-bar-fill').style.width = `${Math.round((i.metric.fraction || 0) * 100)}%`;
      }
      if (i.total) {
        const prog = card.createDiv('fd-lh-prog');
        if (i.stages) {
          prog.createDiv({ cls: 'fd-b-meta', text: `Этапы ${i.stagesDone} из ${i.stages}` });
          const bar = prog.createDiv('fd-b-bar');
          bar.createDiv('fd-b-bar-fill').style.width = `${Math.round((i.stagesDone / i.stages) * 100)}%`;
        }
        prog.createDiv({ cls: 'fd-b-meta', text: `заполнено ${i.filled} из ${i.total}` });
      }
      if (i.empty.length) {
        const chips = card.createDiv('fd-lh-todo');
        chips.createSpan({ cls: 'fd-lh-todo-label', text: 'Дальше:' });
        for (const e of i.empty) {
          const chip = chips.createEl('button', { cls: 'fd-lh-chip', text: e.heading });
          chip.onclick = (ev) => { ev.preventDefault(); jumpToSection(view, e.heading); };
        }
      }
      const actions = card.createDiv('fd-lh-actions');
      if (i.status === 'draft' && i.kind !== 'review') {
        const b = button(actions, i.kind === 'goal' ? 'Поставить цель' : i.kind === 'project' ? 'Запустить проект' : 'В работу', 'play', 'is-primary', (f) => lifeActions.setStatus(app, f, 'active'));
        if (i.missing.length) { b.addClass('is-off'); actions.createDiv({ cls: 'fd-dc-sub', text: 'Осталось: ' + i.missing.join(', ') }); }
      }
      if (i.status === 'active') {
        if (i.metric) button(actions, 'Замер', 'plus', 'is-primary', (f) => openMeasureDialog(app, f, i.metric.unit));
        if (i.kind === 'goal') button(actions, 'Цель достигнута', 'trophy', i.metric ? '' : 'is-primary', (f) => openEvidenceDialog(app, f));
        else if (i.kind === 'project') button(actions, 'Проект готов', 'check', '', (f) => lifeActions.setStatus(app, f, 'done'));
        if (i.kind !== 'area') button(actions, 'Пауза', 'pause', '', (f) => lifeActions.setStatus(app, f, 'paused'));
      }
      if (i.kind === 'review' && i.status === 'draft') button(actions, 'Завершить обзор', 'check', 'is-primary', (f) => lifeActions.setStatus(app, f, 'done'));
      if (i.status === 'paused') button(actions, 'Вернуться в работу', 'play', 'is-primary', (f) => lifeActions.setStatus(app, f, 'active'));
      if (i.status === 'done' && i.kind !== 'review') button(actions, 'Вернуть в работу', 'rotate-ccw', '', (f) => lifeActions.setStatus(app, f, 'active'));
      if (i.kind !== 'area' && i.status !== 'done') button(actions, 'Дата сверки', 'calendar', '', (f) => openReviewDateDialog(app, f, i.review));
      if (!actions.childElementCount) actions.remove();
      return card;
    }
  }

  function jumpToSection(view, heading) {
    const doc = view.state.doc;
    for (let n = 1; n <= doc.lines; n++) {
      if (doc.line(n).text.trim() !== `## ${heading}`) continue;
      const line = doc.line(n);
      const next = n < doc.lines ? doc.line(n + 1) : null;
      if (next && !next.text.trim()) {
        view.dispatch({ selection: { anchor: next.from }, scrollIntoView: true });
      } else {
        view.dispatch({ changes: { from: line.to, insert: '\n' }, selection: { anchor: line.to + 1 }, scrollIntoView: true });
      }
      view.focus();
      return;
    }
  }

  function lifeDecorations(state) {
    if (!active(state)) return Decoration.none;
    const doc = state.doc;
    const found = lifeFrontmatter(doc);
    if (!found || found.end >= doc.lines) return Decoration.none;
    const { fm } = found;
    const kind = String(fm.kind || '').toLowerCase();
    const status = String(fm.status || 'draft').toLowerCase();
    const heads = [];
    for (let n = found.end + 1; n <= doc.lines; n++) {
      const m = /^##\s+(.+?)\s*$/.exec(doc.line(n).text);
      if (m) heads.push({ n, heading: m[1] });
    }
    const out = [];
    const sections = heads.map((h, k) => {
      const endN = k + 1 < heads.length ? heads[k + 1].n : doc.lines + 1;
      let body = '';
      for (let n = h.n + 1; n < endN; n++) body += doc.line(n).text + '\n';
      return { ...h, filled: /[\p{L}\p{N}]/u.test(body), body };
    }).filter((s) => !LIFE_SERVICE.has(s.heading));
    for (const s of sections) if (!s.filled) out.push(Decoration.line({ class: 'fd-h-empty' }).range(doc.line(s.n).from));
    const today = ymd(new Date());
    const dates = [];
    const done = status === 'done' || status === 'archived';
    const rel = (v) => daysFrom(today, v);
    // Far dates read better as a date than as "через 182 дня".
    const when = (v) => Math.abs(rel(v)) > 30 ? dayOf(v).toLocaleDateString('ru', { day: 'numeric', month: 'long', year: dayOf(v).getFullYear() === new Date().getFullYear() ? undefined : 'numeric' }) : inDays(rel(v));
    if (fm.due && rel(fm.due) != null && !done) dates.push({ icon: 'flag', text: `срок ${when(fm.due)}`, late: rel(fm.due) <= 0 });
    if (fm.review && rel(fm.review) != null && !done) dates.push({ icon: 'refresh-cw', text: `сверка ${when(fm.review)}`, late: rel(fm.review) <= 0 });
    if (fm.period_start && fm.period_end) dates.push({ icon: 'calendar', text: `${fm.period_start.slice(5)} — ${fm.period_end.slice(5)}`, late: false });
    const stages = sections.find((s) => s.heading === 'Этапы');
    const marks = stages ? stages.body.split('\n').map((l) => TASK.exec(l)).filter(Boolean) : [];
    const text = (h) => sections.find((s) => s.heading === h)?.body.trim() || '';
    const filledFields = {};
    for (const s of sections) filledFields[s.heading] = s.filled ? s.body : '';
    const metricText = text('Показатель');
    let metric = null;
    const mt = /^\s*Цель:\s*(-?[\d\s]+(?:[.,]\d+)?)\s*(.*)$/m.exec(metricText);
    if (mt) {
      const num = (x) => Number(String(x).replace(/\s/g, '').replace(',', '.'));
      const target = num(mt[1]);
      const st = /^\s*Старт:\s*(-?[\d\s]+(?:[.,]\d+)?)/m.exec(metricText);
      const pts = [...metricText.matchAll(/^\s*[-*]\s*(\d{4}-\d{2}-\d{2})\s*:\s*(-?[\d\s]+(?:[.,]\d+)?)/gm)].map((x) => num(x[2]));
      const current = pts.length ? pts[pts.length - 1] : null;
      const start = st ? num(st[1]) : null;
      const base = start ?? (pts.length && pts[0] > target ? pts[0] : 0);
      metric = { target, unit: mt[2].trim(), start, current,
        fraction: target === base ? null : Math.max(0, Math.min(1, ((current ?? base) - base) / (target - base))) };
    }
    const info = {
      kind, status, dates, review: fm.review || '', decidedOn: fm.decided_on || '',
      reviewIn: fm.review ? rel(fm.review) : null,
      filledSet: sections.filter((s) => s.filled).map((s) => s.heading),
      missing: kind === 'area' || kind === 'review' ? [] : activationProblems(kind, fm.title, filledFields, fm.review),
      confidence: (/^Уверенность:\s*(\d{1,3})\s*%/m.exec(text('Ожидаемый результат')) || [])[1] || null,
      verdict: (/^Ожидание сбылось:\s*(да|частично|нет)/m.exec(text('Что произошло')) || [])[1] || null,
      lesson: text('Что я понял').split('\n')[0] || '',
      metric,
      filled: sections.filter((s) => s.filled).length, total: sections.length,
      empty: sections.filter((s) => !s.filled).slice(0, 4).map((s) => ({ heading: s.heading })),
      stages: marks.length, stagesDone: marks.filter((m) => m[1] !== ' ').length,
    };
    // Under the note's "# Title" when there is one, so the card reads as the title's subline.
    let titleLine = null;
    for (let n = found.end + 1; n <= Math.min(doc.lines, found.end + 4); n++) if (/^#\s/.test(doc.line(n).text)) { titleLine = doc.line(n); break; }
    out.push((titleLine
      ? Decoration.widget({ widget: new LifeHeader(info), block: true, side: 1 }).range(titleLine.to)
      : Decoration.widget({ widget: new LifeHeader(info), block: true, side: -1 }).range(doc.line(found.end + 1).from)));
    return Decoration.set(out, true);
  }

  const lifeHeader = StateField.define({
    create: (state) => lifeDecorations(state),
    update(value, tr) {
      if (tr.docChanged || isLive(tr.startState) !== isLive(tr.state) || tr.effects.some((e) => e.is(refresh))) return lifeDecorations(tr.state);
      return value;
    },
    provide: (f) => EditorView.decorations.from(f),
  });

  const hide = Decoration.replace({});
  const bullet = Decoration.replace({ widget: new Bullet() });
  const tagLine = Decoration.line({ class: 'fd-tag-line' });
  const bulletLine = Decoration.line({ class: 'fd-bullet-line' });

  // "⏳ 10:15" reads as "10:15": the emoji stays in the file because the app's parser needs it.
  function hideTimeMarks(text, from, out) {
    if (!TASK_LINE.test(text)) return;
    for (const m of text.matchAll(TIME_MARK)) out.push(hide.range(from + m.index, from + m.index + m[0].length));
  }

  function build(view) {
    const state = view.state;
    if (!active(state)) return { deco: Decoration.none, atomic: Decoration.none };
    const doc = state.doc;
    const sel = state.selection.ranges;
    const journal = journalLines(doc);
    const ritual = ritualLines(doc);
    const cursorOn = (line) => view.hasFocus && sel.some((r) => r.from <= line.to && r.to >= line.from);
    const ritualShown = [...ritual.body].filter((n) => {
      const rr = RITUAL_ROW.exec(doc.line(n).text);
      return rr && (!ritualFlag(rr[2], doc.line(n).text.slice(rr[0].length)) || cursorOn(doc.line(n)));
    });
    const lines = [];
    const marks = [];
    const own = [];
    const seen = new Set();

    for (const { from, to } of view.visibleRanges) {
      for (let pos = from; pos <= to;) {
        const line = doc.lineAt(pos);
        pos = line.to + 1;
        if (seen.has(line.number)) continue;
        seen.add(line.number);
        const text = line.text;

        if (line.number === ritual.head) {
          const at = text.indexOf('FocusDeck');
          lines.push(Decoration.line({ class: 'fd-rt-head' }).range(line.from));
          if (!cursorOn(line)) own.push(Decoration.replace({ widget: new Label('target', 'Фокус дня', 'fd-rt-title') }).range(line.from + at, line.from + at + 9));
          continue;
        }
        if (ritual.body.has(line.number)) {
          const rr = RITUAL_ROW.exec(text);
          if (!rr) continue;
          if (ritualFlag(rr[2], text.slice(rr[0].length))) {
            if (!cursorOn(line)) lines.push(tagLine.range(line.from));
            continue;
          }
          const cls = ['fd-rt'];
          if (line.number === ritualShown[0]) cls.push('fd-rt-first');
          if (line.number === ritualShown[ritualShown.length - 1]) cls.push('fd-rt-last');
          if (rr[2] === 'Главное') cls.push('fd-rt-main');
          lines.push(Decoration.line({ class: cls.join(' ') }).range(line.from));
          const look = RITUAL_LOOK[rr[2]];
          own.push(Decoration.replace({ widget: new Label(look.icon, look.label) }).range(line.from, line.from + rr[0].length));
          continue;
        }

        if (journal.tags.has(line.number)) {
          if (!(view.hasFocus && sel.some((r) => r.from <= line.to && r.to >= line.from))) lines.push(tagLine.range(line.from));
          continue;
        }
        if (journal.body.has(line.number)) {
          const jr = JOURNAL_ROW.exec(text);
          if (jr) {
            const isRow = (n) => n >= 1 && n <= doc.lines && journal.body.has(n) && JOURNAL_ROW.test(doc.line(n).text);
            const cls = ['fd-jr'];
            if (!isRow(line.number - 1)) cls.push('fd-jr-first');
            if (!isRow(line.number + 1)) cls.push('fd-jr-last');
            const empty = !text.slice(jr[0].length).trim();
            if (empty) cls.push('fd-jr-empty');
            lines.push(Decoration.line({ class: cls.join(' '), attributes: { 'data-ph': JOURNAL_LOOK[jr[2]].hint } }).range(line.from));
            own.push(Decoration.replace({ widget: new Label(JOURNAL_LOOK[jr[2]].icon, jr[2]) }).range(line.from, line.from + jr[0].length));
            continue;
          }
          const b = /^([ \t]*)[-*+][ \t]+/.exec(text);
          if (b && !TASK_LINE.test(text)) {
            lines.push(bulletLine.range(line.from));
            own.push(bullet.range(line.from + b[1].length, line.from + b[0].length));
          }
          continue;
        }

        const quote = QUOTE_PREFIX.exec(text);
        if (quote) {
          const region = calloutRegion(doc, line.number);
          if (region) {
            const look = calloutLook(region.type);
            const cls = ['fd-co'];
            if (line.number === region.first) cls.push('fd-co-first');
            if (line.number === region.last) cls.push('fd-co-last');
            const head = line.number === region.first ? CALLOUT_HEAD.exec(text) : null;
            const rest = head ? text.slice(head[0].length) : text.slice(quote[0].length);
            const b = !head && BULLET.exec(rest);
            if (b) cls.push('fd-bullet-line');
            lines.push(Decoration.line({ class: cls.join(' '), attributes: { style: `--fd-co-bg: ${look.bg}; --fd-co-title: ${look.title}` } }).range(line.from));
            if (head) {
              const label = rest.trim() ? '' : region.type.charAt(0).toUpperCase() + region.type.slice(1);
              own.push(Decoration.replace({ widget: new Icon(look.icon, label) }).range(line.from, line.from + head[0].length));
            } else if (b) {
              own.push(bullet.range(line.from, line.from + quote[0].length + b[0].length));
            } else if (quote[0].length) {
              own.push(hide.range(line.from, line.from + quote[0].length));
            }
          } else if (quote[0].length) {
            own.push(hide.range(line.from, line.from + quote[0].length));
          }
          hideTimeMarks(text, line.from, own);
          const tail = metaTail(text);
          if (tail >= 0) own.push(hide.range(line.from + tail, line.to));
          continue;
        }

        hideTimeMarks(text, line.from, own);
        const tail = metaTail(text);
        if (tail >= 0) own.push(hide.range(line.from + tail, line.to));
      }
    }

    // Markdown marks (** _ # [[ ]] == ~~ `) come from Obsidian's own parser.
    for (const { from, to } of view.visibleRanges) {
      cmLanguage.syntaxTree(state).iterate({
        from, to,
        enter(node) {
          const name = node.name;
          if (!name.includes('formatting') || KEEP_MARK.test(name) || node.to <= node.from) return;
          marks.push([node.from, node.to]);
        },
      });
    }
    const taken = own.map((r) => [r.from, r.to]);
    const overlaps = (a, z) => taken.some(([f, t]) => a < t && z > f);
    const replaces = own.slice();
    for (const [a, z] of marks) if (!overlaps(a, z)) { replaces.push(hide.range(a, z)); taken.push([a, z]); }

    return {
      deco: Decoration.set(lines.concat(replaces), true),
      atomic: Decoration.set(replaces, true),
    };
  }

  function calloutRegion(doc, n) {
    let first = n;
    while (first >= 1 && QUOTE_PREFIX.test(doc.line(first).text) && !CALLOUT_HEAD.test(doc.line(first).text)) first--;
    if (first < 1 || !CALLOUT_HEAD.test(doc.line(first).text)) return null;
    let last = n;
    while (last < doc.lines && QUOTE_PREFIX.test(doc.line(last + 1).text) && !CALLOUT_HEAD.test(doc.line(last + 1).text)) last++;
    return { first, last, type: CALLOUT_HEAD.exec(doc.line(first).text)[2] };
  }

  const decorations = ViewPlugin.fromClass(class {
    constructor(view) { this.live = isLive(view.state); Object.assign(this, build(view)); this.enhance(view); }
    enhance(view) {
      if (!active(view.state)) return;
      view.requestMeasure({ read() {}, write() { enhanceJournal(view.dom); } });
    }
    update(u) {
      const live = isLive(u.state);
      if (live !== this.live || u.docChanged || u.viewportChanged || u.selectionSet || u.focusChanged
        || u.transactions.some((t) => t.effects.some((e) => e.is(refresh)))) {
        this.live = live;
        Object.assign(this, build(u.view));
      }
      this.enhance(u.view);
    }
  }, { decorations: (p) => p.deco });

  const atomic = EditorView.atomicRanges.of((view) => {
    const p = view.plugin(decorations);
    return p ? p.atomic : Decoration.none;
  });

  // The cursor never lands inside the hidden metadata tail, so typing at the end of a task
  // goes before "[dur:: …] ^task-…" and the line stays readable for the app.
  const clamp = EditorState.transactionFilter.of((tr) => {
    if (!tr.selection || tr.annotation(skipClamp) || !active(tr.startState)) return tr;
    const doc = tr.newDoc;
    const before = tr.startState.selection.main.head;
    let changed = false;
    const ranges = tr.selection.ranges.map((r) => {
      if (!r.empty) return r;
      const line = doc.lineAt(r.head);
      const tail = metaTail(line.text);
      if (tail < 0 || r.head <= line.from + tail) return r;
      changed = true;
      if (!tr.docChanged && before === line.from + tail && r.head > before && line.number < doc.lines) {
        return EditorSelection.cursor(line.to + 1);
      }
      return EditorSelection.cursor(line.from + tail);
    });
    if (!changed) return tr;
    return [tr, { selection: EditorSelection.create(ranges, tr.selection.mainIndex), sequential: true }];
  });

  const atTail = (view) => {
    const s = view.state.selection;
    if (!active(view.state) || s.ranges.length !== 1 || !s.main.empty) return null;
    const line = view.state.doc.lineAt(s.main.head);
    const tail = metaTail(line.text);
    return tail >= 0 && s.main.head === line.from + tail ? line : null;
  };
  const keys = Prec.highest(keymap.of([
    {
      // A new task starts after the metadata, not with it: Obsidian's own Enter then runs as usual.
      key: 'Enter',
      run: (view) => {
        const line = atTail(view);
        if (line) view.dispatch({ selection: { anchor: line.to }, annotations: skipClamp.of(true) });
        return false;
      },
    },
    { key: 'Delete', run: (view) => !!atTail(view) },
  ]));

  // Obsidian selects the whole <details> block when its rendered text is clicked, so the next
  // keystroke would replace the entire journal. Put a plain cursor at the clicked word instead.
  plugin.journalMouseDown = (event) => {
      const editor = event.target instanceof Element && event.target.closest('.cm-editor');
      const view = editor && EditorView.findFromDOM(editor);
      if (!view) return;
      if (!active(view.state) || event.button !== 0 || event.shiftKey || event.metaKey || event.ctrlKey || event.altKey) return;
      const embed = event.target instanceof Element && event.target.closest('.cm-html-embed');
      if (!embed || !embed.querySelector('details.daily-journal') || event.target.closest('summary')) return;
      let blockFrom;
      try { blockFrom = view.posAtDOM(embed); } catch (e) { return; }
      const doc = view.state.doc;
      const first = doc.lineAt(blockFrom);
      let last = first;
      while (last.number < doc.lines && !/^\s*<\/details>\s*$/i.test(last.text)) last = doc.line(last.number + 1);
      const source = doc.sliceString(first.from, last.to);

      let pos = -1;
      const caret = document.caretRangeFromPoint && document.caretRangeFromPoint(event.clientX, event.clientY);
      const node = caret && caret.startContainer;
      const inText = node && node.parentElement && !node.parentElement.closest('.fd-jr-name, .fd-jr-ph, .fd-jcard-head');
      if (inText && node.nodeType === Node.TEXT_NODE && node.nodeValue.trim()) {
        const at = source.indexOf(node.nodeValue);
        if (at >= 0) pos = first.from + at + caret.startOffset;
      }
      if (pos < 0) {
        // Clicked beside the text (an empty field, the gap under a line): end of that journal line.
        const row = event.target.closest('[data-field]');
        const li = event.target.closest('li');
        const label = row ? `${row.dataset.field}:` : li && li.textContent.trim().split(/\s+/)[0];
        const at = label ? source.indexOf(label) : -1;
        pos = at >= 0 ? doc.lineAt(first.from + at).to : doc.line(Math.min(first.number + 3, last.number)).to;
      }
      event.preventDefault();
      event.stopPropagation();
      view.focus();
      view.dispatch({ selection: { anchor: pos }, scrollIntoView: false });
  };

  // "- День:" + typing reads as "- День: текст" in the file, like the app writes it.
  const fieldSpace = EditorView.inputHandler.of((view, from, to, text) => {
    if (!active(view.state) || from !== to || !text || /^\s/.test(text)) return false;
    const line = view.state.doc.lineAt(from);
    if (from !== line.to || !/^\s*[-*+]\s+(Утро|День|Вечер|Выводы|Главное|Если помешает):$/.test(line.text)) return false;
    view.dispatch({ changes: { from, insert: ' ' + text }, selection: { anchor: from + 1 + text.length }, userEvent: 'input.type' });
    return true;
  });

  // Typing "10:15" in a task adds the ⏳ the app's parser needs: there's no such key on a keyboard.
  const timeMark = EditorView.inputHandler.of((view, from, to, text) => {
    if (!/^\d$/.test(text)) return false;
    const line = view.state.doc.lineAt(from);
    if (!TASK_LINE.test(line.text) || /[⏳🕐]|\[time::/.test(line.text)) return false;
    const before = line.text.slice(0, from - line.from) + text;
    const m = /(^|[\s(])([01]?\d|2[0-3]):[0-5]\d$/.exec(before);
    if (!m) return false;
    const timeStart = line.from + m.index + m[1].length;
    view.dispatch({
      changes: [{ from: timeStart, insert: '⏳ ' }, { from, to, insert: text }],
      selection: { anchor: from + 2 + text.length },
      userEvent: 'input.type',
    });
    return true;
  });

  return [decorations, lifeHeader, atomic, clamp, keys, timeMark, fieldSpace];
}

function hideInReading(el) {
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const n of nodes) {
    if (!n.parentElement || !n.parentElement.closest('li.task-list-item')) continue;
    const cleaned = n.nodeValue.replace(HIDE_FIELDS, '').replace(TIME_MARK, '');
    if (cleaned !== n.nodeValue) n.nodeValue = cleaned;
  }
}

const JOURNAL_FIELDS = ['Утро', 'День', 'Вечер', 'Выводы'];
const JOURNAL_ROW = /^([ \t]*[-*+][ \t]+)(Утро|День|Вечер|Выводы):[ \t]?/;
const JOURNAL_LOOK = {
  'Утро': { icon: 'sunrise', hint: 'С чем входишь в день?' },
  'День': { icon: 'sun', hint: 'Что происходит, что сдвинулось?' },
  'Вечер': { icon: 'moon', hint: 'Как прошёл день?' },
  'Выводы': { icon: 'lightbulb', hint: 'Что заберёшь в завтра?' },
};
// "## FocusDeck" in a daily note: the app's morning ritual. Two of its lines are app flags, not reading.
const RITUAL_ROW = /^([ \t]*[-*+][ \t]+)([^:\n]+):[ \t]?/;
const RITUAL_LOOK = {
  'Главное': { icon: 'star', label: 'Главное' },
  'Если помешает': { icon: 'shield', label: 'Если помешает' },
  'День отдыха': { icon: 'coffee', label: 'День отдыха' },
};
// Anything else the app keeps here ("Утро подтверждено", "Вечер завершён", …) is its own bookkeeping.
const ritualFlag = (key, value) => !RITUAL_LOOK[key] || (key === 'День отдыха' && !/^да/i.test(value.trim()));
const LIFE_SERVICE = new Set(['Связи FocusDeck', 'История решений', 'История пересмотров']);
const KIND_SINGULAR = { goal: 'Цель', project: 'Проект', area: 'Область жизни', decision: 'Решение', review: 'Обзор' };

function ritualLines(doc) {
  const out = { head: -1, body: new Set() };
  for (let n = 1; n <= doc.lines; n++) {
    const t = doc.line(n).text;
    if (out.head < 0) { if (/^##\s+FocusDeck\s*$/.test(t)) out.head = n; continue; }
    if (/^#{1,6}\s/.test(t)) break;
    out.body.add(n);
  }
  return out;
}

// Frontmatter of a FocusDeck life note, read from the text (flat "key: value" lines only).
function lifeFrontmatter(doc) {
  if (doc.lines < 2 || doc.line(1).text !== '---') return null;
  const fm = {};
  for (let n = 2; n <= doc.lines; n++) {
    const t = doc.line(n).text;
    if (t === '---') return String(fm.focusdeck) === '1' ? { fm, end: n } : null;
    const m = /^([A-Za-z_]+):\s*"?(.*?)"?\s*$/.exec(t);
    if (m) fm[m[1]] = m[2];
  }
  return null;
}

// The rendered <details> journal (cursor elsewhere, or reading view) becomes the same card as in editing.
function enhanceJournal(root) {
  for (const det of root.querySelectorAll('details.daily-journal:not([data-fd])')) {
    det.setAttribute('data-fd', '1');
    const rows = [];
    let cur = null;
    for (const raw of det.textContent.split('\n')) {
      const m = /^\s*[-*+]\s+(Утро|День|Вечер|Выводы):\s?(.*)$/.exec(raw);
      if (m) { cur = { key: m[1], text: m[2].trim() }; rows.push(cur); }
      else if (cur && raw.trim() && !/^\s*(<|Дневник)/.test(raw)) cur.text += '\n' + raw.trim();
    }
    if (!rows.length) continue;
    const card = createDiv('fd-jcard');
    const head = card.createDiv('fd-jcard-head');
    head.createSpan({ text: 'Дневник' });
    head.createSpan({ cls: 'fd-jcard-count', text: `${rows.filter((r) => r.text).length} из ${rows.length}` });
    for (const r of rows) {
      const row = card.createDiv({ cls: 'fd-jrow' + (r.text ? '' : ' is-empty'), attr: { 'data-field': r.key } });
      const label = row.createDiv('fd-jr-name');
      setIcon(label.createSpan('fd-jr-icon'), JOURNAL_LOOK[r.key].icon);
      label.createSpan({ text: r.key });
      if (r.text) row.createDiv({ cls: 'fd-jr-text', text: r.text });
      else row.createDiv({ cls: 'fd-jr-ph', text: JOURNAL_LOOK[r.key].hint });
    }
    det.addClass('fd-jhidden');
    det.after(card);
  }
}
// Reading view splits the <details> block: the journal list arrives as a plain list of its own.
function journalListInReading(el) {
  for (const ul of el.querySelectorAll('ul')) {
    if (ul.closest('.fd-jcard')) continue;
    const items = [...ul.children].filter((li) => li.tagName === 'LI');
    const rows = items.map((li) => /^(Утро|День|Вечер|Выводы):\s*([\s\S]*)$/.exec(li.textContent.trim()));
    if (rows.length < 2 || rows.some((m) => !m)) continue;
    const card = createDiv('fd-jcard');
    const head = card.createDiv('fd-jcard-head');
    head.createSpan({ text: 'Дневник' });
    head.createSpan({ cls: 'fd-jcard-count', text: `${rows.filter((m) => m[2].trim()).length} из ${rows.length}` });
    for (const m of rows) {
      const row = card.createDiv({ cls: 'fd-jrow' + (m[2].trim() ? '' : ' is-empty'), attr: { 'data-field': m[1] } });
      const label = row.createDiv('fd-jr-name');
      setIcon(label.createSpan('fd-jr-icon'), JOURNAL_LOOK[m[1]].icon);
      label.createSpan({ text: m[1] });
      if (m[2].trim()) row.createDiv({ cls: 'fd-jr-text', text: m[2].trim() });
      else row.createDiv({ cls: 'fd-jr-ph', text: JOURNAL_LOOK[m[1]].hint });
    }
    ul.replaceWith(card);
  }
}
function ritualInReading(el) {
  for (const h of el.querySelectorAll('h2')) {
    if (h.textContent.trim() === 'FocusDeck') { h.empty(); setIcon(h.createSpan('fd-jr-icon'), 'target'); h.appendText(' Фокус дня'); h.addClass('fd-rt-h'); }
  }
  for (const ul of el.querySelectorAll('ul')) {
    const items = [...ul.children].filter((li) => li.tagName === 'LI');
    const parsed = items.map((li) => [li, /^(Главное|Если помешает|День отдыха|Утро подтверждено|Вечер завершён):\s*(.*)$/s.exec(li.textContent.trim())]);
    if (!parsed.length || parsed.some(([, m]) => !m)) continue;
    ul.addClass('fd-rt-list');
    for (const [li, m] of parsed) {
      if (ritualFlag(m[1], m[2])) { li.addClass('fd-rt-hidden'); continue; }
      li.empty();
      const name = li.createDiv('fd-jr-name');
      setIcon(name.createSpan('fd-jr-icon'), RITUAL_LOOK[m[1]].icon);
      name.createSpan({ text: RITUAL_LOOK[m[1]].label });
      li.createDiv({ cls: 'fd-jr-text', text: m[2] });
      if (m[1] === 'Главное') li.addClass('fd-rt-main');
    }
  }
}
function unenhanceJournal(root) {
  root.querySelectorAll('.fd-jcard').forEach((c) => c.remove());
  root.querySelectorAll('details.daily-journal[data-fd]').forEach((d) => { d.removeAttribute('data-fd'); d.removeClass('fd-jhidden'); });
}
const LIFE_PAGE = 'Навигация/Жизнь.base';
// Same numbers as the app: PlanningViewModel.DEFAULT_TASK_MINUTES and RitualEngine's plan window.
const DEFAULT_TASK_MINUTES = 50;
const PLAN_START = 8 * 60;
const PLAN_END = 23 * 60;
const PLAN_REQUIRED = 540;
const DURATIONS = [15, 30, 45, 60, 90, 120];

const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const hhmm = (m) => `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;
const dayLabel = (d) => d.toLocaleDateString('ru', { weekday: 'long', day: 'numeric', month: 'long' });
const shiftDay = (d, n) => { const x = new Date(d.getFullYear(), d.getMonth(), d.getDate()); x.setDate(x.getDate() + n); return x; };
const nowMinute = () => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); };
const minutesText = (m) => (m >= 60 ? `${Math.floor(m / 60)} ч${m % 60 ? ` ${m % 60} мин` : ''}` : `${m} мин`);
const shortMinutes = (m) => (m >= 60 ? `${Math.floor(m / 60)} ч${m % 60 ? ` ${m % 60}` : ''}` : `${m} м`);
// RitualEngine.meaningfulTitle: three letters at least, and not one letter repeated.
const meaningfulTitle = (s) => {
  const letters = [...(s || '')].filter((c) => /\p{L}/u.test(c));
  return letters.length >= 3 && new Set(letters.map((c) => c.toLowerCase())).size >= 2;
};
// RitualEngine.journalReadiness: the owner's fixed thresholds for the ritual's journal field.
const JOURNAL_MIN_WORDS = 100;
const JOURNAL_MIN_CHARS = 600;
const JOURNAL_MIN_DISTINCT = 20;
function journalReadiness(text) {
  const words = ((text || '').match(/\p{L}{2,}/gu) || []).map((w) => w.toLowerCase());
  const chars = [...(text || '')].filter((c) => /[\p{L}\p{Nd}]/u.test(c)).length;
  const distinct = new Set(words).size;
  return {
    words: words.length, chars, distinct,
    ready: words.length >= JOURNAL_MIN_WORDS && chars >= JOURNAL_MIN_CHARS && distinct >= JOURNAL_MIN_DISTINCT,
    fraction: Math.max(0, Math.min(1, words.length / JOURNAL_MIN_WORDS, chars / JOURNAL_MIN_CHARS, distinct / JOURNAL_MIN_DISTINCT)),
  };
}
// The app's timeline colours come from the position in the day, not from a category.
const hueAt = (m) => (m < 12 * 60 ? 'var(--fd-t-morning)' : m < 17 * 60 ? 'var(--fd-t-day)' : m < 19 * 60 ? 'var(--fd-t-dusk)' : 'var(--fd-t-evening)');
function relDay(date) {
  const n = Math.round((shiftDay(date, 0) - shiftDay(new Date(), 0)) / 86_400_000);
  return { '-1': 'вчера', 0: 'сегодня', 1: 'завтра' }[n] || null;
}

function sectionLines(lines, heading) {
  const start = lines.findIndex((l) => l.trim() === heading);
  if (start < 0) return null;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (/^#{1,6}\s/.test(lines[i].trimStart())) { end = i; break; }
  }
  return { start, end };
}

const GOAL = /\[goal::\s*([A-Za-z0-9_-]+)]/;
const AREA = /\[area::\s*([A-Za-z0-9_-]+)]/;
const NOTE_LINK = /\[focusdeck-note::\s*(\[\[[^\r\n[\]]+]])\s*]/;
const NOTE_LINK_ALL = new RegExp(NOTE_LINK.source, 'g');

function parseTasks(lines) {
  const range = sectionLines(lines, '## Задачи');
  if (!range) return { tasks: [], range: null };
  const tasks = [];
  for (let i = range.start + 1; i < range.end; i++) {
    const m = TASK.exec(lines[i]);
    if (!m) continue;
    let title = m[2];
    NOISE.forEach((re) => { title = title.replace(re, ' '); });
    title = title.replace(/\s+/g, ' ').trim();
    if (!title) continue; // an empty "- [ ]" is a placeholder, not a task
    const t = TIME.exec(m[2]);
    const start = t ? Number(t[1] ?? t[3]) * 60 + Number(t[2] ?? t[4]) : null;
    const dur = DUR.exec(m[2]);
    tasks.push({
      line: i, raw: lines[i], title, done: m[1] !== ' ', start,
      minutes: dur ? Number(dur[1]) : null,
      project: PROJECT.exec(m[2])?.[1] ?? null,
      goal: GOAL.exec(m[2])?.[1] ?? null,
      area: AREA.exec(m[2])?.[1] ?? null,
      noteLink: NOTE_LINK.exec(m[2])?.[1] ?? null,
      id: TASK_ID.exec(m[2])?.[1] ?? null,
    });
  }
  return { tasks, range };
}

function ritualValue(lines, key) {
  const range = sectionLines(lines, '## FocusDeck');
  if (!range) return null;
  for (let i = range.start + 1; i < range.end; i++) {
    const s = lines[i].trimStart();
    if (s.startsWith(`- ${key}:`)) return s.slice(key.length + 3).trim() || null;
  }
  return null;
}

function journalState(lines) {
  const j = readJournal(lines);
  if (!j) return null;
  const filled = {};
  for (const f of JOURNAL_FIELDS) filled[f] = !!j[f];
  return filled;
}

function lifeSection(text, heading) {
  const lines = text.split('\n');
  const range = sectionLines(lines, `## ${heading}`);
  return range ? lines.slice(range.start + 1, range.end).join('\n').trim() : '';
}

// ---------- Daily note writes, line for line the same as DailyNoteParser.kt / RitualNotes ----------

const TIME_W = /[⏳🕐]\s*(\d{1,2}):(\d{2})/gu;
const INLINE_TIME_W = /\[time::\s*(\d{1,2}):(\d{2})]/g;
const DUR_W = /\[dur::\s*(\d+)]/g;
const PROJECT_W = /\[project::\s*([A-Za-z0-9_-]+)]/g;
const GOAL_W = /\[goal::\s*([A-Za-z0-9_-]+)]/g;
const AREA_W = /\[area::\s*([A-Za-z0-9_-]+)]/g;
const ID_W = /\^task-([A-Za-z0-9_-]+)\s*$/;
const SAFE_ID = /^[A-Za-z0-9_-]+$/;

/** preservingNoteLinks: a wiki link's text is never touched by the metadata edits around it. */
function keepLinks(line, fn) {
  const links = [];
  const guarded = line.replace(NOTE_LINK_ALL, (m) => `\u0000${links.push(m) - 1}\u0000`);
  return fn(guarded).replace(/\u0000(\d+)\u0000/g, (_, i) => links[Number(i)]);
}
const idOf = (line) => (ID_W.exec(line)?.[0] || '').trim();
// Removing a mark takes the space before it too, so edits don't leave "title  [dur:: 30]" gaps behind.
const lead = (re) => new RegExp(`[ \\t]*${re.source}`, re.flags);
const [TIME_X, INLINE_TIME_X, DUR_X, PROJECT_X, GOAL_X, AREA_X, ID_X, LINK_X] = [TIME_W, INLINE_TIME_W, DUR_W, PROJECT_W, GOAL_W, AREA_W, ID_W, NOTE_LINK_ALL].map(lead);

const lineOps = {
  toggle: (line, done) => (done ? line.replace(/-\s\[\s]/, '- [x]') : line.replace(/-\s\[[xX]]/, '- [ ]')),
  schedule: (line, start, minutes) => keepLinks(line, (b) => {
    const id = idOf(b);
    return b.replace(TIME_X, '').replace(INLINE_TIME_X, '').replace(DUR_X, '').replace(ID_X, '').trimEnd()
      + ` ⏳ ${hhmm(start)}` + (minutes ? ` [dur:: ${minutes}]` : '') + (id ? ` ${id}` : '');
  }),
  unschedule: (line) => keepLinks(line, (b) => b.replace(TIME_X, '').replace(INLINE_TIME_X, '').replace(DUR_X, '').trimEnd()),
  duration: (line, minutes) => keepLinks(line, (b) => {
    const id = idOf(b);
    return b.replace(DUR_X, '').replace(ID_X, '').trimEnd() + ` [dur:: ${minutes}]` + (id ? ` ${id}` : '');
  }),
  rename(line, title) {
    const m = TASK.exec(line);
    if (!m) return line;
    const raw = m[2].replace(NOTE_LINK_ALL, '');
    const one = (re) => { re.lastIndex = 0; const x = re.exec(raw); re.lastIndex = 0; return x ? ` ${x[0].trim()}` : ''; };
    const suffix = (one(TIME_W) || one(INLINE_TIME_W)) + one(DUR_W) + one(PROJECT_W) + one(GOAL_W) + one(AREA_W)
      + (NOTE_LINK.exec(m[2]) ? ` ${NOTE_LINK.exec(m[2])[0]}` : '') + (idOf(raw) ? ` ${idOf(raw)}` : '');
    const indent = /^\s*/.exec(line)[0];
    return `${indent}- [${m[1].toLowerCase() === 'x' ? 'x' : ' '}] ${title.replace(/[\r\n]+/g, ' ').trim()}${suffix}`;
  },
  link(line, p, fallbackId) {
    const id = ID_W.exec(line)?.[1] || fallbackId;
    const clean = line.replace(LINK_X, '').replace(PROJECT_X, '').replace(GOAL_X, '').replace(AREA_X, '').replace(ID_X, '').trimEnd();
    return clean + (p.projectId ? ` [project:: ${p.projectId}]` : '') + (p.goalId ? ` [goal:: ${p.goalId}]` : '')
      + (p.areaId ? ` [area:: ${p.areaId}]` : '') + (p.noteLink ? ` [focusdeck-note:: ${p.noteLink}]` : '') + ` ^task-${id}`;
  },
};

/** DailyNoteParser.addTask: after the last task of "## Задачи", never over the blank line under the heading. */
function addTaskLines(lines, t) {
  if (![t.projectId, t.goalId, t.areaId].filter(Boolean).every((x) => SAFE_ID.test(x))) throw new Error('Некорректная связь задачи');
  let h = lines.findIndex((l) => l.trim() === '## Задачи');
  if (h < 0) {
    if (lines.length && lines[lines.length - 1].trim()) lines.push('');
    lines.push('## Задачи', '');
    h = lines.length - 2;
  }
  let end = lines.length;
  for (let i = h + 1; i < lines.length; i++) if (lines[i].trimStart().startsWith('#')) { end = i; break; }
  let last = -1;
  for (let i = h + 1; i < end; i++) if (TASK.test(lines[i])) last = i;
  const at = Math.min(lines.length, last >= 0 ? last + 1 : lines[h + 1] !== undefined && !lines[h + 1].trim() ? h + 2 : h + 1);
  const suffix = (t.start != null ? ` ⏳ ${hhmm(t.start)}` : '') + (t.minutes ? ` [dur:: ${t.minutes}]` : '')
    + (t.projectId ? ` [project:: ${t.projectId}]` : '') + (t.goalId ? ` [goal:: ${t.goalId}]` : '')
    + (t.areaId ? ` [area:: ${t.areaId}]` : '') + (t.noteLink ? ` [focusdeck-note:: ${t.noteLink}]` : '')
    + ` ^task-${t.id || crypto.randomUUID()}`;
  lines.splice(at, 0, `- [ ] ${t.title.replace(/[\r\n]+/g, ' ').trim()}${suffix}`);
  if ((lines[at + 1] || '').trimStart().startsWith('#')) lines.splice(at + 1, 0, '');
}

/** RitualNotes.put */
function putRitual(lines, key, value) {
  const clean = value.replace(/[\r\n]+/g, ' ').trim();
  const start = lines.findIndex((l) => l.trim() === '## FocusDeck');
  if (start < 0) {
    if (lines.length && lines[lines.length - 1].trim()) lines.push('');
    lines.push('## FocusDeck', `- ${key}: ${clean}`, '');
    return;
  }
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) if (lines[i].trimStart().startsWith('#')) { end = i; break; }
  for (let i = start + 1; i < end; i++) {
    if (lines[i].trimStart().startsWith(`- ${key}:`)) { lines[i] = `- ${key}: ${clean}`; return; }
  }
  // Right under the section's last line, not after the blank line that closes it.
  let at = end;
  while (at > start + 1 && !lines[at - 1].trim()) at--;
  lines.splice(at, 0, `- ${key}: ${clean}`);
}

/** DailyNoteParser.parseJournal: continuation lines are indented by two spaces. */
function readJournal(lines) {
  const open = lines.findIndex((l) => l.includes('<details class="daily-journal">'));
  if (open < 0) return null;
  const close = lines.findIndex((l, i) => i > open && l.trim() === '</details>');
  if (close < 0) return null;
  const body = lines.slice(open, close);
  const out = {};
  for (const label of JOURNAL_FIELDS) {
    const i = body.findIndex((l) => l.trim().startsWith(`- ${label}:`));
    if (i < 0) { out[label] = ''; continue; }
    const parts = [body[i].slice(body[i].indexOf(`${label}:`) + label.length + 1).trim()];
    for (let k = i + 1; k < body.length && body[k].startsWith('  '); k++) parts.push(body[k].slice(2));
    out[label] = parts.join('\n').trim();
  }
  return out;
}

const JOURNAL_BROKEN = 'Разметка дневника в заметке повреждена — поправь блок <details> вручную';
/** The one canonical <details class="daily-journal"> block, checked the way writeJournal checks it. */
function journalBounds(lines) {
  const text = lines.join('\n');
  const tags = [...text.matchAll(/<\s*(\/?)\s*details\b[^>]*>/gi)];
  if ([...text.matchAll(/<\s*\/?\s*details\b/gi)].length !== tags.length) throw new Error(JOURNAL_BROKEN);
  const stack = [];
  let open = null, close = null;
  for (const t of tags) {
    const line = text.slice(0, t.index).split('\n').length - 1;
    if (!t[1]) {
      if (stack.some(Boolean)) throw new Error(JOURNAL_BROKEN);
      const mine = /\bdaily-journal\b/i.test(t[0]);
      if (mine) {
        if (open != null || t[0] !== '<details class="daily-journal">' || lines[line].trim() !== t[0]) throw new Error(JOURNAL_BROKEN);
        open = line;
      }
      stack.push(mine);
    } else {
      if (!stack.length) throw new Error(JOURNAL_BROKEN);
      if (stack.pop()) {
        if (lines[line].trim() !== '</details>') throw new Error(JOURNAL_BROKEN);
        close = line;
      }
    }
  }
  if (stack.length || (open == null) !== (close == null)) throw new Error(JOURNAL_BROKEN);
  return open == null ? null : { open, close };
}

/** writeJournal for one field: only that field's lines change, the wrapper and other fields stay byte for byte. */
function writeJournalField(lines, label, value) {
  if (/<\s*\/?\s*details\b/i.test(value)) throw new Error('В тексте дневника не может быть тега details');
  const text = value.replace(/\r/g, '').replace(/\s+$/, '').replace(/^\s+/, '');
  let b = journalBounds(lines);
  if (!b) {
    if (lines.length && lines[lines.length - 1].trim()) lines.push('');
    lines.push('<details class="daily-journal">', '<summary>Дневник</summary>', '</details>');
    b = { open: lines.length - 3, close: lines.length - 1 };
  }
  const find = (l) => {
    const hits = [];
    for (let i = b.open + 1; i < b.close; i++) if (lines[i].trim().startsWith(`- ${l}:`)) hits.push(i);
    if (hits.length > 1 || (hits.length && lines[hits[0]].startsWith('  '))) throw new Error(JOURNAL_BROKEN);
    return hits.length ? hits[0] : -1;
  };
  JOURNAL_FIELDS.forEach((l, k) => {
    if (find(l) >= 0) return;
    const later = JOURNAL_FIELDS.slice(k + 1).map(find).filter((i) => i >= 0);
    lines.splice(later.length ? Math.min(...later) : b.close, 0, `- ${l}:`);
    b.close++;
  });
  const at = find(label);
  let end = at + 1;
  while (end < b.close && lines[end].startsWith('  ')) end++;
  const rows = text.split('\n');
  const repl = [`- ${label}:` + (rows[0] ? ` ${rows[0]}` : ''), ...rows.slice(1).map((r) => `  ${r}`)];
  lines.splice(at, end - at, ...repl);
  if ((readJournal(lines) || {})[label] !== text) throw new Error('Текст не удаётся сохранить без искажений — проверь отступы');
}

/** "> - Экранное время (телефон): **8 ч 41 мин**." lines of the day summary callout. */
function summaryFacts(lines) {
  const start = lines.findIndex((l) => l.trimStart().startsWith('> [!summary]'));
  if (start < 0) return [];
  const out = [];
  for (let i = start + 1; i < lines.length && lines[i].trimStart().startsWith('>'); i++) {
    const m = /^>\s*[-*]\s*([^:]+):\s*(.+?)\.?\s*$/.exec(lines[i].trim());
    if (m) out.push({ label: m[1].replace(/\s*\(телефон\)/, '').trim(), value: m[2].replace(/\*\*/g, '').trim() });
  }
  return out;
}

/** DailyNoteParser.template: the same skeleton the app creates, so the Mac script and plugins find their places. */
function dayTemplate(date) {
  return `---\nday: ${ymd(date)}\ntags:\n  - daily\n  - planning\n---\n\n## Задачи\n\n# Day planner\n\n\`\`\`movebreak-daily-plan\n\`\`\`\n\n## Итоги дня\n\n> [!summary] Краткая сводка дня\n\n<details class="daily-journal">\n<summary>Дневник</summary>\n\n- Утро:\n- День:\n- Вечер:\n- Выводы:\n\n</details>\n`;
}

function dailyFileOf(app, date) {
  const name = `00 - ${ymd(date)}.md`;
  const root = app.vault.getAbstractFileByPath(name);
  if (root instanceof TFile) return root;
  // Same rule as VaultRepository: root first, then one level of subfolders.
  return app.vault.getMarkdownFiles().find((f) => f.name === name && f.path.split('/').length === 2) ?? null;
}
async function ensureDailyFile(app, date) {
  // New days are created in the root, like the app does.
  return dailyFileOf(app, date) || app.vault.create(`00 - ${ymd(date)}.md`, dayTemplate(date));
}

/** DailyNoteParser.resolveTask: the id decides; without one, the same line and title, else a unique title. */
function findTask(lines, task) {
  const { tasks } = parseTasks(lines);
  if (task.id) { const same = tasks.filter((t) => t.id === task.id); return same.length === 1 ? same[0] : null; }
  const exact = tasks.find((t) => t.line === task.line && t.title === task.title);
  if (exact) return exact;
  const same = tasks.filter((t) => t.title === task.title && t.project === task.project && t.goal === task.goal);
  return same.length === 1 ? same[0] : null;
}

/**
 * Change one task line in place, re-found in the fresh file. A task without an id gets one on its first
 * edit (the app does the same when linking), so the next edit still finds it after a rename.
 * `fn` returns the new line, or null to remove the line.
 */
async function editTask(app, file, task, fn) {
  let ok = false;
  await app.vault.process(file, (data) => {
    const lines = data.split('\n');
    const t = findTask(lines, task);
    if (!t) return data;
    let line = lines[t.line];
    if (!t.id) { task.id = crypto.randomUUID(); line = line.replace(/\s+$/, '') + ` ^task-${task.id}`; }
    const next = fn(line, t, lines);
    if (next === null) lines.splice(t.line, 1);
    else lines[t.line] = next;
    ok = true;
    return lines.join('\n');
  });
  if (!ok) new Notice('Задача изменилась в заметке — список обновлён');
  return ok;
}

async function addTask(app, date, t) {
  const file = await ensureDailyFile(app, date);
  await app.vault.process(file, (data) => {
    const lines = data.split('\n');
    addTaskLines(lines, t);
    return lines.join('\n');
  });
  return file;
}

/** VaultDayStore.copyUnfinishedTaskToDay: today's entry stays as it was; the target day gets an unscheduled copy once. */
async function copyTaskToDay(app, fromFile, task, toDate) {
  const fresh = findTask((await app.vault.read(fromFile)).split('\n'), task);
  if (!fresh || fresh.done) { new Notice('Задача уже закрыта или изменилась'); return false; }
  const target = await ensureDailyFile(app, toDate);
  let added = false;
  await app.vault.process(target, (data) => {
    const lines = data.split('\n');
    if (parseTasks(lines).tasks.some((t) => t.title === fresh.title && t.project === fresh.project && t.goal === fresh.goal)) return data;
    addTaskLines(lines, { title: fresh.title, minutes: fresh.minutes, projectId: fresh.project, goalId: fresh.goal, areaId: fresh.area, noteLink: fresh.noteLink });
    added = true;
    return lines.join('\n');
  });
  return added ? 'added' : 'present';
}

async function setRitual(app, date, pairs) {
  const file = await ensureDailyFile(app, date);
  await app.vault.process(file, (data) => {
    const lines = data.split('\n');
    for (const [k, v] of pairs) putRitual(lines, k, v);
    return lines.join('\n');
  });
}

/** "10:30 созвон 45м", "созвон 1ч 10:30": time and duration typed with the title, like the app's quick add. */
function parseQuickAdd(input) {
  let s = ` ${input.replace(/[\r\n]+/g, ' ')} `;
  let start = null, minutes = null;
  const cut = (m) => { s = s.slice(0, m.index) + s.slice(m.index + m[0].length); };
  const tm = /\s(?:в\s)?([01]?\d|2[0-3])[:.]([0-5]\d)(?=\s)/.exec(s);
  if (tm) { start = Number(tm[1]) * 60 + Number(tm[2]); cut(tm); }
  const hm = /\s(\d{1,2}(?:[.,]5)?)\s?(?:ч|час|часа|часов|h)(?:\s?(\d{1,2})\s?(?:м|мин|min|m))?(?=\s)/i.exec(s);
  const mm = /\s(\d{1,3})\s?(?:м|мин|минут|минуты|min|m)(?=\s)/i.exec(s);
  const ok = (n) => n >= 5 && n <= 16 * 60;
  if (hm && ok(Math.round(Number(hm[1].replace(',', '.')) * 60) + Number(hm[2] || 0))) {
    minutes = Math.round(Number(hm[1].replace(',', '.')) * 60) + Number(hm[2] || 0); cut(hm);
  } else if (mm && ok(Number(mm[1]))) { minutes = Number(mm[1]); cut(mm); }
  return { title: s.replace(/\s+/g, ' ').trim(), start, minutes };
}

/** The first gap of `minutes` from `after` that no other timed task of the day covers. */
function freeSlot(tasks, minutes, after, skip) {
  const busy = tasks.filter((t) => t.start != null && t !== skip && !(skip && skip.id && t.id === skip.id))
    .map((t) => [t.start, t.start + (t.minutes ?? DEFAULT_TASK_MINUTES)]).sort((a, b) => a[0] - b[0]);
  let at = Math.max(after, PLAN_START);
  for (const [s, e] of busy) {
    if (e <= at) continue;
    if (s - at >= minutes) break;
    at = Math.max(at, e);
  }
  return at + minutes <= 24 * 60 ? at : null;
}

/** RitualEngine.validTimedTask */
const validTimed = (t) => t.start != null && t.start >= 0 && t.start <= 1439
  && (t.minutes ?? DEFAULT_TASK_MINUTES) >= 1 && (t.minutes ?? DEFAULT_TASK_MINUTES) <= 1440 - t.start;

/** RitualEngine.planReadiness: the union of named intervals inside 08:00–23:00. */
function planCoverage(tasks) {
  let end = PLAN_START, minutes = 0, overlap = false;
  for (const t of tasks.filter((x) => validTimed(x) && meaningfulTitle(x.title)).sort((a, b) => a.start - b.start)) {
    const s = Math.max(PLAN_START, t.start);
    const e = Math.min(PLAN_END, t.start + (t.minutes ?? DEFAULT_TASK_MINUTES));
    if (e <= s) continue;
    if (s < end) overlap = true;
    minutes += Math.max(0, e - Math.max(s, end));
    end = Math.max(end, e);
  }
  return { minutes, overlap };
}

/** planReadiness as a whole: coverage, a main thing that is itself a valid timed task, no broken entries. */
function planReadiness(lines) {
  const { tasks } = parseTasks(lines);
  const cov = planCoverage(tasks);
  const priority = ritualValue(lines, 'Главное');
  const valid = tasks.filter((t) => validTimed(t) && meaningfulTitle(t.title));
  const invalid = tasks.filter((t) => t.start != null && !valid.includes(t));
  const mainOk = !!priority && valid.some((t) => t.title === priority);
  return { ...cov, priority, mainOk, invalid, ready: cov.minutes >= PLAN_REQUIRED && mainOk && !invalid.length };
}

/** RitualEngine.ritualWindow; before 23:00 the evening is prepared ahead, as the app's manual opening does. */
function ritualWindow(now = new Date()) {
  const h = now.getHours();
  if (h < 6) return { phase: 'evening', journal: shiftDay(now, -1), plan: shiftDay(now, 0) };
  if (h >= 21) return { phase: 'evening', journal: shiftDay(now, 0), plan: shiftDay(now, 1) };
  return { phase: 'morning', journal: shiftDay(now, 0), plan: shiftDay(now, 0) };
}

/** Active projects with their next step, as LifeHierarchy.taskParents links them to a task. */
function activeProjects(app) {
  return lifeIndex(app).filter((x) => x.kind === 'project' && x.status === 'active').map((p) => ({
    ...p,
    parents: {
      projectId: SAFE_ID.test(p.id) ? p.id : null,
      goalId: SAFE_ID.test(p.goalId) ? p.goalId : null,
      areaId: SAFE_ID.test(p.areaId) ? p.areaId : null,
      noteLink: `[[${p.file.path.replace(/\.md$/, '')}|${p.title.replace(/\|/g, ' ').replace(/[[\]]/g, '')}]]`,
    },
  }));
}

// ---------- The panel: Сегодня · План · Цели · Дневник ----------

class FocusDeckView extends ItemView {
  constructor(leaf, plugin) {
    super(leaf);
    this.plugin = plugin;
    this.renderSeq = 0;
    this.screen = 'today';
    this.date = new Date();
    this.drafts = {};
  }
  getViewType() { return VIEW_TYPE; }
  getDisplayText() { return 'FocusDeck'; }
  getIcon() { return 'sun'; }
  getState() { return { ...super.getState(), screen: this.screen }; }
  async setState(state, result) {
    if (state && ['today', 'plan', 'journal'].includes(state.screen)) this.screen = state.screen;
    await super.setState(state, result);
    await this.render();
  }

  async onOpen() {
    const root = this.containerEl.children[1];
    // A refresh never runs under the fingers: whatever is being typed stays, the list catches up on blur.
    this.registerDomEvent(root, 'focusout', () => setTimeout(() => { if (this.pending && !this.typing()) this.render(); }, 50));
    await this.render();
  }

  typing() {
    const a = document.activeElement;
    return !!a && this.containerEl.contains(a) && (a.tagName === 'TEXTAREA' || (a.tagName === 'INPUT' && a.type !== 'checkbox'));
  }
  requestRender() {
    if (this.typing()) { this.pending = true; return; }
    this.render();
  }
  go(screen, date) {
    this.screen = screen;
    if (date) this.date = date;
    this.app.workspace.requestSaveLayout();
    this.render();
  }

  // Opening a note from the phone's drawer should show the note, not keep the drawer over it.
  async openFile(file, eState) {
    const leaf = this.leaf.getRoot() === this.app.workspace.rootSplit ? this.app.workspace.getLeaf('tab') : this.app.workspace.getLeaf(false);
    await leaf.openFile(file, eState ? { eState } : undefined);
    if (this.app.isMobile) this.app.workspace.leftSplit?.collapse?.();
  }

  async render() {
    const seq = ++this.renderSeq;
    this.pending = false;
    const screen = this.screen;
    const date = screen === 'today' ? new Date() : this.date;
    const file = dailyFileOf(this.app, date);
    const text = file ? await this.app.vault.read(file) : null;
    const extra = screen === 'today' ? await this.readTodayExtras(date) : null;
    const week = screen === 'today' ? null : await this.readWeek(date);
    if (screen === 'plan') {
      this.nextSteps = new Map();
      for (const p of activeProjects(this.app)) {
        const text = await this.app.vault.cachedRead(p.file);
        this.nextSteps.set(p.file.path, lifeSection(text, 'Следующее действие').split('\n')[0].replace(/^[-*]\s*(\[.\]\s*)?/, '').trim());
      }
    }
    if (seq !== this.renderSeq) return;

    const root = this.containerEl.children[1];
    const scroll = root.scrollTop;
    root.empty();
    root.addClass('fd-root');
    root.toggleClass('is-mobile', !!this.app.isMobile);
    this.renderHead(root, screen, date, week);
    const lines = text ? text.split('\n') : [];
    const ctx = { date, file, lines, ...parseTasks(lines) };
    if (screen === 'plan') this.renderPlan(root, ctx);
    else if (screen === 'journal') this.renderJournalScreen(root, ctx);
    else this.renderToday(root, ctx, extra);
    root.scrollTop = scroll;
  }

  async readWeek(date) {
    const monday = shiftDay(date, -((date.getDay() + 6) % 7));
    return Promise.all([...Array(7)].map(async (_, i) => {
      const day = await this.readDay(shiftDay(monday, i));
      const tasks = parseTasks(day.lines).tasks;
      const j = readJournal(day.lines);
      return { date: day.date, exists: !!day.file, done: tasks.filter((t) => t.done).length, total: tasks.length,
        journal: j ? JOURNAL_FIELDS.filter((f) => j[f]).length : 0 };
    }));
  }

  renderWeek(root, screen, date, week) {
    const strip = root.createDiv('fd-week');
    const today = ymd(new Date());
    for (const d of week) {
      const key = ymd(d.date);
      const cell = strip.createDiv({ cls: 'fd-wk' + (key === ymd(date) ? ' is-on' : '') + (key === today ? ' is-today' : '') + (d.exists ? '' : ' is-empty') });
      cell.createDiv({ cls: 'fd-wk-wd', text: d.date.toLocaleDateString('ru', { weekday: 'short' }) });
      cell.createDiv({ cls: 'fd-wk-n', text: String(d.date.getDate()) });
      // Plan screen: how much of the day's list is done; journal screen: how many of the four fields are written.
      const frac = screen === 'journal' ? d.journal / 4 : d.total ? d.done / d.total : 0;
      const bar = cell.createDiv('fd-wk-bar');
      if (d.exists && (screen === 'journal' ? d.journal : d.total)) bar.createDiv('fd-wk-fill').style.width = `${Math.max(12, Math.round(frac * 100))}%`;
      cell.setAttr('aria-label', screen === 'journal' ? `Дневник: ${d.journal} из 4` : d.total ? `Дела: ${d.done} из ${d.total}` : 'Без плана');
      cell.onclick = () => this.go(screen, d.date);
    }
  }

  renderHead(root, screen, date, week) {
    const head = root.createDiv('fd-head');
    if (screen === 'today') {
      head.createDiv({ cls: 'fd-date', text: dayLabel(date) });
    } else {
      const sw = head.createDiv('fd-day');
      const arrow = (icon, n, label) => {
        const b = sw.createEl('button', { cls: 'fd-icon-btn', attr: { 'aria-label': label } });
        setIcon(b, icon);
        b.onclick = () => this.go(screen, shiftDay(this.date, n));
      };
      arrow('chevron-left', -1, 'Предыдущий день');
      const label = sw.createDiv({ cls: 'fd-day-label' });
      const rel = relDay(date);
      label.createSpan({ cls: 'fd-day-main', text: date.toLocaleDateString('ru', { weekday: 'short', day: 'numeric', month: 'long' }) });
      if (rel) label.createSpan({ cls: 'fd-day-rel' + (rel === 'сегодня' ? ' is-today' : ''), text: rel });
      label.onclick = () => this.go(screen, new Date());
      label.setAttr('aria-label', 'К сегодняшнему дню');
      arrow('chevron-right', 1, 'Следующий день');
    }
    // In the Mac sidebar the panel is narrow; the same screens can open as a full tab.
    if (!this.app.isMobile && this.leaf.getRoot() !== this.app.workspace.rootSplit) {
      const b = head.createEl('button', { cls: 'fd-icon-btn fd-head-tab', attr: { 'aria-label': 'Открыть во вкладке' } });
      setIcon(b, 'maximize-2');
      b.onclick = async () => {
        const leaf = this.app.workspace.getLeaf('tab');
        await leaf.setViewState({ type: VIEW_TYPE, active: true, state: { screen: this.screen } });
        if (leaf.view instanceof FocusDeckView) { leaf.view.date = this.date; leaf.view.render(); }
      };
    }
    const nav = root.createDiv('fd-nav');
    const item = (icon, label, active, onClick) => {
      const el = nav.createDiv({ cls: 'fd-nav-item' + (active ? ' is-active' : '') });
      setIcon(el.createDiv('fd-nav-icon'), icon);
      el.createDiv({ cls: 'fd-nav-label', text: label });
      el.onclick = onClick;
    };
    item('sun', 'Сегодня', screen === 'today', () => this.go('today'));
    item('calendar-days', 'План', screen === 'plan', () => this.go('plan', screen === 'today' ? new Date() : this.date));
    item('target', 'Цели', false, async () => {
      await this.app.workspace.openLinkText(LIFE_PAGE, '', this.leaf.getRoot() === this.app.workspace.rootSplit ? 'tab' : false);
      if (this.app.isMobile) this.app.workspace.leftSplit?.collapse?.();
    });
    item('book-open', 'Дневник', screen === 'journal', () => this.go('journal', screen === 'today' ? new Date() : this.date));
    if (week) this.renderWeek(root, screen, date, week);
  }

  emptyDay(root, date, what) {
    const empty = root.createDiv('fd-card fd-empty');
    empty.createDiv({ cls: 'fd-title', text: relDay(date) === 'сегодня' ? 'Заметки на сегодня ещё нет' : 'Заметки на этот день нет' });
    if (what) empty.createDiv({ cls: 'fd-muted', text: what });
    const btn = empty.createEl('button', { cls: 'fd-lh-btn is-primary' });
    setIcon(btn.createSpan('fd-jr-icon'), 'plus');
    btn.createSpan({ text: 'Создать заметку дня' });
    btn.onclick = async () => { await ensureDailyFile(this.app, date); this.render(); };
  }

  // ----- Сегодня -----

  async readDay(date) {
    const file = dailyFileOf(this.app, date);
    return { date, file, lines: file ? (await this.app.vault.cachedRead(file)).split('\n') : [] };
  }

  async readTodayExtras(date) {
    const life = await this.readLife();
    const y = await this.readDay(shiftDay(date, -1));
    const win = ritualWindow(new Date());
    const h = new Date().getHours();
    let ritual = null;
    if (win.phase === 'evening' || h < 13) {
      const journal = await this.readDay(win.journal);
      const plan = ymd(win.plan) === ymd(win.journal) ? journal : await this.readDay(win.plan);
      const marker = win.phase === 'evening' ? 'Вечер завершён' : 'Утро подтверждено';
      if (ritualValue(journal.lines, marker) !== 'да') {
        ritual = { ...win, journalDay: journal, planDay: plan,
          yesterday: win.phase === 'morning' ? y : null };
      }
    }
    return { life, yFile: y.file, yTasks: parseTasks(y.lines).tasks.filter((t) => !t.done), ritual };
  }

  letGoKey(date, t) { return `${ymd(date)}|${t.id || t.title}`; }
  isLetGo(date, t) { return !!this.plugin.settings?.letGo?.[this.letGoKey(date, t)]; }
  async letGo(date, t) {
    // Like the app's "Отпустить": remembered on this device, the note keeps its honest unfinished entry.
    this.plugin.settings.letGo[this.letGoKey(date, t)] = ymd(new Date());
    await this.plugin.saveSettings();
    this.render();
  }

  renderToday(root, ctx, extra) {
    const { file, tasks, lines, date } = ctx;
    if (!file) {
      this.emptyDay(root, date, 'Задачи, дневник и фокус дня живут в ней — как в приложении.');
      this.renderLife(root, extra.life, []);
      return;
    }
    const minute = nowMinute();
    // The ritual comes first while it's open, as the app's "Закрыть день" card does from 22:30.
    if (extra.ritual) this.renderRitual(root, extra.ritual);
    this.renderNow(root, ctx, minute);
    this.renderMain(root, ctx);
    // After midnight the evening card already lists yesterday's tails.
    if (!(extra.ritual && extra.ritual.phase === 'evening' && ymd(extra.ritual.journal) !== ymd(date))) this.renderTails(root, ctx, extra);
    this.renderTasks(root, ctx, minute);
    this.renderJournalCard(root, file, journalState(lines));
    this.renderLife(root, extra.life, tasks);
  }

  /**
   * The ritual's own checks, prepared here so the phone's ritual only has to confirm: the journal field's
   * volume, the plan's coverage and main thing, the day's tails. The completion mark stays with the app,
   * which also checks food — writing it from here would skip those checks.
   */
  renderRitual(root, r) {
    const evening = r.phase === 'evening';
    const jLines = r.journalDay.lines;
    const field = evening ? 'Вечер' : 'Утро';
    const jr = journalReadiness((readJournal(jLines) || {})[field] || '');
    const plan = planReadiness(r.planDay.lines);
    const planLabel = relDay(r.plan) === 'завтра' ? 'План на завтра' : relDay(r.plan) === 'сегодня' ? 'План на сегодня' : 'План';
    const ok = [jr.ready, plan.ready].filter(Boolean).length;

    const card = root.createDiv('fd-card fd-ritual is-' + r.phase);
    const top = card.createDiv('fd-row');
    const t = top.createDiv('fd-label');
    setIcon(t.createSpan('fd-jr-icon'), evening ? 'moon' : 'sunrise');
    t.createSpan({ text: evening ? (relDay(r.journal) === 'вчера' ? 'Закрыть вчерашний день' : 'Закрыть день') : 'Начать день' });
    top.createDiv({ cls: 'fd-count', text: `${ok} из 2` });

    if (evening) {
      // eveningReview: commitments are the scheduled tasks; the pool isn't a promise.
      const tasks = parseTasks(jLines).tasks;
      const sched = tasks.filter((x) => x.start != null);
      const done = sched.filter((x) => x.done).length;
      const extra = tasks.filter((x) => x.done && x.start == null).length;
      if (sched.length || extra) {
        const rv = card.createDiv('fd-rt-review');
        rv.createSpan({ cls: 'fd-rt-big', text: `${done}/${sched.length}` });
        rv.createSpan({ text: `сделано из запланированного${extra ? ` · ещё ${extra} вне плана` : ''}` });
      }
    } else if (r.yesterday) {
      const lesson = (readJournal(r.yesterday.lines) || {})['Выводы'];
      if (lesson) {
        const q = card.createDiv('fd-rt-quote');
        q.createDiv({ cls: 'fd-label', text: 'Вчерашние выводы' });
        q.createDiv({ text: lesson });
      }
    }

    const step = (done, icon, title, sub, fraction, onClick) => {
      const row = card.createDiv('fd-rt-step' + (done ? ' is-done' : ''));
      const ic = row.createDiv('fd-rt-ic');
      setIcon(ic, done ? 'check' : icon);
      const body = row.createDiv('fd-rt-body');
      body.createDiv({ cls: 'fd-rt-title', text: title });
      body.createDiv({ cls: 'fd-rt-sub', text: sub });
      if (!done && fraction != null) {
        const bar = body.createDiv('fd-bar');
        bar.createDiv('fd-bar-fill').style.width = `${Math.round(fraction * 100)}%`;
      }
      setIcon(row.createDiv('fd-rt-go'), 'chevron-right');
      row.onclick = onClick;
    };
    step(jr.ready, evening ? 'moon' : 'sunrise', `${field} в дневнике`,
      jr.ready ? `${jr.words} слов — хватает` : `${jr.words}/${JOURNAL_MIN_WORDS} слов · ${jr.chars}/${JOURNAL_MIN_CHARS} знаков · ${jr.distinct}/${JOURNAL_MIN_DISTINCT} разных`,
      jr.fraction, () => { this.focusField = field; this.go('journal', r.journal); });
    const planBits = [`${minutesText(plan.minutes)} из ${minutesText(PLAN_REQUIRED)}`, plan.mainOk ? 'главное есть' : plan.priority ? 'главное без времени' : 'главное не выбрано'];
    if (plan.invalid.length) planBits.push(`${plan.invalid.length} с ошибкой`);
    step(plan.ready, 'calendar-days', planLabel, planBits.join(' · '), Math.min(1, plan.minutes / PLAN_REQUIRED), () => this.go('plan', r.plan));

    if (evening) {
      // Tails: unfinished commitments, each either moves on or is let go — never silently dropped.
      const planTasks = parseTasks(r.planDay.lines).tasks;
      const tails = parseTasks(jLines).tasks.filter((x) => x.start != null && !x.done && !this.isLetGo(r.journal, x)
        && !planTasks.some((p) => p.title === x.title && p.project === x.project && p.goal === x.goal));
      if (tails.length) {
        card.createDiv({ cls: 'fd-sub', text: `Хвосты · ${tails.length}` });
        for (const x of tails) {
          const row = card.createDiv('fd-tail');
          row.createDiv({ cls: 'fd-task-title', text: x.title });
          const b = row.createEl('button', { cls: 'fd-mini', text: relDay(r.plan) === 'завтра' ? 'На завтра' : 'На сегодня' });
          b.onclick = async (e) => {
            e.stopPropagation();
            b.disabled = true;
            if (await copyTaskToDay(this.app, r.journalDay.file, x, r.plan)) this.render();
          };
          const g = row.createEl('button', { cls: 'fd-mini is-quiet', text: 'Отпустить' });
          g.onclick = (e) => { e.stopPropagation(); this.letGo(r.journal, x); };
        }
      }
      const food = ritualValue(jLines, 'Еда за день');
      card.createDiv({ cls: 'fd-rt-foot', text: food ? 'Еда записана. Подтверждение и завершение — в приложении.' : 'Еду и завершение подтверждаешь в приложении.' });
    } else {
      card.createDiv({ cls: 'fd-rt-foot', text: 'Подтверждение — в приложении.' });
    }
  }

  renderNow(root, ctx, minute) {
    const timed = ctx.tasks.filter((t) => t.start != null && !t.done).sort((a, b) => a.start - b.start);
    const now = timed.find((t) => t.start <= minute && minute < t.start + (t.minutes ?? DEFAULT_TASK_MINUTES));
    const next = timed.find((t) => t.start > minute);
    const t = now || next;
    if (!t) return;
    const card = root.createDiv('fd-card fd-now' + (now ? ' is-live' : ''));
    card.style.setProperty('--fd-hue', hueAt(t.start));
    const end = t.start + (t.minutes ?? DEFAULT_TASK_MINUTES);
    const top = card.createDiv('fd-row');
    top.createDiv({ cls: 'fd-label', text: now ? `Сейчас · до ${hhmm(end)}` : `Дальше · ${hhmm(t.start)}` });
    top.createDiv({ cls: 'fd-count', text: now ? `осталось ${minutesText(end - minute)}` : `через ${minutesText(t.start - minute)}` });
    card.createDiv({ cls: 'fd-now-title', text: t.title });
    if (now) {
      const bar = card.createDiv('fd-bar');
      bar.createDiv('fd-bar-fill').style.width = `${Math.round(((minute - t.start) / (end - t.start)) * 100)}%`;
      const done = card.createEl('button', { cls: 'fd-lh-btn is-primary fd-now-done' });
      setIcon(done.createSpan('fd-jr-icon'), 'check');
      done.createSpan({ text: 'Готово' });
      done.onclick = async (e) => { e.stopPropagation(); await this.toggle(ctx.file, t); };
      const more = card.createEl('button', { cls: 'fd-lh-btn fd-now-more' });
      more.createSpan({ text: '+15 мин' });
      more.onclick = async (e) => {
        e.stopPropagation();
        if (await editTask(this.app, ctx.file, t, (l, f) => lineOps.duration(l, (f.minutes ?? DEFAULT_TASK_MINUTES) + 15))) this.render();
      };
      const btns = card.createDiv('fd-now-btns');
      btns.append(done, more);
    }
    card.onclick = () => this.openTask(ctx, t);
  }

  renderMain(root, ctx) {
    const priority = ritualValue(ctx.lines, 'Главное');
    const ifThen = ritualValue(ctx.lines, 'Если помешает');
    const main = priority ? ctx.tasks.find((t) => t.title === priority) : null;
    if (!priority) {
      if (!ctx.tasks.some((t) => !t.done)) return;
      const row = root.createDiv('fd-card fd-pick-main');
      setIcon(row.createSpan('fd-jr-icon'), 'star');
      row.createSpan({ text: 'Выбрать главное дня' });
      row.onclick = () => new MainDialog(this.app, ctx, () => this.render()).open();
      return;
    }
    const card = root.createDiv('fd-card fd-main');
    card.createDiv({ cls: 'fd-label', text: main?.start != null ? `Главное · ${hhmm(main.start)}` : 'Главное' });
    card.createDiv({ cls: 'fd-hero', text: priority });
    if (ifThen) card.createDiv({ cls: 'fd-muted', text: `Если помешает: ${ifThen}` });
    if (main) card.toggleClass('is-done', main.done);
    card.onclick = () => new MainDialog(this.app, ctx, () => this.render()).open();
  }

  // Unfinished yesterday, one tap each to bring into today — the evening ritual's "Хвосты", for a missed evening.
  renderTails(root, ctx, extra) {
    const yDate = shiftDay(ctx.date, -1);
    const left = extra.yTasks.filter((y) => !this.isLetGo(yDate, y)
      && !ctx.tasks.some((t) => t.title === y.title && t.project === y.project && t.goal === y.goal));
    if (!left.length) return;
    const box = root.createEl('details', { cls: 'fd-tails' });
    const sum = box.createEl('summary');
    setIcon(sum.createSpan('fd-jr-icon'), 'history');
    sum.createSpan({ text: 'Со вчера не закрыто' });
    sum.createSpan({ cls: 'fd-count', text: String(left.length) });
    for (const y of left) {
      const row = box.createDiv('fd-tail');
      row.createDiv({ cls: 'fd-task-title', text: y.title });
      const b = row.createEl('button', { cls: 'fd-mini', text: 'На сегодня' });
      b.onclick = async () => {
        b.disabled = true;
        if (await copyTaskToDay(this.app, extra.yFile, y, ctx.date)) this.render();
      };
      const g = row.createEl('button', { cls: 'fd-mini is-quiet', text: 'Отпустить' });
      g.onclick = () => this.letGo(yDate, y);
    }
  }

  renderTasks(root, ctx, minute) {
    const { tasks } = ctx;
    const done = tasks.filter((t) => t.done).length;
    const section = root.createDiv('fd-section');
    const title = section.createDiv('fd-section-head');
    title.createSpan({ text: 'Дела' });
    const right = title.createDiv('fd-section-tools');
    right.createSpan({ cls: 'fd-count', text: tasks.length ? `${done} из ${tasks.length}` : '' });
    const plan = right.createEl('button', { cls: 'fd-link', text: 'План' });
    plan.onclick = () => this.go('plan', new Date());
    if (tasks.length) {
      const bar = section.createDiv('fd-bar');
      bar.createDiv('fd-bar-fill').style.width = `${Math.round((done / tasks.length) * 100)}%`;
    }
    const timed = tasks.filter((t) => t.start != null).sort((a, b) => a.start - b.start);
    const pool = tasks.filter((t) => t.start == null);
    const list = section.createDiv('fd-list');
    timed.forEach((t) => this.taskRow(list, ctx, t, minute));
    if (pool.length) {
      list.createDiv({ cls: 'fd-sub', text: 'Пул' });
      pool.forEach((t) => this.taskRow(list, ctx, t, minute));
    }
    if (!tasks.length) section.createDiv({ cls: 'fd-muted', text: 'План пуст — добавь дело ниже.' });
    this.addBox(section, ctx.date);
  }

  addBox(parent, date, placeholder = 'Дело · 10:30 · 45м') {
    const wrap = parent.createDiv('fd-addwrap');
    const add = wrap.createEl('input', { cls: 'fd-add', attr: { type: 'text', placeholder, enterkeyhint: 'done' } });
    const hint = wrap.createDiv('fd-add-hint');
    const draftKey = ymd(date);
    add.value = this.drafts[draftKey] || '';
    const preview = () => {
      this.drafts[draftKey] = add.value;
      const q = parseQuickAdd(add.value);
      hint.empty();
      if (q.start != null) hint.createSpan({ cls: 'fd-pill', text: hhmm(q.start) });
      if (q.minutes) hint.createSpan({ cls: 'fd-pill', text: shortMinutes(q.minutes) });
      hint.toggle(q.start != null || !!q.minutes);
    };
    preview();
    add.addEventListener('input', preview);
    add.addEventListener('keydown', async (e) => {
      if (e.key !== 'Enter' || e.isComposing) return;
      e.preventDefault();
      const q = parseQuickAdd(add.value);
      if (!meaningfulTitle(q.title)) { new Notice('Назови дело конкретнее'); return; }
      add.value = '';
      delete this.drafts[draftKey];
      preview();
      await addTask(this.app, date, q);
      this.render();
    });
    this.addInput = add;
    return add;
  }

  taskRow(list, ctx, task, minute) {
    const row = list.createDiv({ cls: 'fd-task' + (task.done ? ' is-done' : '') });
    const today = relDay(ctx.date) === 'сегодня';
    const now = today && task.start != null && !task.done && task.start <= minute && minute < task.start + (task.minutes ?? DEFAULT_TASK_MINUTES);
    if (now) row.addClass('is-now');
    const box = row.createDiv('fd-check');
    if (task.done) setIcon(box, 'check');
    box.onclick = async (e) => { e.stopPropagation(); await this.toggle(ctx.file, task); };
    row.createDiv({ cls: 'fd-time', text: task.start != null ? hhmm(task.start) : '' });
    const body = row.createDiv('fd-task-body');
    body.createDiv({ cls: 'fd-task-title', text: task.title });
    const meta = this.taskMeta(task);
    if (meta) body.createDiv({ cls: 'fd-task-meta', text: meta });
    row.onclick = () => this.openTask(ctx, task);
  }

  taskMeta(task) {
    const bits = [];
    if (task.minutes) bits.push(shortMinutes(task.minutes));
    const p = task.noteLink && /\|([^\]]+)]]$/.exec(task.noteLink);
    if (p) bits.push(p[1]);
    return bits.join(' · ');
  }

  openTask(ctx, task) {
    new TaskSheet(this.app, this, ctx, task).open();
  }

  renderJournalCard(root, file, filled) {
    const card = root.createDiv('fd-card fd-journal');
    card.onclick = () => this.go('journal', new Date());
    const top = card.createDiv('fd-row');
    top.createDiv({ cls: 'fd-label', text: 'Дневник' });
    const count = filled ? JOURNAL_FIELDS.filter((f) => filled[f]).length : 0;
    top.createDiv({ cls: 'fd-count', text: `${count} из 4` });
    const chips = card.createDiv('fd-chips');
    JOURNAL_FIELDS.forEach((f) => {
      const chip = chips.createDiv({ cls: 'fd-chip' + (filled && filled[f] ? ' is-on' : '') });
      setIcon(chip.createSpan('fd-chip-icon'), JOURNAL_LOOK[f].icon);
      chip.createSpan({ text: f });
      chip.onclick = (e) => { e.stopPropagation(); this.focusField = f; this.go('journal', new Date()); };
    });
  }

  async readLife() {
    const out = { projects: [], due: 0 };
    const today = ymd(new Date());
    const parents = new Map(activeProjects(this.app).map((p) => [p.file.path, p.parents]));
    for (const f of this.app.vault.getMarkdownFiles()) {
      if (!f.path.startsWith('FocusDeck/')) continue;
      const fm = this.app.metadataCache.getFileCache(f)?.frontmatter;
      if (!fm || String(fm.focusdeck) !== '1') continue;
      const active = String(fm.status).toLowerCase() === 'active';
      if (active && fm.review && String(fm.review) <= today && fm.kind !== 'review') out.due++;
      if (!active || String(fm.kind).toLowerCase() !== 'project') continue;
      const text = await this.app.vault.cachedRead(f);
      const stages = lifeSection(text, 'Этапы').split('\n').map((l) => TASK.exec(l)).filter(Boolean);
      out.projects.push({
        file: f, title: fm.title || f.basename, parents: parents.get(f.path),
        next: lifeSection(text, 'Следующее действие').split('\n')[0].replace(/^[-*]\s*(\[.\]\s*)?/, '').trim(),
        done: stages.filter((m) => m[1] !== ' ').length, total: stages.length,
      });
    }
    out.projects.sort((a, b) => b.file.stat.mtime - a.file.stat.mtime);
    return out;
  }

  renderLife(root, life, tasks) {
    const section = root.createDiv('fd-section');
    const head = section.createDiv('fd-section-head');
    head.createSpan({ text: 'Проекты' });
    if (life.due) head.createSpan({ cls: 'fd-count fd-attn', text: `пора свериться: ${life.due}` });
    if (!life.projects.length) {
      section.createDiv({ cls: 'fd-muted', text: 'Проектов в работе нет.' });
      const add = section.createEl('button', { cls: 'fd-lh-btn is-primary' });
      setIcon(add.createSpan('fd-jr-icon'), 'plus');
      add.createSpan({ text: 'Новый проект' });
      add.onclick = () => openLifeWizard(this.app, 'project');
      return;
    }
    life.projects.slice(0, 6).forEach((p) => {
      const card = section.createDiv('fd-card fd-project');
      card.onclick = () => this.openFile(p.file);
      const top = card.createDiv('fd-row');
      top.createDiv({ cls: 'fd-title', text: p.title });
      if (p.total) top.createDiv({ cls: 'fd-count', text: `${Math.round((p.done / p.total) * 100)}%` });
      if (p.total) {
        const bar = card.createDiv('fd-bar');
        bar.createDiv('fd-bar-fill').style.width = `${Math.round((p.done / p.total) * 100)}%`;
      }
      if (!p.next) return;
      // The next step goes into today's pool with the project's links, like the chips in the evening ritual.
      const row = card.createDiv('fd-next-row');
      row.createDiv({ cls: 'fd-next', text: `▸ ${p.next}` });
      const planned = tasks.some((t) => t.title === p.next);
      const b = row.createEl('button', { cls: 'fd-mini' + (planned ? ' is-on' : ''), text: planned ? 'В плане' : 'В план' });
      b.disabled = planned || !meaningfulTitle(p.next);
      b.onclick = async (e) => {
        e.stopPropagation();
        b.disabled = true;
        await addTask(this.app, new Date(), { title: p.next, ...(p.parents || {}) });
        this.render();
      };
    });
  }

  // ----- План: the day's tape with free windows, the pool and the plan's coverage -----

  renderPlan(root, ctx) {
    const { file, tasks, date, lines } = ctx;
    // A day without a note is planned the same way: the first added task creates the note from the app's template.
    const isToday = relDay(date) === 'сегодня';
    const minute = nowMinute();
    const readiness = planReadiness(lines);
    const cov = readiness;
    const priority = readiness.priority;
    const mainTimed = readiness.mainOk;

    const sum = root.createDiv('fd-card fd-plan-sum');
    const r1 = sum.createDiv('fd-row');
    r1.createDiv({ cls: 'fd-label', text: 'План 08–23' });
    r1.createDiv({ cls: 'fd-count', text: `${minutesText(cov.minutes)} · ${Math.round((cov.minutes * 100) / (PLAN_END - PLAN_START))}%` });
    const bar = sum.createDiv('fd-bar fd-cov');
    bar.createDiv('fd-bar-fill').style.width = `${Math.min(100, (cov.minutes * 100) / (PLAN_END - PLAN_START))}%`;
    bar.createDiv('fd-cov-mark').style.left = `${(PLAN_REQUIRED * 100) / (PLAN_END - PLAN_START)}%`;
    const r2 = sum.createDiv('fd-plan-flags');
    const flag = (ok, text) => { const f = r2.createDiv('fd-flag' + (ok ? ' is-ok' : '')); setIcon(f.createSpan(), ok ? 'check' : 'circle'); f.createSpan({ text }); };
    flag(cov.minutes >= PLAN_REQUIRED, cov.minutes >= PLAN_REQUIRED ? 'покрытие есть' : `ещё ${minutesText(PLAN_REQUIRED - cov.minutes)}`);
    flag(!!mainTimed, mainTimed ? 'главное выбрано' : 'главное не выбрано');
    if (cov.overlap) r2.createDiv({ cls: 'fd-flag is-warn', text: 'есть пересечения' });
    if (readiness.invalid.length) {
      // The app's ritual refuses a plan with these: say which ones, not just that something is off.
      r2.createDiv({ cls: 'fd-flag is-warn', text: `не засчитано: ${readiness.invalid.map((t) => `«${t.title}»`).join(', ')}` });
    }
    const pickMain = r2.createEl('button', { cls: 'fd-link', text: priority ? 'Главное…' : 'Выбрать главное' });
    pickMain.onclick = () => new MainDialog(this.app, ctx, () => this.render()).open();

    const timed = tasks.filter((t) => t.start != null).sort((a, b) => a.start - b.start || a.line - b.line);
    const pool = tasks.filter((t) => t.start == null);
    const tape = root.createDiv('fd-tape');
    let cursor = PLAN_START;
    let nowShown = !isToday;
    const nowLine = () => {
      if (nowShown) return;
      nowShown = true;
      const n = tape.createDiv('fd-nowline');
      n.createSpan({ text: hhmm(minute) });
    };
    const gap = (from, to) => {
      if (to - from < 15) return;
      if (isToday && to <= minute) return;
      const g = tape.createDiv('fd-gap');
      g.createSpan({ cls: 'fd-gap-time', text: hhmm(from) });
      g.createSpan({ text: `свободно ${minutesText(to - from)}` });
      setIcon(g.createSpan('fd-gap-plus'), 'plus');
      g.onclick = () => {
        if (!this.addInput) return;
        const rest = parseQuickAdd(this.addInput.value).title;
        this.addInput.value = `${hhmm(Math.max(from, isToday ? Math.ceil(minute / 5) * 5 : from))} ${rest}`.trimEnd() + ' ';
        this.addInput.dispatchEvent(new Event('input'));
        this.addInput.focus();
      };
    };
    for (const t of timed) {
      const end = t.start + (t.minutes ?? DEFAULT_TASK_MINUTES);
      if (!nowShown && t.start > minute) { gap(cursor, Math.min(minute, t.start)); nowLine(); cursor = Math.max(cursor, minute); }
      gap(cursor, Math.min(t.start, PLAN_END));
      const overlaps = t.start < cursor && cursor > PLAN_START;
      const row = tape.createDiv({ cls: 'fd-slot' + (t.done ? ' is-done' : '') + (isToday && t.start <= minute && minute < end && !t.done ? ' is-now' : '') });
      row.style.setProperty('--fd-hue', hueAt(t.start));
      const check = row.createDiv('fd-check');
      if (t.done) setIcon(check, 'check');
      check.onclick = async (e) => { e.stopPropagation(); await this.toggle(file, t); };
      const body = row.createDiv('fd-slot-body');
      body.createDiv({ cls: 'fd-task-title', text: t.title });
      const meta = body.createDiv('fd-task-meta');
      meta.createSpan({ text: `${hhmm(t.start)}–${hhmm(Math.min(end, 24 * 60 - 1))}` });
      meta.createSpan({ text: shortMinutes(end - t.start) });
      const p = t.noteLink && /\|([^\]]+)]]$/.exec(t.noteLink);
      if (p) meta.createSpan({ text: p[1] });
      if (t.title === priority) meta.createSpan({ cls: 'fd-star', text: '★ главное' });
      if (overlaps) meta.createSpan({ cls: 'fd-warn', text: 'пересекается' });
      row.onclick = () => this.openTask(ctx, t);
      cursor = Math.max(cursor, end);
    }
    if (timed.length) {
      if (!nowShown && minute < PLAN_END) { gap(cursor, minute); nowLine(); cursor = Math.max(cursor, minute); }
      gap(cursor, PLAN_END);
    }
    if (!timed.length) tape.createDiv({ cls: 'fd-muted fd-tape-empty', text: 'Во времени пока ничего. Задай дело со временем — «10:30 созвон 45м» — или поставь время делу из пула.' });

    const addSec = root.createDiv('fd-section');
    this.addBox(addSec, date);

    const ps = root.createDiv('fd-section');
    const ph = ps.createDiv('fd-section-head');
    ph.createSpan({ text: 'Пул' });
    ph.createSpan({ cls: 'fd-count', text: pool.length ? String(pool.length) : '' });
    if (!pool.length) ps.createDiv({ cls: 'fd-muted', text: 'Пул пуст.' });
    const list = ps.createDiv('fd-list');
    for (const t of pool) {
      const row = list.createDiv({ cls: 'fd-task fd-pool' + (t.done ? ' is-done' : '') });
      const box = row.createDiv('fd-check');
      if (t.done) setIcon(box, 'check');
      box.onclick = async (e) => { e.stopPropagation(); await this.toggle(file, t); };
      const body = row.createDiv('fd-task-body');
      body.createDiv({ cls: 'fd-task-title', text: t.title });
      const meta = this.taskMeta(t);
      if (meta) body.createDiv({ cls: 'fd-task-meta', text: meta });
      if (!t.done) {
        // One tap to the nearest free window of its length: the pool's most common move.
        const slot = freeSlot(tasks, t.minutes ?? DEFAULT_TASK_MINUTES, isToday ? Math.ceil(minute / 5) * 5 : PLAN_START, t);
        if (slot != null) {
          const b = row.createEl('button', { cls: 'fd-mini', text: `в ${hhmm(slot)}`, attr: { 'aria-label': 'Поставить в ближайшее свободное окно' } });
          b.onclick = async (e) => {
            e.stopPropagation();
            if (await editTask(this.app, file, t, (l) => lineOps.schedule(l, slot, t.minutes))) this.render();
          };
        }
      }
      row.onclick = () => this.openTask(ctx, t);
    }
    this.renderProjectSteps(root, ctx);
    if (!file) return;
    const open = root.createEl('button', { cls: 'fd-link fd-open-note', text: 'Открыть заметку дня' });
    open.onclick = () => this.openFile(file);
  }

  /** The evening "Завтра" step of the app: each active project's next action, one tap into this day's pool. */
  renderProjectSteps(root, ctx) {
    const steps = activeProjects(this.app);
    if (!steps.length || !this.nextSteps) return;
    const sec = root.createDiv('fd-section');
    const head = sec.createDiv('fd-section-head');
    head.createSpan({ text: 'Шаги проектов' });
    const row = sec.createDiv('fd-steps');
    let shown = 0;
    for (const p of steps) {
      const next = this.nextSteps.get(p.file.path);
      if (!next || !meaningfulTitle(next)) continue;
      const planned = ctx.tasks.some((t) => t.title === next);
      const chip = row.createEl('button', { cls: 'fd-step' + (planned ? ' is-on' : '') });
      setIcon(chip.createSpan('fd-jr-icon'), planned ? 'check' : 'plus');
      const txt = chip.createDiv('fd-step-text');
      txt.createDiv({ text: next });
      txt.createDiv({ cls: 'fd-step-proj', text: p.title });
      chip.disabled = planned;
      chip.onclick = async () => {
        chip.disabled = true;
        await addTask(this.app, ctx.date, { title: next, ...p.parents });
        this.render();
      };
      shown++;
    }
    if (!shown) sec.remove();
  }

  // ----- Дневник: the four fields of the day, written in place like the app's editor -----

  renderJournalScreen(root, ctx) {
    const { file, lines, date, tasks } = ctx;
    if (!file) { this.emptyDay(root, date); return; }
    const journal = readJournal(lines) || Object.fromEntries(JOURNAL_FIELDS.map((f) => [f, '']));

    const facts = root.createDiv('fd-facts');
    const fact = (icon, text) => { const f = facts.createDiv('fd-fact'); setIcon(f.createSpan(), icon); f.createSpan({ text }); };
    if (tasks.length) fact('check-square', `дела ${tasks.filter((t) => t.done).length}/${tasks.length}`);
    const icons = { 'Экранное время': 'smartphone', 'Фокус': 'timer', 'Калории': 'utensils', 'Задачи': null, 'Сорвано блоков': 'zap-off', 'Шаги': 'footprints' };
    for (const f of summaryFacts(lines)) {
      if (f.label === 'Задачи' || /нет данных|^0 мин$/.test(f.value)) continue;
      fact(icons[f.label] || 'dot', `${f.label.toLowerCase()} ${f.value}`);
    }
    if (!facts.childElementCount) facts.remove();

    const priority = ritualValue(lines, 'Главное');
    const ifThen = ritualValue(lines, 'Если помешает');
    const food = ritualValue(lines, 'Еда за день');
    if (priority || food) {
      const r = root.createDiv('fd-card fd-dayfocus');
      if (priority) { const x = r.createDiv('fd-df'); setIcon(x.createSpan('fd-jr-icon'), 'star'); x.createSpan({ text: priority }); }
      if (ifThen) { const x = r.createDiv('fd-df is-sub'); setIcon(x.createSpan('fd-jr-icon'), 'shield'); x.createSpan({ text: ifThen }); }
      if (food) { const x = r.createDiv('fd-df is-sub'); setIcon(x.createSpan('fd-jr-icon'), 'utensils'); x.createSpan({ text: food.split(/;\s*/).join(' · ') }); }
    }

    const words = JOURNAL_FIELDS.reduce((n, f) => n + (journal[f].match(/[\p{L}\p{N}]+/gu) || []).length, 0);
    const head = root.createDiv('fd-section-head');
    head.createSpan({ text: 'Дневник' });
    const counter = head.createSpan({ cls: 'fd-count', text: words ? `${words} слов` : '' });
    const fields = root.createDiv('fd-jfields');
    let focusEl = null;
    for (const f of JOURNAL_FIELDS) {
      const card = fields.createDiv('fd-jfield');
      const label = card.createDiv('fd-jr-name');
      setIcon(label.createSpan('fd-jr-icon'), JOURNAL_LOOK[f].icon);
      label.createSpan({ text: f });
      const state = card.createSpan('fd-jstate');
      const ta = card.createEl('textarea', { cls: 'fd-jtext', attr: { placeholder: JOURNAL_LOOK[f].hint, rows: '2' } });
      const key = `${ymd(date)}|${f}`;
      const draft = this.drafts[key];
      let base = journal[f];
      ta.value = draft != null ? draft.text : base;
      if (draft != null && draft.base !== base && draft.text !== base) this.conflict(card, ta, f, base, draft);
      const fit = () => { ta.style.height = 'auto'; ta.style.height = `${Math.max(ta.scrollHeight, 48)}px`; };
      setTimeout(fit, 0);
      let timer = null;
      const save = async () => {
        clearTimeout(timer);
        const d = this.drafts[key];
        if (!d || d.text === d.base) { delete this.drafts[key]; return; }
        state.setText('сохраняю…');
        try {
          const saved = await this.saveJournal(file, f, d);
          if (saved === 'conflict') { state.setText(''); this.render(); return; }
          if (this.drafts[key] === d) delete this.drafts[key];
          else if (this.drafts[key]) this.drafts[key].base = d.text;
          base = d.text;
          state.setText('сохранено');
          setTimeout(() => state.setText(''), 1500);
        } catch (err) {
          state.setText('');
          new Notice(err.message);
        }
      };
      let meter = null;
      if (f === 'Утро' || f === 'Вечер') {
        // The ritual reads exactly this field: show how far it is from the app's threshold while writing.
        meter = card.createDiv('fd-jmeter');
        const draw = () => {
          const r = journalReadiness(ta.value);
          meter.empty();
          meter.toggleClass('is-ready', r.ready);
          const bar = meter.createDiv('fd-bar');
          bar.createDiv('fd-bar-fill').style.width = `${Math.round(r.fraction * 100)}%`;
          meter.createDiv({ cls: 'fd-jmeter-text', text: r.ready ? 'Хватает для ритуала'
            : `${r.words}/${JOURNAL_MIN_WORDS} слов · ${r.chars}/${JOURNAL_MIN_CHARS} знаков · ${r.distinct}/${JOURNAL_MIN_DISTINCT} разных` });
        };
        meter.draw = draw;
        draw();
      }
      ta.addEventListener('input', () => {
        fit();
        if (meter) meter.draw();
        this.drafts[key] = { text: ta.value, base: this.drafts[key]?.base ?? base };
        const total = [...fields.querySelectorAll('textarea')].reduce((n, x) => n + (x.value.match(/[\p{L}\p{N}]+/gu) || []).length, 0);
        counter.setText(total ? `${total} слов` : '');
        clearTimeout(timer);
        timer = setTimeout(save, 900);
      });
      ta.addEventListener('blur', save);
      if (this.focusField === f) focusEl = ta;
    }
    if (focusEl) {
      this.focusField = null;
      setTimeout(() => { focusEl.focus(); focusEl.setSelectionRange(focusEl.value.length, focusEl.value.length); focusEl.scrollIntoView({ block: 'center' }); }, 30);
    }
    const open = root.createEl('button', { cls: 'fd-link fd-open-note', text: 'Открыть заметку дня' });
    open.onclick = () => this.openFile(file);
  }

  /** Our draft and the file both moved since the field was opened: never overwrite silently. */
  conflict(card, ta, field, fileText, draft) {
    const box = card.createDiv('fd-jconflict');
    box.createDiv({ text: 'Поле изменилось в заметке, пока ты писал. Что оставить?' });
    const row = box.createDiv('fd-w-chips');
    const mine = row.createEl('button', { cls: 'fd-mini', text: 'Мой текст' });
    const theirs = row.createEl('button', { cls: 'fd-mini', text: 'Из заметки' });
    const key = `${ymd(this.date)}|${field}`;
    mine.onclick = async () => {
      this.drafts[key] = { text: draft.text, base: fileText };
      ta.value = draft.text;
      box.remove();
      try { await this.saveJournal(this.dailyFile(), field, this.drafts[key]); delete this.drafts[key]; } catch (e) { new Notice(e.message); }
    };
    theirs.onclick = () => { delete this.drafts[key]; ta.value = fileText; box.remove(); };
  }
  dailyFile() { return dailyFileOf(this.app, this.date); }

  async saveJournal(file, field, draft) {
    let result = 'saved';
    await this.app.vault.process(file, (data) => {
      const lines = data.split('\n');
      const current = (readJournal(lines) || {})[field] || '';
      if (current === draft.text) { result = 'same'; return data; }
      if (current !== draft.base) { result = 'conflict'; return data; }
      writeJournalField(lines, field, draft.text);
      return lines.join('\n');
    });
    return result;
  }

  async toggle(file, task) {
    const ok = await editTask(this.app, file, task, (l, fresh) => lineOps.toggle(l, !fresh.done));
    if (ok) this.render();
  }
}

/** "Задача удалена · Вернуть": the line goes back after the same neighbour it had, if that neighbour is still there. */
function undoNotice(app, file, removed) {
  const box = document.createDocumentFragment();
  const text = document.createElement('span');
  text.textContent = 'Задача удалена ';
  const b = document.createElement('button');
  b.className = 'fd-mini';
  b.textContent = 'Вернуть';
  box.append(text, b);
  const notice = new Notice(box, 8000);
  const prev = removed.prev;
  b.onclick = async () => {
    await app.vault.process(file, (data) => {
      const lines = data.split('\n');
      const id = idOf(removed.text);
      if (id && lines.some((l) => l.includes(id))) return data;
      // Only a task line is a trustworthy neighbour; otherwise the place addTask would pick in "## Задачи".
      const range = sectionLines(lines, '## Задачи');
      let at = -1;
      if (range && prev != null && TASK.test(prev)) {
        const i = lines.indexOf(prev, range.start);
        if (i > range.start && i < range.end) at = i + 1;
      }
      if (range && at < 0 && removed.next != null && TASK.test(removed.next)) {
        const i = lines.indexOf(removed.next, range.start);
        if (i > range.start && i < range.end) at = i;
      }
      if (at < 0) {
        if (!range) { new Notice('Раздела «Задачи» больше нет — вернуть некуда'); return data; }
        at = !lines[range.start + 1]?.trim() && range.start + 1 < range.end ? range.start + 2 : range.start + 1;
        for (let i = range.start + 1; i < range.end; i++) if (TASK.test(lines[i])) at = i + 1;
      }
      lines.splice(at, 0, removed.text);
      return lines.join('\n');
    });
    notice.hide && notice.hide();
  };
}

/** Main thing of the day, and what to do if it's blocked: the "## FocusDeck" lines the rituals write. */
class MainDialog extends require('obsidian').Modal {
  constructor(app, ctx, done) { super(app); this.ctx = ctx; this.done = done; }
  onOpen() {
    this.modalEl.addClass('fd-wizard', 'fd-sheet');
    const { lines, tasks, date } = this.ctx;
    let main = ritualValue(lines, 'Главное');
    let ifThen = ritualValue(lines, 'Если помешает') || '';
    const c = this.contentEl;
    c.createDiv({ cls: 'fd-w-q', text: 'Главное дня' });
    const body = c.createDiv('fd-w-body');
    const open = tasks.filter((t) => !t.done || t.title === main);
    if (!open.length) body.createDiv({ cls: 'fd-w-hint', text: 'Сначала добавь дела в план.' });
    const timed = open.filter((t) => t.start != null).sort((a, b) => a.start - b.start);
    const pool = open.filter((t) => t.start == null);
    const list = body.createDiv('fd-pick');
    const opt = (t) => {
      const r = list.createDiv({ cls: 'fd-pick-row' + (t.title === main ? ' is-on' : '') });
      r.createSpan({ cls: 'fd-time', text: t.start != null ? hhmm(t.start) : 'пул' });
      r.createSpan({ text: t.title });
      r.onclick = () => { main = t.title; list.querySelectorAll('.fd-pick-row').forEach((x) => x.removeClass('is-on')); r.addClass('is-on'); };
    };
    timed.forEach(opt); pool.forEach(opt);
    if (pool.length && !timed.length) body.createDiv({ cls: 'fd-w-hint', text: 'Для ритуала главное должно стоять во времени — поставь ему время в плане.' });
    body.createDiv({ cls: 'fd-w-label', text: 'Если помешает' });
    growArea(body, ifThen, 'Если отвлекусь — вернусь через 10 минут', (v) => { ifThen = v; }, 1);
    const foot = c.createDiv('fd-w-foot');
    const b = foot.createEl('button', { cls: 'fd-w-primary' });
    b.createSpan({ text: 'Сохранить' });
    setIcon(b.createSpan('fd-jr-icon'), 'check');
    b.onclick = async () => {
      const pairs = [];
      if (main) pairs.push(['Главное', main], ['День отдыха', 'нет']);
      if (ifThen.trim() || ritualValue(lines, 'Если помешает')) pairs.push(['Если помешает', ifThen]);
      if (pairs.length) await setRitual(this.app, date, pairs);
      this.close();
      this.done();
    };
  }
}

/** A task's card: what the app's timeline and pool sheet let you change, without touching markdown. */
class TaskSheet extends require('obsidian').Modal {
  constructor(app, view, ctx, task) { super(app); this.view = view; this.ctx = ctx; this.task = { ...task }; }
  onOpen() { this.modalEl.addClass('fd-wizard', 'fd-sheet'); this.draw(); }
  onClose() { this.view.render(); }

  async reload() {
    const lines = (await this.app.vault.read(this.ctx.file)).split('\n');
    const fresh = findTask(lines, this.task);
    if (!fresh) { new Notice('Задачи больше нет в заметке'); this.close(); return; }
    this.ctx = { ...this.ctx, lines, ...parseTasks(lines) };
    this.task = { ...fresh, id: fresh.id || this.task.id };
    this.draw();
  }
  async edit(fn) { if (await editTask(this.app, this.ctx.file, this.task, fn)) await this.reload(); }

  draw() {
    const t = this.task;
    const c = this.contentEl;
    c.empty();
    const top = c.createDiv('fd-sheet-top');
    const check = top.createDiv('fd-check' + (t.done ? ' is-on' : ''));
    if (t.done) setIcon(check, 'check');
    check.onclick = () => this.edit((l, f) => lineOps.toggle(l, !f.done));
    const title = top.createEl('textarea', { cls: 'fd-w-input fd-sheet-title', attr: { rows: '1' } });
    title.value = t.title;
    const fit = () => { title.style.height = 'auto'; title.style.height = `${title.scrollHeight}px`; };
    setTimeout(fit, 0);
    const rename = async () => {
      const v = title.value.replace(/\s+/g, ' ').trim();
      if (v === t.title) return;
      if (!meaningfulTitle(v)) { new Notice('Назови дело конкретнее'); title.value = t.title; return; }
      const wasMain = ritualValue(this.ctx.lines, 'Главное') === t.title;
      await this.edit((l) => lineOps.rename(l, v));
      if (wasMain) await setRitual(this.app, this.ctx.date, [['Главное', v]]);
    };
    title.addEventListener('input', fit);
    title.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); title.blur(); } });
    title.addEventListener('blur', rename);

    const body = c.createDiv('fd-w-body');
    const minutes = t.minutes ?? DEFAULT_TASK_MINUTES;
    const isToday = relDay(this.ctx.date) === 'сегодня';

    body.createDiv({ cls: 'fd-w-label', text: 'Время' });
    const times = body.createDiv('fd-w-chips');
    const chip = (parent, label, on, fn, icon) => {
      const b = parent.createEl('button', { cls: 'fd-w-chip' + (on ? ' is-on' : '') });
      if (icon) setIcon(b.createSpan('fd-jr-icon'), icon);
      b.createSpan({ text: label });
      b.onclick = fn;
      return b;
    };
    chip(times, 'В пуле', t.start == null, () => t.start != null && this.edit((l) => lineOps.unschedule(l)), 'inbox');
    const from = isToday ? Math.ceil(nowMinute() / 5) * 5 : PLAN_START;
    const slot = freeSlot(this.ctx.tasks, minutes, from, t);
    if (slot != null && slot !== t.start) chip(times, `Окно ${hhmm(slot)}`, false, () => this.edit((l) => lineOps.schedule(l, slot, t.minutes)), 'sparkles');
    const pick = times.createEl('input', { cls: 'fd-w-date', attr: { type: 'time', step: '300' } });
    if (t.start != null) pick.value = hhmm(t.start);
    pick.onchange = () => {
      const m = /^(\d{2}):(\d{2})/.exec(pick.value);
      if (m) this.edit((l) => lineOps.schedule(l, Number(m[1]) * 60 + Number(m[2]), t.minutes));
    };
    if (t.start != null) {
      const nudge = body.createDiv('fd-w-chips');
      for (const d of [-30, -15, 15, 30]) {
        const s = t.start + d;
        if (s < 0 || s > 24 * 60 - 5) continue;
        chip(nudge, `${d > 0 ? '+' : '−'}${Math.abs(d)} мин`, false, () => this.edit((l) => lineOps.schedule(l, s, t.minutes)));
      }
    }

    body.createDiv({ cls: 'fd-w-label', text: !t.minutes ? `Длительность · не задана, считается ${DEFAULT_TASK_MINUTES} мин` : DURATIONS.includes(t.minutes) ? 'Длительность' : `Длительность · ${minutesText(t.minutes)}` });
    const durs = body.createDiv('fd-w-chips');
    for (const d of DURATIONS) chip(durs, shortMinutes(d), t.minutes === d, () => t.minutes !== d && this.edit((l) => lineOps.duration(l, d)));

    const projects = activeProjects(this.app);
    if (projects.length || t.project) {
      body.createDiv({ cls: 'fd-w-label', text: 'Проект' });
      const pr = body.createDiv('fd-w-chips');
      chip(pr, 'Без проекта', !t.project, () => t.project && this.edit((l) => lineOps.link(l, {}, t.id)));
      for (const p of projects) {
        chip(pr, p.title, t.project === p.id, () => t.project !== p.id && this.edit((l) => lineOps.link(l, p.parents, t.id)), 'folder-kanban');
      }
    }

    const acts = c.createDiv('fd-sheet-acts');
    const act = (icon, label, fn, cls = '') => {
      const b = acts.createEl('button', { cls: 'fd-lh-btn ' + cls });
      setIcon(b.createSpan('fd-jr-icon'), icon);
      b.createSpan({ text: label });
      b.onclick = fn;
      return b;
    };
    const isMain = ritualValue(this.ctx.lines, 'Главное') === t.title;
    act('star', isMain ? 'Главное дня' : 'Сделать главным', async () => {
      if (isMain) return;
      await setRitual(this.app, this.ctx.date, [['Главное', t.title], ['День отдыха', 'нет']]);
      await this.reload();
    }, isMain ? 'is-on' : '');
    if (!t.done) {
      const next = shiftDay(this.ctx.date, 1);
      act('arrow-right', relDay(next) === 'завтра' ? 'Копия на завтра' : 'Копия на след. день', async () => {
        const r = await copyTaskToDay(this.app, this.ctx.file, t, next);
        if (r) new Notice(r === 'added' ? 'Добавлено в пул следующего дня' : 'На следующий день уже есть');
      });
    }
    if (t.noteLink) {
      const target = /^\[\[([^|\]]+)/.exec(t.noteLink)?.[1];
      if (target) act('folder-kanban', 'Проект', async () => {
        this.close();
        const f = this.app.metadataCache.getFirstLinkpathDest(target, this.ctx.file.path);
        if (f) this.view.openFile(f);
      });
    }
    act('file-text', 'В заметке', () => { this.close(); this.view.openFile(this.ctx.file, { line: t.line }); });
    const del = act('trash-2', 'Удалить', async () => {
      if (!del.hasClass('is-armed')) { del.addClass('is-armed'); del.lastChild.setText('Точно удалить?'); return; }
      let removed = null;
      const file = this.ctx.file;
      if (!(await editTask(this.app, file, this.task, (l, f, lines) => {
        removed = { text: l, prev: f.line > 0 ? lines[f.line - 1] : null, next: lines[f.line + 1] ?? null };
        return null;
      }))) return;
      this.close();
      undoNotice(this.app, file, removed);
    }, 'is-danger');
  }
}

// ---------- Life notes: create and change them from Obsidian, byte-compatible with LifeNoteCodec.kt ----------

const LIFE_FOLDER = { area: 'Области', goal: 'Цели', project: 'Проекты', decision: 'Решения', review: 'Обзоры' };
const LIFE_KEYS = ['focusdeck', 'id', 'kind', 'title', 'status', 'area_id', 'goal_id', 'project_id', 'due', 'review',
  'created', 'updated', 'decided_on', 'reviewed_on', 'period_start', 'period_end'];
// fieldsFor(kind) in LifeEntry.kt: the sections a new note gets, in this order.
const LIFE_FIELDS = {
  area: ['Что значит достаточно', 'Почему это важно', 'Признаки прогресса', 'Что стало ясно'],
  goal: ['Почему это важно', 'Как пойму, что готово', 'Показатель', 'Этапы', 'Цена выбора', 'Что может помешать', 'Если — то', 'Признаки прогресса', 'Что стало ясно'],
  project: ['Как пойму, что готово', 'Следующее действие', 'Этапы', 'Что может помешать', 'Если — то', 'Признаки прогресса', 'Что стало ясно'],
  decision: ['Варианты', 'Критерии выбора', 'Мой выбор и основания', 'Ожидаемый результат', 'Цена выбора', 'Что произошло', 'Что я понял'],
  review: ['Что получилось', 'Что мешало', 'Что изменю', 'Главное на неделю'],
};
const DECISION_BASIS = ['Варианты', 'Критерии выбора', 'Мой выбор и основания', 'Ожидаемый результат', 'Цена выбора'];
const DECISION_RESULT = ['Что произошло', 'Что я понял'];
const HISTORY_HEADING = 'История решений';
const MEANINGFUL = /[\p{L}\p{N}]/u;
const meaningful = (s) => MEANINGFUL.test(s || '');
const KIND_NEW = { goal: 'Новая цель', project: 'Новый проект', area: 'Новая сфера', decision: 'Новое решение', review: 'Обзор недели' };

// LifeNoteCodec.quote
const yamlQuote = (v) => '"' + String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t') + '"';
// LifeEntry.fileName
function lifeFileName(kind, title, id) {
  const singular = KIND_SINGULAR[kind] || 'Запись';
  const clean = title.replace(/[\\/:*?"<>|#^\r\n]/g, ' ').replace(/\[/g, '').replace(/]/g, '').trim().slice(0, 60);
  return `${clean || singular} -- ${id}.md`;
}
const nowStamp = () => { const d = new Date(); return `${ymd(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; };
const addDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return ymd(d); };

/** Sections as LifeNoteCodec.sections sees them: "# " and "## " heads outside code fences. */
function lifeSections(lines, from) {
  const heads = [];
  let fence = null, fenceLen = 0;
  for (let i = from; i < lines.length; i++) {
    const t = lines[i].trimStart();
    const m = /^(`{3,}|~{3,})/.exec(t);
    if (m) {
      if (!fence) { fence = m[1][0]; fenceLen = m[1].length; } else if (m[1][0] === fence && m[1].length >= fenceLen) fence = null;
      continue;
    }
    if (!fence && (t.startsWith('## ') || t.startsWith('# '))) heads.push(i);
  }
  return heads.map((h, k) => [h, heads[k + 1] ?? lines.length]);
}
const fmEnd = (lines) => lines[0] === '---' ? lines.indexOf('---', 1) : -1;

class LifeNote {
  constructor(text) { this.lines = text.replace(/\r\n/g, '\n').split('\n'); }
  get text() { return this.lines.join('\n'); }
  prop(key) {
    const end = fmEnd(this.lines);
    for (let i = 1; i < end; i++) {
      const m = /^([a-z][a-z0-9_-]*):(?:[ \t]*(.*))?$/.exec(this.lines[i]);
      if (m && m[1] === key) {
        const v = (m[2] || '').trim();
        if (v.startsWith('"')) { try { return JSON.parse(v.replace(/\s+#.*$/, '')); } catch (e) { return v.slice(1, -1); } }
        if (v.startsWith("'")) return v.slice(1, -1).replace(/''/g, "'");
        return v === 'null' || v === '~' ? '' : v.replace(/ #.*$/, '');
      }
    }
    return '';
  }
  setProp(key, value) {
    const end = fmEnd(this.lines);
    const line = `${key}: ${yamlQuote(value)}`;
    for (let i = 1; i < end; i++) {
      if (/^([a-z][a-z0-9_-]*):/.exec(this.lines[i])?.[1] === key) { this.lines[i] = line; return; }
    }
    this.lines.splice(end, 0, line);
  }
  section(heading) {
    const s = lifeSections(this.lines, fmEnd(this.lines) + 1).find(([h]) => this.lines[h].trim() === `## ${heading}`);
    return s ? this.lines.slice(s[0] + 1, s[1]).join('\n').trim() : '';
  }
  /** LifeNoteCodec.merge: replace the body of our section, or append it at the end. */
  setSection(heading, content) {
    const body = content.trim().split('\n');
    const s = lifeSections(this.lines, fmEnd(this.lines) + 1).find(([h]) => this.lines[h].trim() === `## ${heading}`);
    if (!s) {
      if (this.lines[this.lines.length - 1]?.trim()) this.lines.push('');
      this.lines.push(`## ${heading}`, '', ...body, '');
      return;
    }
    this.lines.splice(s[0] + 1, s[1] - s[0] - 1, '', ...body, '');
  }
}

/** LifeRules.appendEvent: one dated block of the decision's history with a stable block id. */
function decisionEvent(history, path, label, snapshot, fields, note = '') {
  const prev = [...history.matchAll(/\^life-event-([a-zA-Z0-9-]+)/g)].pop()?.[1];
  let ev = `### ${nowStamp()} · ${label}\n\n`;
  if (note) ev += `${note}\n\n`;
  if (prev) ev += `Предыдущая запись: [[${path.replace(/\.md$/, '')}#^life-event-${prev}]]\n\n`;
  for (const f of fields) {
    const t = snapshot.fields[f] || '';
    if (!t.trim()) continue;
    ev += `**${f}**\n` + t.split('\n').map((l) => `> ${l}`).join('\n') + '\n\n';
  }
  ev += `Срок результата: ${snapshot.due || 'не указан'}. Проверка: ${snapshot.review || 'не назначена'}.\n\n`;
  ev += `^life-event-${crypto.randomUUID()}`;
  return (history.trimEnd() + '\n\n' + ev).trim();
}

/** LifeEntry.activationProblems */
function activationProblems(kind, title, fields, review) {
  const out = [];
  if (!meaningful(title)) out.push('добавь название');
  if (kind === 'goal') {
    if (!meaningful(fields['Почему это важно'])) out.push('запиши, почему цель важна');
    if (!meaningful(fields['Как пойму, что готово'])) out.push('определи критерий результата');
  } else if (kind === 'project') {
    if (!meaningful(fields['Как пойму, что готово'])) out.push('определи, когда проект завершён');
    if (!meaningful(fields['Следующее действие'])) out.push('выбери следующее действие');
  } else if (kind === 'decision') {
    if (!meaningful(fields['Варианты'])) out.push('рассмотри варианты');
    if (!meaningful(fields['Мой выбор и основания'])) out.push('запиши выбор и основания');
    if (!meaningful(fields['Ожидаемый результат'])) out.push('зафиксируй ожидания');
    if (!review) out.push('выбери дату проверки');
  }
  return out;
}

function lifeIndex(app) {
  const out = [];
  for (const file of app.vault.getMarkdownFiles()) {
    if (!file.path.startsWith('FocusDeck/')) continue;
    const fm = app.metadataCache.getFileCache(file)?.frontmatter;
    if (!fm || String(fm.focusdeck) !== '1') continue;
    out.push({ file, id: String(fm.id || ''), kind: String(fm.kind || '').toLowerCase(), title: String(fm.title || file.basename),
      status: String(fm.status || 'draft').toLowerCase(), areaId: String(fm.area_id || ''), goalId: String(fm.goal_id || '') });
  }
  return out;
}

/** LifeNoteCodec.create, plus LifeRules.prepare for a decision accepted right away. */
async function createLifeNote(app, e) {
  const id = crypto.randomUUID();
  const today = ymd(new Date());
  const path = `FocusDeck/${LIFE_FOLDER[e.kind]}/${lifeFileName(e.kind, e.title, id)}`;
  const fields = { ...e.fields };
  const meta = {
    focusdeck: '1', id, kind: e.kind, title: e.title.replace(/[\r\n]/g, ' ').trim(), status: e.status,
    area_id: e.areaId || '', goal_id: e.goalId || '', project_id: e.projectId || '',
    due: e.due || '', review: e.review || '', created: today, updated: today,
    decided_on: '', reviewed_on: '', period_start: e.periodStart || '', period_end: e.periodEnd || '',
  };
  if (e.kind === 'decision' && e.status === 'active') {
    fields[HISTORY_HEADING] = decisionEvent('', path, 'Решение принято', { fields, due: meta.due, review: meta.review }, DECISION_BASIS);
    meta.decided_on = today;
  }
  let text = '---\n' + LIFE_KEYS.map((k) => `${k}: ${yamlQuote(meta[k])}\n`).join('') + `---\n\n# ${meta.title}\n`;
  const heads = [...LIFE_FIELDS[e.kind], ...(fields[HISTORY_HEADING] ? [HISTORY_HEADING] : [])];
  for (const h of heads) text += `\n## ${h}\n\n${(fields[h] || '').trim()}\n`;
  const folder = `FocusDeck/${LIFE_FOLDER[e.kind]}`;
  if (!app.vault.getAbstractFileByPath(folder)) await app.vault.createFolder(folder);
  return app.vault.create(path, text);
}

/** Read-modify-write of one life note; `updated` always moves to today, like the app's save. */
async function editLifeNote(app, file, fn) {
  let result;
  await app.vault.process(file, (data) => {
    const note = new LifeNote(data);
    result = fn(note);
    if (result === false) return data;
    note.setProp('updated', ymd(new Date()));
    return note.text;
  });
  return result;
}

function snapshotOf(note, heads) {
  const fields = {};
  for (const h of heads) fields[h] = note.section(h);
  return { fields, due: note.prop('due'), review: note.prop('review') };
}

const lifeActions = {
  async accept(app, file) {
    return editLifeNote(app, file, (n) => {
      const fields = snapshotOf(n, [...DECISION_BASIS, ...DECISION_RESULT]).fields;
      const miss = activationProblems('decision', n.prop('title'), fields, n.prop('review'));
      if (miss.length) { new Notice('Чтобы принять решение: ' + miss.join(', ')); return false; }
      const late = DECISION_RESULT.some((h) => fields[h].trim());
      const snap = snapshotOf(n, DECISION_BASIS);
      n.setSection(HISTORY_HEADING, decisionEvent(n.section(HISTORY_HEADING), file.path,
        late ? 'Записано после результата' : 'Решение принято', snap, DECISION_BASIS,
        late ? 'Ожидание внесено вместе с результатом; это не прогноз, зафиксированный заранее.' : ''));
      n.setProp('status', 'active');
      n.setProp('decided_on', ymd(new Date()));
      return true;
    });
  },
  async recordOutcome(app, file, { outcome, verdict, lesson, close }) {
    return editLifeNote(app, file, (n) => {
      let history = n.section(HISTORY_HEADING);
      const before = snapshotOf(n, [...DECISION_BASIS, ...DECISION_RESULT]);
      if (!/\^life-event-/.test(history)) {
        history = decisionEvent(history, file.path, 'Состояние до правки', before, [...DECISION_BASIS, ...DECISION_RESULT],
          'Исходная история отсутствует. Это текущая запись из Obsidian, а не подтверждение того, что было известно при принятии решения.');
      }
      const out = [outcome.trim(), verdict ? `Ожидание сбылось: ${verdict}` : ''].filter(Boolean).join('\n');
      n.setSection('Что произошло', out);
      n.setSection('Что я понял', lesson.trim());
      const snap = snapshotOf(n, [...DECISION_BASIS, ...DECISION_RESULT]);
      n.setSection(HISTORY_HEADING, decisionEvent(history, file.path, 'Проверка результата', snap, [...DECISION_BASIS, ...DECISION_RESULT]));
      n.setProp('reviewed_on', ymd(new Date()));
      if (close) n.setProp('status', 'done');
      return true;
    });
  },
  async moveReview(app, file, date) {
    return editLifeNote(app, file, (n) => {
      const old = n.prop('review');
      if (old === date) return false;
      const known = n.prop('decided_on') || ['active', 'done'].includes(n.prop('status'));
      n.setProp('review', date);
      if (n.prop('kind') === 'decision' && known) {
        n.setSection(HISTORY_HEADING, decisionEvent(n.section(HISTORY_HEADING), file.path, 'Дата проверки изменена',
          snapshotOf(n, []), [], `Предыдущая дата: ${old || 'не назначена'}. Перенос даты не означает состоявшийся обзор.`));
      }
      return true;
    });
  },
  async setStatus(app, file, status, extra = {}) {
    return editLifeNote(app, file, (n) => {
      const kind = n.prop('kind');
      if (extra.evidence) n.setSection('Признаки прогресса', [n.section('Признаки прогресса'), extra.evidence.trim()].filter(Boolean).join('\n'));
      if (status === 'active') {
        const fields = snapshotOf(n, LIFE_FIELDS[kind] || []).fields;
        const miss = kind === 'area' ? [] : activationProblems(kind, n.prop('title'), fields, n.prop('review'));
        if (miss.length) { new Notice('Чтобы запустить: ' + miss.join(', ')); return false; }
      }
      if (status === 'done') {
        const need = { goal: ['Признаки прогресса', 'Запиши факт, который подтверждает достижение цели'], review: ['Что изменю', 'Выбери, что изменишь на следующей неделе'] }[kind];
        if (need && !meaningful(n.section(need[0]))) { new Notice(need[1]); return false; }
        n.setProp('reviewed_on', ymd(new Date()));
      }
      n.setProp('status', status);
      return true;
    });
  },
  async addMeasure(app, file, value) {
    return editLifeNote(app, file, (n) => {
      const date = ymd(new Date());
      const kept = n.section('Показатель').split('\n').filter((l) => !new RegExp(`^\\s*[-*]\\s*${date}\\s*:`).test(l));
      n.setSection('Показатель', (kept.join('\n').trimEnd() + `\n- ${date}: ${value}`).trim());
      return true;
    });
  },
};

// ----- UI helpers shared by the wizard and the action dialogs -----
function chipRow(parent, options, selected, onPick, cls = '') {
  const row = parent.createDiv('fd-w-chips ' + cls);
  const chips = options.map(([value, label, icon]) => {
    const c = row.createEl('button', { cls: 'fd-w-chip' + (value === selected ? ' is-on' : '') });
    if (icon) setIcon(c.createSpan('fd-jr-icon'), icon);
    c.createSpan({ text: label });
    c.onclick = (e) => {
      e.preventDefault();
      const was = c.hasClass('is-on');
      chips.forEach((x) => x.removeClass('is-on'));
      if (!was) c.addClass('is-on');
      onPick(was ? null : value);
    };
    return c;
  });
  return row;
}
function growArea(parent, value, placeholder, onInput, rows = 2) {
  const ta = parent.createEl('textarea', { cls: 'fd-w-input', attr: { placeholder, rows: String(rows) } });
  ta.value = value || '';
  const fit = () => { ta.style.height = 'auto'; ta.style.height = Math.max(ta.scrollHeight, 44) + 'px'; };
  ta.addEventListener('input', () => { fit(); onInput(ta.value); });
  setTimeout(fit, 0);
  return ta;
}

class LifeWizard extends require('obsidian').Modal {
  constructor(app, kind, done) {
    super(app);
    this.kind = kind;
    this.done = done;
    this.step = 0;
    this.e = { kind, title: '', fields: {}, status: 'draft' };
    if (kind === 'decision') this.e.review = addDays(7);
    if (kind === 'review') {
      const d = new Date(); const dow = (d.getDay() + 6) % 7;
      const mon = new Date(d); mon.setDate(d.getDate() - dow);
      const sun = new Date(mon); sun.setDate(mon.getDate() + 6);
      Object.assign(this.e, { title: `Неделя ${ymd(mon)}`, periodStart: ymd(mon), periodEnd: ymd(sun) });
    }
    this.motive = null; this.confidence = null; this.metric = { target: '', unit: '', start: '' }; this.milestones = '';
    this.steps = this.stepsFor(kind);
  }

  // Same questions as LifeWizard.kt in the app.
  stepsFor(kind) {
    const S = (q, field, ph, o = {}) => ({ q, field, ph, ...o });
    return {
      project: [
        S('Какой проект?', null, 'Запустить портфолио', { single: true }),
        S('Как поймёшь, что готово?', 'Как пойму, что готово', 'Сайт открыт, в нём пять работ'),
        S('С какого шага начнёшь?', 'Следующее действие', 'Набросать структуру за 25 минут', { single: true }),
        S('Какие этапы по пути?', 'Этапы', 'Структура\nТексты\nДизайн\nЗапуск', { optional: true, hint: 'Каждая строка — этап. Из них сложится процент', extra: 'milestones' }),
        S('Представь, что проект встал. Что помешало?', 'Что может помешать', 'Нет времени на неделе', { optional: true, extra: 'woop' }),
        S('К чему он относится?', null, '', { optional: true, extra: 'links' }),
      ],
      goal: [
        S('Какая цель?', null, 'Пробежать полумарафон', { single: true }),
        S('Почему это важно именно тебе?', 'Почему это важно', 'Хочу снова чувствовать себя сильным', { extra: 'motive' }),
        S('Как поймёшь, что достиг?', 'Как пойму, что готово', 'Финиш 21 км до 1 мая'),
        S('Какое число покажет движение?', 'Показатель', '', { optional: true, extra: 'metric' }),
        S('Представь: срок прошёл, цель не достигнута. Что помешало?', 'Что может помешать', 'Устану после работы и отложу', { optional: true }),
        S('Если это случится — что сделаешь?', 'Если — то', 'Если пропущу пробежку, выйду на 15 минут вечером', { optional: true }),
        S('Сфера и срок', null, '', { optional: true, extra: 'links' }),
      ],
      decision: [
        S('Что решаешь?', null, 'Менять ли работу этой осенью', { single: true }),
        S('Какие есть варианты?', 'Варианты', 'Остаться · перейти · подождать до весны', { hint: 'Включая «ничего не менять»' }),
        S('Что для тебя важнее всего?', 'Критерии выбора', 'Доход, спокойствие, время на учёбу', { optional: true, hint: 'И чем готов пожертвовать' }),
        S('Что выбираешь и почему?', 'Мой выбор и основания', 'Перехожу: рост важнее стабильности сейчас'),
        S('Чего ожидаешь и к какому сроку?', 'Ожидаемый результат', 'Через 3 месяца доход выше, стресс не больше', { extra: 'confidence' }),
        S('Когда проверить ожидание?', null, '', { extra: 'links' }),
      ],
      area: [
        S('Какая сфера жизни?', null, 'Здоровье и энергия', { single: true }),
        S('Как выглядит «достаточно»?', 'Что значит достаточно', 'Сплю 7 часов, двигаюсь 3 раза в неделю', { optional: true }),
      ],
      review: [
        S('Что получилось за неделю?', 'Что получилось', 'Закрыл резюме, три тренировки', { hint: 'Опирайся на факты' }),
        S('Что мешало?', 'Что мешало', 'Вечерами залипал в телефон', { optional: true }),
        S('Что изменишь на следующей неделе?', 'Что изменю', 'Телефон — в другой комнате после 22:00'),
        S('Что главное на неделю?', 'Главное на неделю', 'Отправить 10 откликов', { optional: true }),
      ],
    }[kind];
  }

  onOpen() {
    this.modalEl.addClass('fd-wizard');
    this.render();
  }

  value(s) { return s.field ? (this.e.fields[s.field] || '') : this.e.title; }

  collect() {
    const f = this.e.fields;
    if (this.kind === 'goal') {
      const why = (f['Почему это важно'] || '').replace(/^Мотив:.*$/m, '').trim();
      f['Почему это важно'] = [this.motive ? `Мотив: ${this.motive}` : '', why].filter(Boolean).join('\n');
      const t = this.metric.target.replace(/\s/g, '').replace(',', '.');
      if (t && !isNaN(Number(t))) {
        const st = this.metric.start.replace(/\s/g, '').replace(',', '.');
        f['Показатель'] = `Цель: ${Number(t)}${this.metric.unit.trim() ? ' ' + this.metric.unit.trim() : ''}` + (st && !isNaN(Number(st)) ? `\nСтарт: ${Number(st)}` : '');
      } else delete f['Показатель'];
    }
    if (this.kind === 'decision') {
      const p = (f['Ожидаемый результат'] || '').replace(/^Уверенность:.*$/m, '').trim();
      f['Ожидаемый результат'] = [p, this.confidence ? `Уверенность: ${this.confidence}%` : ''].filter(Boolean).join('\n');
    }
    if (this.milestones.trim()) {
      f['Этапы'] = this.milestones.split('\n').map((l) => l.trim().replace(/^[-*]\s*/, '').replace(/^\[[ xX]]\s*/, '').trim())
        .filter((l) => meaningful(l)).map((l) => `- [ ] ${l}`).join('\n');
    }
  }

  canActivate() {
    this.collect();
    return this.kind === 'area' || activationProblems(this.kind, this.e.title, this.e.fields, this.e.review).length === 0;
  }

  render() {
    const { contentEl } = this;
    contentEl.empty();
    const s = this.steps[this.step];
    const last = this.step === this.steps.length - 1;

    const top = contentEl.createDiv('fd-w-top');
    const badge = top.createDiv(`fd-w-badge is-${this.kind}`);
    setIcon(badge.createSpan('fd-jr-icon'), KINDS[this.kind].icon);
    badge.createSpan({ text: KIND_NEW[this.kind] });
    const dots = top.createDiv('fd-w-dots');
    this.steps.forEach((_, i) => dots.createDiv({ cls: 'fd-w-dot' + (i === this.step ? ' is-now' : i < this.step ? ' is-done' : '') }));
    if (s.optional && !last) {
      const skip = top.createEl('button', { cls: 'fd-w-skip', text: 'Пропустить' });
      skip.onclick = () => { this.step++; this.render(); };
    } else top.createDiv('fd-w-skip-space');

    const body = contentEl.createDiv('fd-w-body');
    body.createDiv({ cls: 'fd-w-q', text: s.q });
    if (s.hint) body.createDiv({ cls: 'fd-w-hint', text: s.hint });
    let focusEl = null;

    if (s.extra === 'metric') {
      body.createDiv({ cls: 'fd-w-hint', text: 'Например 21 км, 80 кг, 10 000 подписчиков. Прогресс будет видно по замерам' });
      const grid = body.createDiv('fd-w-metric');
      const box = (label, key, numeric) => {
        const w = grid.createDiv('fd-w-num');
        w.createDiv({ cls: 'fd-w-label', text: label });
        const inp = w.createEl('input', { cls: 'fd-w-line', attr: { type: 'text', inputmode: numeric ? 'decimal' : 'text' } });
        inp.value = this.metric[key];
        inp.oninput = () => { this.metric[key] = inp.value; };
        return inp;
      };
      focusEl = box('Цель', 'target', true); box('Единица', 'unit', false); box('Сейчас — необязательно', 'start', true);
    } else if (s.extra === 'links') {
      this.renderLinks(body);
    } else if (s.extra === 'milestones') {
      focusEl = growArea(body, this.milestones, s.ph, (v) => { this.milestones = v; }, 4);
    } else {
      focusEl = growArea(body, this.value(s), s.ph, (v) => {
        if (s.field) this.e.fields[s.field] = v; else this.e.title = v.replace(/\n/g, ' ');
        primary.toggleClass('is-off', !this.answered(s));
      }, s.single ? 1 : 3);
      if (s.single) focusEl.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') { ev.preventDefault(); this.next(); } });
    }

    if (s.extra === 'motive') {
      body.createDiv({ cls: 'fd-w-label', text: 'Скорее потому что' });
      const motives = [['интересно', 'интересно'], ['важно мне', 'важно мне'], ['чувствую, что должен', 'чувствую, что должен'], ['ждут другие', 'ждут другие']];
      const note = createDiv({ cls: 'fd-w-hint', text: 'Цели на «надо» обычно держатся хуже. Что здесь важно лично тебе — или её стоит отпустить?' });
      chipRow(body, motives, this.motive, (v) => { this.motive = v; note.toggle(v === 'чувствую, что должен' || v === 'ждут другие'); });
      body.appendChild(note); note.toggle(this.motive === 'чувствую, что должен' || this.motive === 'ждут другие');
    }
    if (s.extra === 'confidence') {
      body.createDiv({ cls: 'fd-w-label', text: 'Насколько уверен, что так и будет' });
      chipRow(body, [50, 60, 70, 80, 90].map((v) => [v, `${v}%`]), this.confidence, (v) => { this.confidence = v; });
    }
    if (s.extra === 'woop') {
      body.createDiv({ cls: 'fd-w-q is-sub', text: 'Если это случится — что сделаешь?' });
      growArea(body, this.e.fields['Если — то'], 'Если неделя забита — сделаю 20 минут в субботу утром', (v) => { this.e.fields['Если — то'] = v; });
    }
    if (last && this.kind !== 'area' && this.kind !== 'review' && !this.canActivate()) {
      const miss = activationProblems(this.kind, this.e.title, this.e.fields, this.e.review);
      body.createDiv({ cls: 'fd-w-hint is-warn', text: 'Сохранится как идея. Чтобы запустить: ' + miss.join(', ') });
    }

    const foot = contentEl.createDiv('fd-w-foot');
    if (this.step > 0) {
      const back = foot.createEl('button', { cls: 'fd-w-back', attr: { 'aria-label': 'Назад' } });
      setIcon(back, 'arrow-left');
      back.onclick = () => { this.step--; this.render(); };
    }
    const primary = foot.createEl('button', { cls: `fd-w-primary is-${this.kind}` });
    primary.createSpan({ text: last ? this.finishLabel() : 'Дальше' });
    setIcon(primary.createSpan('fd-jr-icon'), last ? 'check' : 'arrow-right');
    primary.toggleClass('is-off', !(s.optional || this.answered(s)));
    primary.onclick = () => this.next();
    if (focusEl) setTimeout(() => { focusEl.focus(); }, 30);
  }

  finishLabel() {
    if (this.kind === 'area') return 'Добавить сферу';
    if (this.kind === 'review') return 'Сохранить обзор';
    if (!this.canActivate()) return 'Сохранить как идею';
    return { project: 'Запустить проект', goal: 'Поставить цель', decision: 'Принять решение' }[this.kind];
  }

  answered(s) {
    if (s.extra === 'links' || s.extra === 'metric') return true;
    if (s.extra === 'milestones') return meaningful(this.milestones);
    return meaningful(this.value(s));
  }

  renderLinks(body) {
    const all = lifeIndex(this.app).filter((x) => x.status !== 'archived');
    const group = (label) => { body.createDiv({ cls: 'fd-w-label', text: label }); };
    const dateChips = (key, options, fallbackDays) => {
      const today = new Date();
      const opts = options.map(([d, l]) => [addDays(d), l]);
      const row = chipRow(body, opts, this.e[key], (v) => { this.e[key] = v || (key === 'review' ? this.e.review : ''); });
      const pick = row.createEl('input', { cls: 'fd-w-date', attr: { type: 'date' } });
      pick.value = this.e[key] && !opts.some(([v]) => v === this.e[key]) ? this.e[key] : '';
      pick.onchange = () => { if (pick.value) { this.e[key] = pick.value; row.querySelectorAll('.fd-w-chip').forEach((c) => c.removeClass('is-on')); } };
      void today; void fallbackDays;
    };
    if (this.kind === 'decision') {
      dateChips('review', [[7, 'Через неделю'], [14, 'Через 2 недели'], [30, 'Через месяц'], [90, 'Через 3 месяца']]);
      const projects = all.filter((x) => x.kind === 'project' && ['active', 'draft'].includes(x.status));
      if (projects.length) {
        group('Проект');
        chipRow(body, projects.map((p) => [p.id, p.title, 'folder-kanban']), this.e.projectId, (v) => {
          const p = projects.find((x) => x.id === v);
          Object.assign(this.e, { projectId: v || '', goalId: p ? p.goalId : '', areaId: p ? p.areaId : '' });
        });
      }
      return;
    }
    if (this.kind === 'project') {
      const goals = all.filter((x) => x.kind === 'goal' && x.status !== 'done');
      if (goals.length) {
        group('Цель');
        chipRow(body, goals.map((g) => [g.id, g.title, 'target']), this.e.goalId, (v) => {
          const g = goals.find((x) => x.id === v);
          Object.assign(this.e, { goalId: v || '', areaId: g ? g.areaId : this.e.areaId });
        });
      }
    }
    const areas = all.filter((x) => x.kind === 'area');
    if (areas.length) {
      group('Сфера');
      chipRow(body, areas.map((a) => [a.id, a.title, 'compass']), this.e.areaId, (v) => { this.e.areaId = v || ''; });
    }
    if (this.kind === 'goal') {
      group('Срок');
      dateChips('due', [[30, 'Месяц'], [91, '3 месяца'], [182, 'Полгода'], [365, 'Год']]);
    }
    if (!areas.length && this.kind === 'project' && !all.some((x) => x.kind === 'goal')) body.createDiv({ cls: 'fd-w-hint', text: 'Пока не с чем связать — это можно сделать позже' });
  }

  async next() {
    const s = this.steps[this.step];
    if (!(s.optional || this.answered(s))) return;
    if (this.step < this.steps.length - 1) { this.step++; this.render(); return; }
    if (!meaningful(this.e.title)) { this.step = 0; this.render(); return; }
    this.collect();
    this.e.status = this.kind === 'area' || (this.kind !== 'review' && this.canActivate()) ? 'active' : 'draft';
    try {
      const file = await createLifeNote(this.app, this.e);
      this.close();
      new Notice(this.e.status === 'active' ? `${KIND_SINGULAR[this.kind]} создан${this.kind === 'goal' || this.kind === 'area' ? 'а' : this.kind === 'decision' ? 'о' : ''}` : 'Сохранено как идея');
      if (this.done) this.done(file);
    } catch (err) {
      new Notice('Не удалось сохранить: ' + err.message);
    }
  }
}

/** Small dialogs for the actions on a note's header card. */
class LifeDialog extends require('obsidian').Modal {
  constructor(app, title, build) { super(app); this.title = title; this.build = build; }
  onOpen() {
    this.modalEl.addClass('fd-wizard');
    this.contentEl.createDiv({ cls: 'fd-w-q', text: this.title });
    this.build(this.contentEl.createDiv('fd-w-body'), this);
  }
  footer(label, kind, onClick) {
    const foot = this.contentEl.createDiv('fd-w-foot');
    const b = foot.createEl('button', { cls: `fd-w-primary is-${kind}` });
    b.createSpan({ text: label });
    setIcon(b.createSpan('fd-jr-icon'), 'check');
    b.onclick = async () => { if ((await onClick()) !== false) this.close(); };
    return b;
  }
}

function openOutcomeDialog(app, file) {
  const state = { outcome: '', verdict: null, lesson: '', close: true };
  new LifeDialog(app, 'Что произошло на самом деле?', (body, dlg) => {
    body.createDiv({ cls: 'fd-w-hint', text: 'Сначала факты — отдельно от того, чего ждал.' });
    growArea(body, '', 'Доход вырос на 20%, но стресса стало больше', (v) => { state.outcome = v; }, 3);
    body.createDiv({ cls: 'fd-w-label', text: 'Ожидание сбылось?' });
    chipRow(body, [['да', 'Да'], ['частично', 'Частично'], ['нет', 'Нет']], null, (v) => { state.verdict = v; });
    body.createDiv({ cls: 'fd-w-q is-sub', text: 'Что ты понял?' });
    growArea(body, '', 'В следующий раз закладывать время на адаптацию', (v) => { state.lesson = v; }, 2);
    const row = body.createEl('label', { cls: 'fd-w-check' });
    const cb = row.createEl('input', { attr: { type: 'checkbox' } });
    cb.checked = true;
    cb.onchange = () => { state.close = cb.checked; };
    row.createSpan({ text: 'Закрыть решение' });
    dlg.footer('Записать результат', 'decision', async () => {
      if (!meaningful(state.outcome)) { new Notice('Запиши фактический результат проверки'); return false; }
      await lifeActions.recordOutcome(app, file, state);
    });
  }).open();
}

function openReviewDateDialog(app, file, current) {
  let picked = null;
  new LifeDialog(app, 'Когда проверить?', (body, dlg) => {
    const opts = [[7, 'Через неделю'], [14, 'Через 2 недели'], [30, 'Через месяц'], [90, 'Через 3 месяца']].map(([d, l]) => [addDays(d), l]);
    const row = chipRow(body, opts, null, (v) => { picked = v; });
    const inp = row.createEl('input', { cls: 'fd-w-date', attr: { type: 'date' } });
    inp.value = current || '';
    inp.onchange = () => { picked = inp.value; row.querySelectorAll('.fd-w-chip').forEach((c) => c.removeClass('is-on')); };
    body.createDiv({ cls: 'fd-w-hint', text: 'Перенос попадёт в историю решения — это не то же самое, что проверка.' });
    dlg.footer('Перенести', 'decision', async () => {
      if (!picked) return false;
      await lifeActions.moveReview(app, file, picked);
    });
  }).open();
}

function openEvidenceDialog(app, file) {
  let text = '';
  new LifeDialog(app, 'Чем подтверждается результат?', (body, dlg) => {
    body.createDiv({ cls: 'fd-w-hint', text: 'Факт, по которому видно, что цель достигнута.' });
    growArea(body, '', 'Пробежал 21 км за 2:05 на забеге 28 апреля', (v) => { text = v; }, 3);
    dlg.footer('Цель достигнута', 'goal', async () => {
      if (!meaningful(text)) { new Notice('Запиши факт, который подтверждает достижение цели'); return false; }
      await lifeActions.setStatus(app, file, 'done', { evidence: text });
    });
  }).open();
}

function openMeasureDialog(app, file, unit) {
  let value = '';
  new LifeDialog(app, 'Новый замер', (body, dlg) => {
    body.createDiv({ cls: 'fd-w-hint', text: `Сегодня, ${new Date().toLocaleDateString('ru', { day: 'numeric', month: 'long' })}` });
    const inp = body.createEl('input', { cls: 'fd-w-line is-big', attr: { type: 'text', inputmode: 'decimal', placeholder: unit ? `0 ${unit}` : '0' } });
    inp.oninput = () => { value = inp.value; };
    setTimeout(() => inp.focus(), 30);
    dlg.footer('Записать', 'goal', async () => {
      const n = Number(value.replace(/\s/g, '').replace(',', '.'));
      if (!value.trim() || isNaN(n)) return false;
      await lifeActions.addMeasure(app, file, Number.isInteger(n) ? String(n) : n.toFixed(1));
    });
  }).open();
}

function openNewLifeMenu(app, evt) {
  const { Menu } = require('obsidian');
  const menu = new Menu();
  for (const k of ['goal', 'project', 'decision', 'area', 'review']) {
    menu.addItem((i) => i.setTitle(KIND_NEW[k]).setIcon(KINDS[k].icon).onClick(() => openLifeWizard(app, k)));
  }
  menu.showAtMouseEvent(evt);
}

function openLifeWizard(app, kind) {
  new LifeWizard(app, kind, (file) => app.workspace.getLeaf(false).openFile(file)).open();
}

// ---------- Bases view: cards and day tiles instead of spreadsheet tables ----------

const BOARD_VIEW = 'focusdeck-board';
const DAY_NAME = /^(?:00 - )?(\d{4}-\d{2}-\d{2})$/;
const KINDS = {
  goal: { label: 'Цели', icon: 'target', empty: 'Цель — результат, который ты выбираешь получить' },
  project: { label: 'Проекты', icon: 'folder-kanban', empty: 'Проект — конечная работа с понятным результатом' },
  area: { label: 'Области жизни', icon: 'compass', empty: 'То, что важно поддерживать постоянно' },
  decision: { label: 'Решения', icon: 'split', empty: 'Выбор, основания и проверка результата' },
  review: { label: 'Обзоры', icon: 'notebook-pen', empty: 'Что произошло и что стоит изменить' },
};
const KIND_BY_FOLDER = { 'Цели': 'goal', 'Проекты': 'project', 'Области': 'area', 'Решения': 'decision', 'Обзоры': 'review' };
const STATUS_ORDER = { active: 0, draft: 1, paused: 2, done: 3, archived: 4 };

function statusLabel(kind, status) {
  if (status === 'active') return { decision: 'Принято', area: 'Поддерживаю' }[kind] || 'В работе';
  if (status === 'done') return kind === 'goal' ? 'Достигнута' : 'Завершено';
  if (status === 'draft') return kind === 'review' ? 'Черновик' : 'Обдумываю';
  return { paused: 'На паузе', archived: 'В архиве' }[status] || '';
}

const dayOf = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
function daysFrom(today, s) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s || '')) return null;
  return Math.round((dayOf(s) - dayOf(today)) / 86_400_000);
}
function inDays(n) {
  if (n === 0) return 'сегодня';
  if (n === 1) return 'завтра';
  if (n === -1) return 'вчера';
  const a = Math.abs(n);
  const w = a % 10 === 1 && a % 100 !== 11 ? 'день' : a % 10 >= 2 && a % 10 <= 4 && (a % 100 < 12 || a % 100 > 14) ? 'дня' : 'дней';
  return n > 0 ? `через ${a} ${w}` : `${a} ${w} назад`;
}
function ago(ms) {
  const n = daysFrom(ymd(new Date()), ymd(new Date(ms)));
  if (n === 0) return new Date(ms).toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit' });
  if (n > -7) return inDays(n);
  return new Date(ms).toLocaleDateString('ru', { day: 'numeric', month: 'short' });
}

class FocusDeckBoard extends (require('obsidian').BasesView || Object) {
  constructor(controller, containerEl) {
    super(controller);
    this.type = BOARD_VIEW;
    this.root = containerEl.createDiv('fd-board');
    this.seq = 0;
    this.query = '';
  }

  onDataUpdated() { this.render(); }

  open(file, e) {
    this.app.workspace.getLeaf(e && (e.metaKey || e.ctrlKey) ? 'tab' : false).openFile(file);
  }

  async render() {
    const seq = ++this.seq;
    const entries = (this.data && this.data.data) || [];
    const files = entries.map((e) => e.file).filter((f) => f instanceof TFile);
    const cache = (f) => this.app.metadataCache.getFileCache(f) || {};
    const days = files.filter((f) => DAY_NAME.test(f.basename));
    const life = files.filter((f) => String(cache(f).frontmatter?.focusdeck) === '1');
    let build;
    // Stray non-day notes in a journal folder don't turn the whole view back into a file list.
    if (days.length && days.length * 2 >= files.length) {
      const days_ = await this.buildDays(days);
      const rest = this.buildNotes(files.filter((f) => !days.includes(f)), true);
      // The short "recent days" strip shows days only; stray notes are listed in the full journal.
      const strip = days.length <= 7;
      build = (root) => { days_(root); if (!strip && days.length < files.length) rest(root); };
    }
    else if ((life.length && life.length === files.length) || this.config?.name === 'Всё') build = await this.buildLife(life, this.config?.name === 'Всё');
    else build = this.buildNotes(files);
    if (seq !== this.seq) return;
    this.root.empty();
    build(this.root);
  }

  // ----- Goals, projects, decisions: one card each, sectioned by kind -----
  async buildLife(files, overview = false) {
    const today = ymd(new Date());
    const items = await Promise.all(files.map(async (file) => {
      const fm = this.app.metadataCache.getFileCache(file)?.frontmatter || {};
      const kind = String(fm.kind || KIND_BY_FOLDER[file.parent?.name] || '').toLowerCase();
      const status = String(fm.status || 'draft').toLowerCase();
      const text = await this.app.vault.cachedRead(file);
      const stages = lifeSection(text, 'Этапы').split('\n').map((l) => TASK.exec(l)).filter(Boolean);
      const firstLine = (h) => lifeSection(text, h).split('\n').map((l) => l.replace(/^[-*]\s*(\[.\]\s*)?/, '').trim()).find(Boolean) || '';
      const hint = kind === 'project' ? firstLine('Следующее действие')
        : kind === 'goal' ? firstLine('Как пойму, что готово')
        : kind === 'decision' ? firstLine('Мой выбор и основания') || firstLine('Критерии выбора')
        : kind === 'area' ? firstLine('Что значит достаточно')
        : firstLine('Главное на неделю') || firstLine('Что получилось');
      const date = fm.due ? { n: daysFrom(today, String(fm.due)), what: 'срок' }
        : fm.review ? { n: daysFrom(today, String(fm.review)), what: 'сверка' } : null;
      return {
        file, kind, status, title: String(fm.title || file.basename.replace(/ -- [0-9a-f-]{36}$/, '')), hint, date,
        done: stages.filter((m) => m[1] !== ' ').length, total: stages.length,
      };
    }));
    return (root) => {
      const kinds = overview ? Object.keys(KINDS)
        : [...new Set(items.map((i) => i.kind))].sort((a, b) => Object.keys(KINDS).indexOf(a) - Object.keys(KINDS).indexOf(b));
      const active = items.filter((i) => i.status === 'active');
      // Due: active work past its review date, and drafts (decisions) past the date they were meant to be made.
      const attention = items.filter((i) => (i.status === 'active' || i.status === 'draft') && i.date && i.date.n != null && i.date.n <= 0);
      if (overview) {
        const hero = root.createDiv('fd-b-hero');
        const row = hero.createDiv('fd-b-hero-row');
        row.createDiv({ cls: 'fd-b-hero-title', text: 'Жизнь' });
        const add = row.createEl('button', { cls: 'fd-lh-btn is-primary' });
        setIcon(add.createSpan('fd-jr-icon'), 'plus');
        add.createSpan({ text: 'Новая запись' });
        add.onclick = (e) => openNewLifeMenu(this.app, e);
        hero.createDiv({ cls: 'fd-b-hero-sub', text: 'Сферы, цели, проекты, решения и обзоры — те же, что в приложении FocusDeck. Создавай и веди их здесь или на телефоне.' });
      }
      if (kinds.length > 1) {
        const sum = root.createDiv('fd-b-summary');
        const stat = (n, label, cls = '') => { const s = sum.createDiv('fd-b-stat ' + cls); s.createDiv({ cls: 'fd-b-stat-n', text: String(n) }); s.createDiv({ cls: 'fd-b-stat-l', text: label }); };
        stat(active.length, 'в работе');
        stat(items.filter((i) => i.status === 'draft').length, 'обдумываю');
        stat(attention.length, 'пора свериться', attention.length ? 'is-attn' : '');
        stat(items.filter((i) => i.status === 'done').length, 'завершено', 'is-ok');
      }
      if (overview) {
        if (attention.length) {
          const box = root.createDiv('fd-b-attn');
          const h = box.createDiv('fd-b-attn-head');
          setIcon(h.createSpan('fd-jr-icon'), 'alarm-clock');
          h.createSpan({ text: 'Пора вернуться' });
          for (const i of attention) {
            const row = box.createDiv('fd-b-attn-row');
            row.onclick = (e) => this.open(i.file, e);
            setIcon(row.createSpan('fd-jr-icon'), KINDS[i.kind]?.icon || 'file');
            row.createSpan({ cls: 'fd-b-attn-title', text: i.title });
            row.createSpan({ cls: 'fd-b-meta', text: `${i.date.what} ${inDays(i.date.n)}` });
          }
        }
      }
      const emptyKinds = kinds.filter((k) => !items.some((i) => i.kind === k));
      for (const kind of kinds.filter((k) => !emptyKinds.includes(k))) {
        const group = items.filter((i) => i.kind === kind).sort((a, b) =>
          (STATUS_ORDER[a.status] ?? 9) - (STATUS_ORDER[b.status] ?? 9) || b.file.stat.mtime - a.file.stat.mtime);
        const live = group.filter((i) => i.status !== 'done' && i.status !== 'archived');
        const past = group.filter((i) => !live.includes(i));
        const section = root.createDiv('fd-b-section');
        if (kinds.length > 1) {
          const head = section.createDiv('fd-b-head');
          setIcon(head.createSpan('fd-b-head-icon'), KINDS[kind]?.icon || 'file');
          head.createSpan({ text: KINDS[kind]?.label || kind });
          head.createSpan({ cls: 'fd-b-count', text: String(group.length) });
          const plus = head.createEl('button', { cls: 'fd-b-plus', attr: { 'aria-label': KIND_NEW[kind] } });
          setIcon(plus, 'plus');
          plus.onclick = () => openLifeWizard(this.app, kind);
        }
        const grid = section.createDiv('fd-b-grid');
        live.forEach((i) => this.lifeCard(grid, i));

        if (past.length) {
          const more = section.createEl('details', { cls: 'fd-b-past' });
          more.createEl('summary', { text: `Завершённые и в архиве · ${past.length}` });
          const pg = more.createDiv('fd-b-grid');
          past.forEach((i) => this.lifeCard(pg, i));
        }
      }
      if (overview) this.emptyKinds(root, emptyKinds);
    };
  }

  emptyKinds(root, kinds) {
    if (!kinds.length) return;
    const box = root.createDiv('fd-b-section');
    const head = box.createDiv('fd-b-head');
    head.createSpan({ text: 'Ещё не начато' });
    const row = box.createDiv('fd-b-empties');
    for (const k of kinds) {
      const chip = row.createDiv({ cls: 'fd-b-emptykind' });
      chip.onclick = () => openLifeWizard(this.app, k);
      setIcon(chip.createSpan('fd-jr-icon'), KINDS[k].icon);
      const t = chip.createDiv();
      t.createDiv({ cls: 'fd-b-emptykind-title', text: KINDS[k].label });
      t.createDiv({ cls: 'fd-b-emptykind-hint', text: KINDS[k].empty });
      const go = chip.createDiv('fd-b-emptykind-go');
      setIcon(go, 'plus');
    }
  }

  lifeCard(grid, i) {
    const card = grid.createDiv({ cls: `fd-b-card is-${i.status} is-k-${i.kind}` });
    card.onclick = (e) => this.open(i.file, e);
    const top = card.createDiv('fd-b-card-top');
    setIcon(top.createDiv('fd-b-kind'), KINDS[i.kind]?.icon || 'file');
    top.createDiv({ cls: `fd-b-pill is-${i.status}`, text: statusLabel(i.kind, i.status) });
    card.createDiv({ cls: 'fd-b-title', text: i.title });
    if (i.hint) card.createDiv({ cls: 'fd-b-hint', text: i.hint });
    const foot = card.createDiv('fd-b-foot');
    if (i.total) {
      const pct = Math.round((i.done / i.total) * 100);
      const bar = foot.createDiv('fd-b-bar');
      bar.createDiv('fd-b-bar-fill').style.width = `${pct}%`;
      foot.createDiv({ cls: 'fd-b-meta', text: `${i.done}/${i.total}` });
    }
    if (i.date && i.date.n != null && i.status !== 'done' && i.status !== 'archived') {
      const late = i.date.n <= 0 && (i.status === 'active' || i.status === 'draft');
      const chip = foot.createDiv({ cls: 'fd-b-date' + (late ? ' is-late' : '') });
      setIcon(chip.createSpan(), i.date.what === 'срок' ? 'flag' : 'refresh-cw');
      chip.createSpan({ text: `${i.date.what} ${Math.abs(i.date.n) > 30 ? dayOf(addDays(i.date.n)).toLocaleDateString('ru', { day: 'numeric', month: 'long' }) : inDays(i.date.n)}` });
    }
  }

  // ----- Daily notes: tiles with weekday, task progress and the day's main thing -----
  async buildDays(files) {
    const today = ymd(new Date());
    const items = await Promise.all(files.map(async (file) => {
      const day = DAY_NAME.exec(file.basename)[1];
      const lines = (await this.app.vault.cachedRead(file)).split('\n');
      const { tasks } = parseTasks(lines);
      const journal = journalState(lines);
      return {
        file, day, main: ritualValue(lines, 'Главное'),
        done: tasks.filter((t) => t.done).length, total: tasks.length,
        journal: journal ? JOURNAL_FIELDS.filter((f) => journal[f]).length : 0,
      };
    }));
    const compact = items.length <= 7;
    return (root) => {
      let grid = null;
      let month = '';
      for (const i of items) {
        const d = dayOf(i.day);
        const m = d.toLocaleDateString('ru', { month: 'long', year: 'numeric' });
        if (!grid || (!compact && m !== month)) {
          if (!compact) root.createDiv({ cls: 'fd-b-month', text: m });
          grid = root.createDiv(compact ? 'fd-b-week' : 'fd-b-days');
          month = m;
        }
        const tile = grid.createDiv({ cls: 'fd-b-day' + (i.day === today ? ' is-today' : '') + (i.day > today ? ' is-future' : '') });
        tile.onclick = (e) => this.open(i.file, e);
        tile.createDiv({ cls: 'fd-b-wd', text: i.day === today ? 'сегодня' : d.toLocaleDateString('ru', { weekday: 'long' }) });
        const head = tile.createDiv('fd-b-day-head');
        head.createDiv({ cls: 'fd-b-dn', text: String(d.getDate()) });
        head.createDiv({ cls: 'fd-b-mn', text: d.toLocaleDateString('ru', { month: 'short', day: 'numeric' }).replace(/^\d+\s*/, '') });
        if (i.main) tile.createDiv({ cls: 'fd-b-day-main', text: i.main });
        const foot = tile.createDiv('fd-b-day-foot');
        if (i.total) {
          const bar = foot.createDiv('fd-b-bar');
          bar.createDiv('fd-b-bar-fill').style.width = `${Math.round((i.done / i.total) * 100)}%`;
          foot.createDiv({ cls: 'fd-b-meta', text: `${i.done}/${i.total}` });
        } else foot.createDiv({ cls: 'fd-b-meta', text: 'без плана' });
        if (i.journal) {
          const j = foot.createDiv({ cls: 'fd-b-journal', attr: { 'aria-label': `Дневник: ${i.journal} из 4` } });
          setIcon(j, 'book-open');
        }
      }
      if (!items.length) root.createDiv({ cls: 'fd-b-empty', text: 'Дней пока нет' });
    };
  }

  // ----- Plain notes: a quiet list with search, grouped by subfolder -----
  buildNotes(files, aside = false) {
    const base = commonFolder(files);
    return (root) => {
      if (aside) root.createDiv({ cls: 'fd-b-month', text: 'Другие заметки' });
      // Keep the base's order inside a folder, but one heading per folder, root notes first.
      const folderOf = (f) => (f.parent && f.parent.path !== base ? f.parent.path : '');
      const firstSeen = new Map();
      files.forEach((f, i) => { if (!firstSeen.has(folderOf(f))) firstSeen.set(folderOf(f), folderOf(f) ? i + 1 : 0); });
      const filterFiles = files.map((f, i) => [f, i]).sort((a, b) =>
        firstSeen.get(folderOf(a[0])) - firstSeen.get(folderOf(b[0])) || a[1] - b[1]).map((x) => x[0]);
      let list;
      const draw = () => {
        list.empty();
        const q = this.query.toLowerCase();
        const shown = filterFiles.filter((f) => !q || f.basename.toLowerCase().includes(q) || f.path.toLowerCase().includes(q));
        let folder = null;
        for (const f of shown) {
          const rel = f.parent && f.parent.path !== base ? f.parent.path.slice(base ? base.length + 1 : 0) : '';
          if (rel !== folder) {
            folder = rel;
            if (rel) list.createDiv({ cls: 'fd-b-folder', text: rel.split('/').join(' › ') });
          }
          const row = list.createDiv('fd-b-row');
          row.onclick = (e) => this.open(f, e);
          setIcon(row.createDiv('fd-b-row-icon'), 'file-text');
          row.createDiv({ cls: 'fd-b-row-title', text: f.basename });
          row.createDiv({ cls: 'fd-b-row-meta', text: ago(f.stat.mtime) });
        }
        if (shown.length) return;
        // An empty goals/projects view explains what belongs there instead of showing a blank grid.
        const kind = Object.values(KINDS).find((k) => k.label === (this.config && this.config.name));
        const empty = list.createDiv('fd-b-empty');
        if (kind && !files.length) {
          setIcon(empty.createDiv('fd-b-empty-icon'), kind.icon);
          empty.createDiv({ cls: 'fd-b-empty-title', text: `${kind.label} пока нет` });
          empty.createDiv({ text: `${kind.empty}.` });
          const k = Object.keys(KINDS).find((x) => KINDS[x] === kind);
          const add = empty.createEl('button', { cls: 'fd-lh-btn is-primary fd-b-empty-add' });
          setIcon(add.createSpan('fd-jr-icon'), 'plus');
          add.createSpan({ text: KIND_NEW[k] });
          add.onclick = () => openLifeWizard(this.app, k);
        } else empty.setText(files.length ? 'Ничего не нашлось' : 'Здесь пока пусто');
      };
      if (files.length > 10 && !aside) {
        const search = root.createEl('input', { cls: 'fd-b-search', attr: { type: 'search', placeholder: `Поиск среди ${files.length}` } });
        search.value = this.query;
        search.addEventListener('input', () => { this.query = search.value; draw(); });
      }
      list = root.createDiv('fd-b-list');
      draw();
    };
  }
}

function commonFolder(files) {
  if (!files.length) return '';
  let parts = (files[0].parent?.path || '').split('/');
  for (const f of files) {
    const p = (f.parent?.path || '').split('/');
    let k = 0;
    while (k < parts.length && parts[k] === p[k]) k++;
    parts = parts.slice(0, k);
  }
  const path = parts.join('/');
  return path === '/' ? '' : path;
}

module.exports = class FocusDeckPanel extends Plugin {
  async onload() {
    // Settings first: Obsidian restores the panel as soon as the view is registered.
    const saved = (await this.loadData()) || {};
    this.settings = { ...saved, letGo: saved.letGo || {} };
    this.hideMeta = saved.hideMeta !== false;
    this.registerView(VIEW_TYPE, (leaf) => new FocusDeckView(leaf, this));
    this.addCommand({ id: 'open', name: 'Открыть панель', callback: () => this.reveal() });
    this.addCommand({ id: 'plan', name: 'План дня', callback: () => this.reveal(true, 'plan') });
    this.addCommand({ id: 'journal', name: 'Дневник дня', callback: () => this.reveal(true, 'journal') });
    this.addRibbonIcon('sun', 'FocusDeck', () => this.reveal());

    const refresh = () => {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.views().forEach((v) => v.requestRender()), 300);
    };
    const relevant = (f) => f instanceof TFile && (f.path.startsWith('FocusDeck/') || /^00 - \d{4}-\d{2}-\d{2}\.md$/.test(f.name));
    this.registerEvent(this.app.vault.on('modify', (f) => relevant(f) && refresh()));
    this.registerEvent(this.app.vault.on('create', (f) => relevant(f) && refresh()));
    this.registerEvent(this.app.vault.on('delete', (f) => relevant(f) && refresh()));
    this.registerEvent(this.app.vault.on('rename', (f, oldPath) => {
      if (relevant(f) || oldPath.startsWith('FocusDeck/') || /(?:^|\/)00 - \d{4}-\d{2}-\d{2}\.md$/.test(oldPath)) refresh();
    }));
    this.registerDomEvent(window, 'focus', refresh);
    this.registerDomEvent(document, 'visibilitychange', () => { if (!document.hidden) refresh(); });
    this.registerEvent(this.app.metadataCache.on('changed', (f) => f.path.startsWith('FocusDeck/') && refresh()));
    // The date and the "now" highlight move on their own.
    this.registerInterval(window.setInterval(refresh, 60_000));

    document.body.toggleClass('fd-clean', this.hideMeta);
    const hider = cleanModeExtension(this);
    if (hider) this.registerEditorExtension(hider);
    // Capture phase: Obsidian's HTML widget handles the click before CodeMirror would see it.
    if (this.journalMouseDown) this.registerDomEvent(document, 'mousedown', this.journalMouseDown, { capture: true });
    this.registerMarkdownPostProcessor((el) => {
      if (!this.hideMeta) return;
      hideInReading(el);
      enhanceJournal(el);
      journalListInReading(el);
      ritualInReading(el);
    });

    this.addCommand({
      id: 'toggle-meta',
      name: 'Чистый режим: скрыть/показать разметку',
      hotkeys: [{ modifiers: ['Mod', 'Shift'], key: 'h' }],
      callback: () => this.toggleMeta(),
    });
    this.addRibbonIcon('eye-off', 'Чистый режим', () => this.toggleMeta());
    // Life notes carry app bookkeeping in properties and a UUID in the file name: the header card shows what matters.
    const markLife = () => this.app.workspace.iterateAllLeaves((leaf) => {
      const f = leaf.view && leaf.view.file;
      if (!leaf.view || leaf.view.getViewType() !== 'markdown') return;
      const fm = f && this.app.metadataCache.getFileCache(f)?.frontmatter;
      leaf.view.containerEl.toggleClass('fd-life-note', !!fm && String(fm.focusdeck) === '1');
    });
    this.registerEvent(this.app.workspace.on('layout-change', markLife));
    this.registerEvent(this.app.workspace.on('file-open', () => setTimeout(markLife, 0)));
    this.registerEvent(this.app.metadataCache.on('changed', () => markLife()));
    this.app.workspace.onLayoutReady(markLife);
    for (const k of ['goal', 'project', 'decision', 'area', 'review']) {
      this.addCommand({ id: `new-${k}`, name: KIND_NEW[k], callback: () => openLifeWizard(this.app, k) });
    }

    // Navigation bases render as cards and day tiles; the core table view stays available in the view menu.
    const board = { name: 'FocusDeck', icon: 'layout-grid', factory: (controller, el) => new FocusDeckBoard(controller, el) };
    const boardReady = this.registerBasesView && this.registerBasesView(BOARD_VIEW, board);

    this.app.workspace.onLayoutReady(() => {
      if (this.registerBasesView && !boardReady) this.registerBasesView(BOARD_VIEW, board);
      // The saved layout brings its own panel back a moment later; don't race it with a new one.
      setTimeout(() => this.reveal(false), 500);
    });
  }

  async toggleMeta() {
    this.hideMeta = !this.hideMeta;
    if (!this.hideMeta) unenhanceJournal(document.body);
    document.body.toggleClass('fd-clean', this.hideMeta);
    await this.saveSettings();
    this.app.workspace.iterateAllLeaves((leaf) => {
      const cm = leaf.view && leaf.view.editor && leaf.view.editor.cm;
      if (cm && this.refreshEffect) cm.dispatch({ effects: this.refreshEffect.of(null) });
      if (leaf.view && leaf.view.previewMode && leaf.view.previewMode.rerender) leaf.view.previewMode.rerender(true);
    });
    new Notice(this.hideMeta ? 'Чистый режим включён' : 'Разметка видна');
  }

  async saveSettings() {
    this.settings.hideMeta = this.hideMeta;
    const cutoff = ymd(shiftDay(new Date(), -45));
    for (const [k, v] of Object.entries(this.settings.letGo)) if (v < cutoff) delete this.settings.letGo[k];
    await this.saveData(this.settings);
  }

  onunload() { clearTimeout(this.timer); document.body.removeClass('fd-clean'); }

  views() {
    return this.app.workspace.getLeavesOfType(VIEW_TYPE).map((l) => l.view).filter((v) => v instanceof FocusDeckView);
  }

  async reveal(focus = true, screen = null) {
    // One panel in the sidebar: a reload or a restored layout must not stack copies of it.
    const side = this.app.workspace.getLeavesOfType(VIEW_TYPE).filter((l) => l.getRoot() !== this.app.workspace.rootSplit);
    side.slice(1).forEach((l) => l.detach());
    let leaf = side[0] || this.app.workspace.getLeavesOfType(VIEW_TYPE)[0];
    if (!leaf) {
      leaf = this.app.workspace.getLeftLeaf(false);
      await leaf.setViewState({ type: VIEW_TYPE, active: true });
    }
    if (screen && leaf.view instanceof FocusDeckView) leaf.view.go(screen, new Date());
    // The panel is the sidebar's first tab: shown instead of the file navigator, not hidden behind it.
    if (focus || !this.app.isMobile) this.app.workspace.revealLeaf(leaf);
  }
};
