<template>
  <article class="message" :class="role">
    <header class="message-meta">
      <span>{{ role === 'assistant' ? 'Rover Agent' : '我' }}</span>
      <span v-if="streaming" class="live-mark"><i></i> 正在生成</span>
    </header>
    <div ref="content" class="message-content"></div>
    <p v-if="hint" class="message-hint">{{ hint }}</p>
  </article>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'

type BlockKind = 'paragraph' | 'heading' | 'list' | 'quote' | 'table' | 'code' | 'rule'
type InlineNode = { marker: string; node: HTMLElement }
type Block = { kind: BlockKind; container: HTMLElement; textNode: Text | null; fenced: boolean; openingFence?: boolean; openingHeader?: string; fenceChar?: string; fenceLength?: number; listTag?: string; lastItem?: HTMLElement; inlineStack?: InlineNode[]; inlinePending?: string; tableHeaderSeen?: boolean }

const props = withDefaults(defineProps<{ role: 'user' | 'assistant'; streaming?: boolean; hint?: string; snapshot?: string }>(), { streaming: false, hint: '', snapshot: '' })
const emit = defineEmits<{ content: [] }>()
const content = ref<HTMLElement | null>(null)
let activeBlock: Block | null = null
let lineBuffer = ''
let hasPendingCR = false
let renderedLength = 0
let lineVisible = false
let needsLineBreak = false
let destroyed = false

function appendText(block: Block, text: string) {
  if (!text) return
  if (!block.textNode || block.textNode.parentNode !== block.container) {
    block.textNode = document.createTextNode('')
    block.container.append(block.textNode)
  }
  block.textNode.appendData(text)
  emit('content')
}

function appendMarkdownText(block: Block, value: string) {
  if (block.kind === 'code') { appendText(block, value); return }
  let source = (block.inlinePending || '') + value
  block.inlinePending = ''
  let plain = ''
  const parent = () => block.inlineStack?.[block.inlineStack.length - 1]?.node || block.lastItem || block.container
  const flush = () => {
    if (!plain) return
    if (!block.textNode || block.textNode.parentNode !== parent()) { block.textNode = document.createTextNode(''); parent().append(block.textNode) }
    block.textNode.appendData(plain)
    plain = ''
    emit('content')
  }
  for (let index = 0; index < source.length;) {
    const top = block.inlineStack?.[block.inlineStack.length - 1]
    if (top && source.startsWith(top.marker, index) && !/\s/.test(source[index - 1] || '')) { flush(); block.inlineStack!.pop(); block.textNode = null; index += top.marker.length; continue }
    if (source[index] === '\\') {
      if (index + 1 === source.length) { flush(); block.inlinePending = '\\'; return }
      plain += source[index + 1]
      index += 2
      continue
    }
    if (top?.marker === '`' && source[index] !== '`') { plain += source[index++]; continue }
    if (source[index] === '[') {
      const labelEnd = source.indexOf('](', index + 1)
      if (labelEnd < 0 && !source.includes(']', index + 1)) { flush(); block.inlinePending = source.slice(index); return }
      if (labelEnd >= 0) {
        let depth = 0
        let linkEnd = -1
        for (let cursor = labelEnd + 2; cursor < source.length; cursor++) {
          if (source[cursor] === '(') depth++
          else if (source[cursor] === ')') { if (!depth) { linkEnd = cursor; break } depth-- }
        }
        if (linkEnd < 0) { flush(); block.inlinePending = source.slice(index); return }
        flush()
        appendParsedInline(block, source.slice(index, linkEnd + 1))
        index = linkEnd + 1
        continue
      }
    }
    const marker = ['**', '__', '~~', '*', '_', '`'].find(candidate => source.startsWith(candidate, index)) || ''
    if (marker === '_' && /[\p{L}\p{N}]/u.test(source[index - 1] || '') && /[\p{L}\p{N}]/u.test(source[index + marker.length] || '')) { plain += source[index++]; continue }
    if (marker && index + marker.length === source.length) { flush(); block.inlinePending = source.slice(index); return }
    const next = source[index + marker.length] || ''
    const previous = source[index - 1] || ''
    const canOpen = marker && !/\s/.test(next) && (marker.length > 1 || !/[\p{L}\p{N}]/u.test(previous))
    const canClose = marker && top?.marker === marker && !/\s/.test(previous)
    if (canOpen && !canClose) {
      flush()
      const node = document.createElement(marker === '**' || marker === '__' ? 'strong' : marker === '~~' ? 'del' : marker === '`' ? 'code' : 'em')
      parent().append(node)
      block.inlineStack ||= []
      block.inlineStack.push({ marker, node })
      block.textNode = null
      index += marker.length
      continue
    }
    plain += source[index++]
  }
  flush()
}

