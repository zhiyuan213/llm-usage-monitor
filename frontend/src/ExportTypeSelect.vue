<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount} from 'vue';
defineProps<{modelValue:string[];label:string}>();
const emit=defineEmits<{ 'update:modelValue':[value:string[]] }>();
const root=ref<HTMLElement>();
const open=ref(false);
function outside(event:PointerEvent){if(!root.value?.contains(event.target as Node))open.value=false;}
function toggle(values:string[],type:string){
  if(values.includes(type)){if(values.length>1)emit('update:modelValue',values.filter(v=>v!==type));}
  else emit('update:modelValue',[...values,type]);
}
onMounted(()=>document.addEventListener('pointerdown',outside));
onBeforeUnmount(()=>document.removeEventListener('pointerdown',outside));
</script>

<template>
  <div ref="root" class="export-type-select" @keydown.esc="open=false" @focusout="!root?.contains($event.relatedTarget as Node)&& (open=false)">
    <button type="button" class="btn export-type-trigger" :aria-expanded="open" @click="open=!open">{{label}} <span class="export-type-count">{{modelValue.length}}/2</span><span aria-hidden="true">⌄</span></button>
    <div v-if="open" class="export-type-menu" :aria-label="label">
      <label v-for="type in ['trace','observation']" :key="type" class="export-type-option"><input type="checkbox" :checked="modelValue.includes(type)" :disabled="modelValue.length===1&&modelValue.includes(type)" @change="toggle(modelValue,type)" />{{type==='trace'?'Trace':'Observation'}}</label>
    </div>
  </div>
</template>
