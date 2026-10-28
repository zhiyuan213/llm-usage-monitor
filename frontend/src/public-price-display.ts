export function publicPriceRate(value:unknown,locale:string){
 return new Intl.NumberFormat(locale,{maximumFractionDigits:12}).format(Number(value));
}