function closeBlock() { activeBlock = null }

function createBlock(kind: BlockKind, tag: string, className = '') {
  const node = document.createElement(tag)
  if (className) node.className = className
  content.value?.append(node)
  activeBlock = { kind, container: node, textNode: null, fenced: false }
  emit('content')
  return activeBlock
}

function sanitizeInline(value: string) {
  const template = document.createElement('template')
  template.innerHTML = marked.parseInline(value, { async: false }) as string
  const allowed = new Set(['STRONG', 'EM', 'DEL', 'CODE', 'A', 'BR'])
  const nodes = Array.from(template.content.querySelectorAll('*'))
  for (const node of nodes) {
    if (!template.content.contains(node)) continue
    if (!allowed.has(node.tagName)) { node.replaceWith(document.createTextNode(node.textContent || '')); continue }
    Array.from(node.attributes).forEach(attribute => {
      if (attribute.name !== 'href' || !/^(https?:|mailto:)/i.test(attribute.value)) node.removeAttribute(attribute.name)
    })
  }
  return template.content
}

function appendParsedInline(block: Block, value: string) {
  const target = block.inlineStack?.[block.inlineStack.length - 1]?.node || block.lastItem || block.container
  const fragment = sanitizeInline(value)
  while (fragment.firstChild) target.append(fragment.firstChild)
  block.textNode = null
  emit('content')
}

function completeInline(block: Block) {
  const pending = block.inlinePending
  if (pending) {
    block.inlinePending = ''
    const top = block.inlineStack?.[block.inlineStack.length - 1]
    if (top?.marker === pending) { block.inlineStack!.pop(); block.textNode = null }
    else {
      const target = top?.node || block.lastItem || block.container
      if (!block.textNode || block.textNode.parentNode !== target) { block.textNode = document.createTextNode(''); target.append(block.textNode) }
      block.textNode.appendData(pending)
      emit('content')
    }
  }
  if (block.inlineStack?.length) {
    const open = block.inlineStack.splice(0)
    for (const item of open.reverse()) item.node.replaceWith(document.createTextNode(item.marker), ...Array.from(item.node.childNodes))
  }
}

function createListItem(block: Block, value: string) {
  const item = document.createElement('li')
  block.container.append(item)
  block.lastItem = item
  block.inlineStack = []
  block.textNode = null
  appendMarkdownText(block, value)
}

