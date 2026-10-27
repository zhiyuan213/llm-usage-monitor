import {ref,watch} from 'vue';
export const pageSizes=[10,25,50,100];
export function readPageSize(storage:Pick<Storage,'getItem'>):number {
  try {const value=Number(storage.getItem('metertrail.pageSize'));return pageSizes.includes(value)?value:25;}catch{return 25;}
}
export function usePageSize(){
  const size=ref(readPageSize(localStorage));
  watch(size,value=>{try{if(pageSizes.includes(value))localStorage.setItem('metertrail.pageSize',String(value));}catch{}});
  return size;
}
