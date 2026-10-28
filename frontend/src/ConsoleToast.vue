<script setup lang="ts">
import { CheckCircle2, AlertCircle } from 'lucide-vue-next';
import { toast } from './toast';
</script>

<template>
  <Teleport to="body">
    <div v-if="toast" class="console-toast" :class="`console-toast-${toast.kind}`"
      :role="toast.kind === 'error' ? 'alert' : 'status'" aria-atomic="true">
      <AlertCircle v-if="toast.kind === 'error'" :size="22" aria-hidden="true" />
      <CheckCircle2 v-else :size="22" aria-hidden="true" />
      <span>{{ toast.message }}</span>
    </div>
  </Teleport>
</template>