function classifyLine(line: string): { kind: BlockKind; tag: string; initial: string; className?: string } | null {
  if (!line.trim()) return null
  if (/^\s*(```+|~~~+)/.test(line)) return { kind: 'code', tag: 'pre', className: 'code-block', initial: '' }
  if (/^\s{0,3}(#{1,6})\s+/.test(line)) {
    const level = line.match(/^\s{0,3}(#{1,6})/)?.[1].length || 2
    return { kind: 'heading', tag: `h${level}`, initial: line.replace(/^\s{0,3}#{1,6}\s+/, '') }
  }
  if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) return { kind: 'rule', tag: 'hr', initial: '' }
  if (/^\s*>/.test(line)) return { kind: 'quote', tag: 'blockquote', initial: line.replace(/^\s*>\s?/, '') }
  const unordered = line.match(/^\s*([-+*])\s+/)
  if (unordered) return { kind: 'list', tag: 'ul', initial: line.slice(unordered[0].length) }
  const ordered = line.match(/^\s*\d+[.)]\s+/)
  if (ordered) return { kind: 'list', tag: 'ol', initial: line.slice(ordered[0].length) }
  if (/^\s*\|?\s*:?-{3,}:?\s*(?:\||$)/.test(line) || (line.includes('|') && /^\s*\|/.test(line))) {
    return { kind: 'table', tag: 'div', className: 'table-block', initial: '' }
  }
  return { kind: 'paragraph', tag: 'p', initial: line }
}

function appendTableRow(block: Block, line: string) {
  if (/^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line)) return
  const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
  let table = block.container.querySelector('table')
  if (!table) { table = document.createElement('table'); block.container.append(table) }
  const header = !table.querySelector('thead')
  let section = table.querySelector(header ? 'thead' : 'tbody') as HTMLTableSectionElement | null
  if (!section) { section = document.createElement(header ? 'thead' : 'tbody'); table.append(section) }
  const row = document.createElement('tr')
  for (const value of cells) {
    const cell = document.createElement(header ? 'th' : 'td')
    cell.append(sanitizeInline(value))
    row.append(cell)
  }
  section.append(row)
}

function appendLine(line: string) {
  const fence = line.match(/^\s*(```+|~~~+)(.*)$/)
  if (activeBlock?.kind === 'code') {
    if (fence && fence[1][0] === activeBlock.fenceChar && fence[1].length >= (activeBlock.fenceLength || 3)) closeBlock()
    else appendText(activeBlock, line + '\n')
    return
  }
  if (fence) {
    const block = createBlock('code', 'pre', 'code-block')
    const code = document.createElement('code')
    const language = fence[2].trim().split(/\s+/)[0]
    if (language) code.className = `language-${language.replace(/[^\w-]/g, '')}`
    block.container.append(code)
    block.container = code
    block.fenced = true
    block.fenceChar = fence[1][0]
    block.fenceLength = fence[1].length
    return
  }

  const classified = classifyLine(line)
  if (activeBlock?.kind === 'table') return
  if (!activeBlock && /^\s*\|/.test(line)) {
    const table = createTableBlock()
    appendTableHeader(table, line)
    emit('content')
    return
  }
  if (activeBlock && classified?.kind !== 'list' && classified?.kind !== 'paragraph') completeInline(activeBlock)
  if (!classified) { closeBlock(); return }
  const isParagraphContinuation = activeBlock?.kind === 'paragraph' && classified.kind === 'paragraph'
  const isListContinuation = activeBlock?.kind === 'list' && classified.kind === 'list' && activeBlock.listTag === classified.tag
  const isTableContinuation = activeBlock?.kind === 'table' && classified.kind === 'table'
  if (activeBlock && !isParagraphContinuation && !isListContinuation && !isTableContinuation) closeBlock()
  if (!activeBlock) {
    const block = createBlock(classified.kind, classified.tag, classified.className || '')
    if (classified.kind === 'list') block.listTag = classified.tag
  }
  const block = activeBlock!
  if (block.kind === 'list') {
    createListItem(block, classified.initial)
  } else if (block.kind === 'table') {
    appendTableRow(block, line)
    emit('content')
  } else if (block.kind === 'rule') {
    closeBlock()
  } else if (block.kind === 'paragraph') {
    if (isParagraphContinuation) appendText(block, '\n')
    appendMarkdownText(block, classified.initial)
  } else {
    appendMarkdownText(block, classified.initial)
  }
}

function pendingIsMarkerPrefix(line: string) {
  return /^\s{0,3}(?:#{1,6}|>|[-+*]|\d+[.)]|`{1,2}|~{1,2})$/.test(line)
}

function isTableSeparator(line: string) {
  return /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line)
}

function createTableBlock() {
  const block = createBlock('table', 'div', 'table-block')
  const table = document.createElement('table')
  table.append(document.createElement('thead'), document.createElement('tbody'))
  block.container.append(table)
  return block
}

function appendTableHeader(block: Block, line: string) {
  const row = document.createElement('tr')
  for (const value of line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|')) {
    const cell = document.createElement('th')
    cell.append(sanitizeInline(value.trim()))
    row.append(cell)
  }
  block.container.querySelector('thead')?.append(row)
}

function appendStableLine(line: string) {
  if (activeBlock?.kind === 'table') {
    if (isTableSeparator(line)) activeBlock.tableHeaderSeen = true
    else if (/^\s*\|/.test(line)) {
      if (activeBlock.tableHeaderSeen) appendTableRow(activeBlock, line)
      else appendTableHeader(activeBlock, line)
      emit('content')
    } else {
      completeInline(activeBlock)
      closeBlock()
      if (line) appendLine(line)
    }
    return
  }
  appendLine(line)
}

