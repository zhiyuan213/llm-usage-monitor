// Produce readable initial HTML for search engines and visitors without JavaScript.
// The same tutorial content and executable examples drive Vue and these static pages.
import { createServer } from 'vite';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const p = value => `<p>${escape(value)}</p>`;
const code = value => `<div class="code-box"><pre>${escape(value)}</pre></div>`;
const link = (url, text) => `<a href="${escape(url)}">${escape(text)}</a>`;
try {
  const { tutorials, environmentExample, receipt, queryExample } = await server.ssrLoadModule('/src/tutorial-content.ts');
  const { demoCalls } = await server.ssrLoadModule('/src/demo-data.ts');
  const shell = await readFile('dist/index.html', 'utf8');
  const nav = `<header class="public-header"><a class="brand" href="/">MeterTrail</a><nav>${[['/','Product'],['/pricing','Pricing'],['/docs','Documentation'],['/demo','Demo'],['/tutorials','Tutorials']].map(([url,text])=>link(url,text)).join(' ')}</nav></header>`;
  const cards = tutorials.map(t => `<article class="panel"><h2>${link('/tutorials/'+t.slug,t.en)}</h2>${p(t.summary[0])}</article>`).join('');
  const pages = [
    {path:'/', title:'LLM call tracking and cost monitoring', description:'See which AI requests are slow, where tokens are spent and how model costs add up. Explore the demo or start with Node.js, Python or REST.', html:`<section class="page-intro"><h1>Track AI calls. Understand the cost.</h1>${p('See which requests are slow, where tokens are spent, and how model costs add up. Send data from your backend with Node.js, Python, Java or REST.')}${link('/register','Start building for free')} · ${link('/demo','Explore the demo')}${p('50,000 units / month. No credit card required.')}</section><h2>Choose your integration guide</h2><div class="tutorial-grid">${cards}</div>`},
    {path:'/tutorials',title:'Integration tutorials',description:'Step-by-step Node.js, Python and REST tutorials with runnable code and troubleshooting.',html:`<h1>Integration tutorials</h1>${p('Start with one request. Each guide includes runnable code, the expected response and troubleshooting steps.')}<div class="tutorial-grid">${cards}</div>`},
    {path:'/demo',title:'Interactive AI request monitoring demo',description:'Explore fictional AI calls, slow requests, model usage and estimated costs without signing up.',html:`<h1>Find the request behind the cost</h1>${p('All requests, model names and costs below are fictional. This demo runs locally in your browser, never calls a model and does not change your account or quota.')}${p('Enable JavaScript to filter requests and explore their steps.')}<div class="tutorial-grid">${demoCalls.map(c=>`<article class="panel"><h2>${escape(c.name)}</h2>${p(`${c.model} · ${c.status} · ${c.duration/1000} seconds`)}${p(`${c.input} input tokens · ${c.output} output tokens · ${c.cost===null?'Unknown cost':'Estimated cost $'+c.cost.toFixed(4)}`)}</article>`).join('')}</div>${link('/tutorials','Choose an integration tutorial')}`},
    ...tutorials.map(t=>({path:'/tutorials/'+t.slug,title:t.en,description:t.summary[0],html:`<article class="tutorial-article">${link('/tutorials','← All tutorials')}<h1>${escape(t.en)}</h1>${p(t.summary[0])}<h2>Before you start</h2>${p(t.requirement[0])}${p('Keep project API keys out of browser code, screenshots and source control. No model API key is required for this test.')}<h2>1. Create a project and API key</h2>${p('Create a free account, verify your email, then create or select a project in Project management. Open API keys for that project, create a key and complete email verification. Save the key when it is displayed.')}${link('/register','Create a free account')}<h2>2. Set your environment</h2>${code(environmentExample)}<h2>3. Send a sample trace</h2>${p('Save as '+t.filename+' and run from the same terminal where you set the environment variables.')}${code(t.code)}${code(t.command)}${p(t.usage[0])}<h2>4. Check the result</h2>${p('A successful upload returns HTTP 200. pending is a receipt for asynchronous processing, not a confirmation that data is already queryable.')}${code(receipt)}${p('Optionally query the returned batchId using the same project key:')}${code(queryExample)}<h2>5. Find the trace in your console</h2>${p('Select the project associated with your key and open Traces. Refresh after processing and find Example request. Model cost can be Unknown because this minimal example does not report model usage or prices.')}<h2>Troubleshooting</h2>${p('401: check the key and project. 400: check required fields and timestamps. For quota or subscription errors, check Billing. After a network timeout retry the same event IDs and content rather than creating new objects.')}${link('/docs#fields','Full ingestion field reference')}${p('Other tutorials:')}${tutorials.filter(other=>other.slug!==t.slug).map(other=>link('/tutorials/'+other.slug,other.en)).join(' · ')}</article>`})),
  ];
  for (const page of pages) {
    const title = `${page.title} | MeterTrail`;
    const output = shell.replace(/<title>.*?<\/title>/,`<title>${escape(title)}</title>`)
      .replace(/<meta name="description" content="[^"]*">/,`<meta name="description" content="${escape(page.description)}">`)
      .replace('</head>',`<link rel="canonical" href="https://metertrail.com${page.path}"></head>`)
      .replace('<div id="app"></div>',`<div id="app"><div class="public public-v2">${nav}<main class="public-content">${page.html}</main><footer>${link('/privacy','Privacy')} · ${link('/terms','Terms')} · ${link('/refunds','Refunds')}</footer></div></div>`);
    const target = page.path==='/' ? 'dist/home.html' : 'dist'+page.path+'.html';
    await mkdir(dirname(target),{recursive:true}); await writeFile(target,output);
  }
  console.log(`Generated ${pages.length} public pages with readable initial HTML.`);
} finally { await server.close(); }
