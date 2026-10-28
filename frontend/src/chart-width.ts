import {onBeforeUnmount, ref, watch} from 'vue';

// Match the SVG viewBox to its container so a fixed-height chart does not get
// centered inside a wider viewport with unused space on both sides.
export function useChartWidth() {
  const chartContainer = ref<HTMLElement | null>(null);
  const containerWidth = ref(600);
  let observer: ResizeObserver | undefined;
  watch(chartContainer, element => {
    observer?.disconnect();
    if (!element) return;
    const measure = () => { containerWidth.value = element.clientWidth; };
    measure();
    observer = new ResizeObserver(measure);
    observer.observe(element);
  }, {flush:'post'});
  onBeforeUnmount(() => observer?.disconnect());
  return {chartContainer, containerWidth};
}