function appendDelta(value: string, trackLength = true) {
  if (!value || props.role !== 'assistant' || destroyed) return
  if (trackLength) renderedLength += value.length
  let normalized = ''
  for (const character of value) {
    if (hasPendingCR) { normalized += '\n'; hasPendingCR = false; if (character === '\n') continue }
    if (character === '\r') hasPendingCR = true
    else normalized += character
  }
  lineBuffer += normalized
  let newline = lineBuffer.indexOf('\n')
  while (newline >= 0) {
    const line = lineBuffer.slice(0, newline)
    const classified = classifyLine(line)
    if (!line && activeBlock?.kind === 'table') {
      closeBlock()
    } else if (!line && lineVisible) {
      if (activeBlock?.kind === 'heading') closeBlock()
      else if (activeBlock && ['paragraph', 'list', 'quote'].includes(activeBlock.kind)) appendMarkdownText(activeBlock, '\n')
    } else if (activeBlock?.kind === 'code' && activeBlock.openingFence) {
      const header = `${activeBlock.openingHeader || ''}${line}`
      const opening = header.match(/^\s*(```+|~~~+)(.*)$/)
      if (opening) {
        const language = opening[2].trim().split(/\s+/)[0]
        if (language) activeBlock.container.className = `language-${language.replace(/[^\w-]/g, '')}`
      } else appendText(activeBlock, `${header}\n`)
      activeBlock.openingFence = false
      activeBlock.openingHeader = ''
    } else if (activeBlock?.kind === 'table') {
      appendStableLine(line)
    } else if (activeBlock?.kind === 'code') {
      if (/^\s*(```+|~~~+)/.test(line)) appendLine(line)
      else appendMarkdownText(activeBlock, line + '\n')
    } else if (activeBlock?.kind === 'heading') {
      closeBlock()
      if (line) appendLine(line)
    } else if (activeBlock?.kind === 'list' && classified?.kind === 'paragraph') {
      if (line) appendMarkdownText(activeBlock, line)
      else closeBlock()
    } else if (activeBlock?.kind === 'list' && classified?.kind === 'list') {
      appendLine(line)
    } else if (activeBlock?.kind === 'quote') {
      if (line.startsWith('>')) appendMarkdownText(activeBlock, '\n' + line.replace(/^\s*>\s?/, ''))
      else { closeBlock(); if (line) appendLine(line) }
    } else if (activeBlock?.kind === 'paragraph' && classified?.kind === 'paragraph') {
      if (line) appendMarkdownText(activeBlock, (needsLineBreak && !lineVisible ? '\n' : '') + line)
      else if (!lineVisible) closeBlock()
    } else appendStableLine(line)
    lineBuffer = lineBuffer.slice(newline + 1)
    needsLineBreak = !!activeBlock && ['paragraph', 'list', 'quote'].includes(activeBlock.kind)
    lineVisible = false
    newline = lineBuffer.indexOf('\n')
  }

  if (activeBlock?.kind === 'code' && activeBlock.openingFence) {
    activeBlock.openingHeader = `${activeBlock.openingHeader || ''}${lineBuffer}`
    lineBuffer = ''
    return
  }
  if (!lineBuffer || pendingIsMarkerPrefix(lineBuffer)) return
  // Keep a partial table row in the current line only; append stable cells after its newline.
  if ((activeBlock?.kind === 'table' || !activeBlock) && /^\s*\|/.test(lineBuffer)) return
  if (activeBlock?.kind === 'code') {
    const candidate = lineBuffer.trimStart()
    const fenceChar = activeBlock.fenceChar || '`'
    const fence = fenceChar.repeat(activeBlock.fenceLength || 3)
    if (fence.startsWith(candidate) || (candidate.startsWith(fence) && /^\s*$/.test(candidate.slice(fence.length)))) return
    appendMarkdownText(activeBlock, lineBuffer)
    lineVisible = true
    lineBuffer = ''
  } else if (activeBlock?.kind === 'paragraph' || activeBlock?.kind === 'heading' || activeBlock?.kind === 'quote' || activeBlock?.kind === 'list') {
    appendMarkdownText(activeBlock, (needsLineBreak && !lineVisible ? '\n' : '') + lineBuffer)
    lineVisible = true
    needsLineBreak = false
    lineBuffer = ''
  } else if (!activeBlock && classifyLine(lineBuffer)) {
    const partialFence = lineBuffer.match(/^\s*(```+|~~~+)/)
    const potentialTable = /^\s*\|/.test(lineBuffer)
    if (potentialTable) {
      createTableBlock()
    } else if (partialFence) {
      const block = createBlock('code', 'pre', 'code-block')
      const code = document.createElement('code')
      block.container.append(code)
      block.container = code
      block.fenced = true
      block.openingFence = true
      block.openingHeader = lineBuffer
      block.fenceChar = partialFence[1][0]
      block.fenceLength = partialFence[1].length
    } else {
      appendStableLine(lineBuffer)
      lineVisible = true
    }
    lineBuffer = ''
  } else if (activeBlock?.kind === 'rule' && lineBuffer.trim()) {
    closeBlock()
    appendLine(lineBuffer)
    lineBuffer = ''
  }
}

function replaceWithSnapshot(text: string) {
  if (!content.value) return
  reset()
  if (props.role === 'user') { content.value.append(document.createTextNode(text)); return }
  appendSnapshot(text, true)
}

function finish() {
  if (hasPendingCR) { lineBuffer += '\n'; hasPendingCR = false }
  if (lineBuffer) {
    if (activeBlock?.kind === 'code' || activeBlock?.kind === 'heading' || activeBlock?.kind === 'quote' || activeBlock?.kind === 'paragraph' || activeBlock?.kind === 'list') appendMarkdownText(activeBlock, lineBuffer)
    else if (activeBlock?.kind === 'table') {
      appendStableLine(lineBuffer)
    } else appendLine(lineBuffer)
    lineBuffer = ''
  }
  if (activeBlock) completeInline(activeBlock)
  lineVisible = false
  needsLineBreak = false
}

function appendSnapshot(text: string, complete = false) {
  if (text.length <= renderedLength) return
  const previousLength = renderedLength
  appendDelta(text.slice(previousLength), false)
  renderedLength = text.length
  if (complete) finish()
}

function reset() {
  content.value?.replaceChildren()
  activeBlock = null
  lineBuffer = ''
  hasPendingCR = false
  renderedLength = 0
  lineVisible = false
  needsLineBreak = false
}

function getRenderState() { return { lineBuffer, kind: activeBlock?.kind || 'none', childCount: content.value?.childElementCount || 0 } }
defineExpose({ appendDelta, finish, appendSnapshot, replaceWithSnapshot, getRenderState, reset })
watch(() => props.snapshot, snapshot => { if (snapshot) replaceWithSnapshot(snapshot) })
onMounted(() => { if (props.snapshot) replaceWithSnapshot(props.snapshot) })
onBeforeUnmount(() => { destroyed = true; activeBlock = null; lineBuffer = '' })
</script>

<style scoped>
.message { width: min(100%, 840px); line-height: 1.6; color: var(--color-text-main); }
.message.user { width: fit-content; max-width: min(100%, 720px); margin-left: auto; padding: 12px 15px; border: 1px solid var(--color-border); background: #f3f4f2; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-meta { display: flex; gap: 10px; align-items: center; color: var(--color-text-secondary); font-size: 12px; margin-bottom: 6px; }
.message-content { min-width: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.message-content :deep(p), .message-content :deep(h1), .message-content :deep(h2), .message-content :deep(h3), .message-content :deep(blockquote), .message-content :deep(ul), .message-content :deep(ol), .message-content :deep(pre), .message-content :deep(table) { margin: 0 0 10px; line-height: 1.6; }
.message-content :deep(h1), .message-content :deep(h2), .message-content :deep(h3) { font-size: 15px; font-weight: 650; }
.message-content :deep(ul), .message-content :deep(ol) { padding-left: 22px; }
.message-content :deep(blockquote) { border-left: 3px solid var(--color-border); padding-left: 12px; color: var(--color-text-secondary); }
.message-content :deep(.code-block) { overflow-x: auto; padding: 12px 14px; border: 1px solid #374151; border-radius: 4px; background: #1f2937; color: #e5e7eb; font: 13px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre; }
.message-content :deep(.code-block code) { font: inherit; }
.message-content :deep(:not(pre) > code) { padding: 1px 4px; border: 1px solid #e3e6e2; border-radius: 3px; background: #f2f3f0; font: 12px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; }
.message-content :deep(.table-block) { overflow-x: auto; }
.message-content :deep(table) { border-collapse: collapse; min-width: 280px; }
.message-content :deep(th), .message-content :deep(td) { padding: 6px 9px; border: 1px solid var(--color-border); text-align: left; }
.message-content :deep(a) { color: #486550; text-decoration: underline; }
.message-hint { margin: 7px 0 0; color: #9ca3af; font-size: 12px; line-height: 1.6; }
.live-mark { display: inline-flex; align-items: center; gap: 5px; color: var(--color-primary); }
.live-mark i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
</style>
