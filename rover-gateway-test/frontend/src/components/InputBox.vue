<template>
  <form class="composer" @submit.prevent="submit">
    <textarea
      ref="textarea"
      :value="modelValue"
      :disabled="disabled || sending"
      rows="1"
      aria-label="发送消息"
      placeholder="描述你要排查的问题…"
      @input="onInput"
      @keydown="onKeydown"
      @paste="onPaste"
    ></textarea>
    <button v-if="sending" class="send-button stop" type="button" aria-label="停止生成" @click="emit('stop')">
      <span class="stop-icon"></span><span>停止</span>
    </button>
    <button v-else class="send-button" type="submit" :disabled="disabled || !modelValue.trim()">发送</button>
  </form>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ modelValue: string; sending?: boolean; disabled?: boolean }>(), { sending: false, disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: string]; send: []; stop: [] }>()
const textarea = ref<HTMLTextAreaElement | null>(null)

function resize() {
  const input = textarea.value
  if (!input) return
  input.style.height = '44px'
  input.style.height = `${Math.min(input.scrollHeight, 150)}px`
  input.style.overflowY = input.scrollHeight > 150 ? 'auto' : 'hidden'
}

function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
  resize()
}

function submit() {
  if (props.sending || props.disabled || !props.modelValue.trim()) return
  emit('send')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); submit() }
}

function onPaste(event: ClipboardEvent) {
  const pasted = event.clipboardData?.getData('text/plain')
  if (pasted === undefined || !textarea.value) return
  event.preventDefault()
  const input = textarea.value
  const start = input.selectionStart
  const end = input.selectionEnd
  const next = props.modelValue.slice(0, start) + pasted + props.modelValue.slice(end)
  emit('update:modelValue', next)
  nextTick(() => { input.setSelectionRange(start + pasted.length, start + pasted.length); resize() })
}

watch(() => props.modelValue, () => nextTick(resize))
defineExpose({ focus: () => textarea.value?.focus() })

</script>

<style scoped>
.composer {
  display: flex; align-items: flex-end; gap: 10px; padding: 8px; border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-bg-input); box-shadow: var(--shadow-sm); transition: border-color var(--transition-fast);
}
.composer:hover { border-color: #9ca3af; }
.composer:focus-within { border-color: var(--color-border-focus); }
textarea { display: block; flex: 1; min-width: 0; min-height: 44px; max-height: 150px; padding: 10px 11px; resize: none; overflow-y: hidden; border: 0; outline: 0; background: transparent; color: var(--color-text-main); font: inherit; line-height: 1.6; }
textarea::placeholder { color: #9ca3af; }
textarea:disabled { background: var(--color-bg-disabled); color: var(--color-text-secondary); cursor: not-allowed; }
.composer:has(textarea:disabled) { background: var(--color-bg-disabled); cursor: not-allowed; }
.send-button { flex: 0 0 auto; min-width: 72px; height: 36px; margin-bottom: 4px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid var(--color-primary); border-radius: 6px; background: var(--color-primary); color: #fff; font: inherit; cursor: pointer; transition: opacity var(--transition-fast), background var(--transition-fast); }
.send-button:hover:not(:disabled) { background: #536b57; }
.send-button:disabled { opacity: 0.5; cursor: not-allowed; }
.send-button.stop { border-color: var(--color-danger-soft); background: var(--color-danger-soft); }
.stop-icon { width: 9px; height: 9px; background: #fff; }
@media (max-width: 600px) { .composer { gap: 6px; padding: 6px; } .send-button { min-width: 62px; } }
</style>
