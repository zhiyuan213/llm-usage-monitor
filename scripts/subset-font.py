"""Regenerate the self-hosted CJK UI subset. Requires fonttools[woff]."""
from pathlib import Path
from fontTools import subset

root = Path(__file__).resolve().parent.parent
source = root / 'frontend/public/fonts/NotoSansSC-Regular.woff2'
target = source.with_name('NotoSansSC-ui.woff2')
text = ''.join(p.read_text(encoding='utf-8') for p in (root / 'frontend/src').rglob('*')
               if p.suffix in {'.vue', '.ts', '.css'})
characters = {c for c in text if ord(c) >= 0x3000}
options = subset.Options()
options.flavor = 'woff2'
font = subset.load_font(str(source), options)
subsetter = subset.Subsetter(options=options)
subsetter.populate(text=''.join(sorted(characters)))
subsetter.subset(font)
subset.save_font(font, str(target), options)
print(f'{len(characters)} characters; {source.stat().st_size} -> {target.stat().st_size} bytes')
