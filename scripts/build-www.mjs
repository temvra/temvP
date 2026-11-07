// Genera www/ a partir de src/app.html: fuentes y librerías locales (la app funciona sin conexión) + puente nativo.
import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),nm=p=>path.join(root,'node_modules',p),www=path.join(root,'www');
fs.rmSync(www,{recursive:true,force:true});fs.mkdirSync(path.join(www,'libs'),{recursive:true});fs.mkdirSync(path.join(www,'fonts'),{recursive:true});
const cp=(a,b)=>fs.copyFileSync(a,path.join(www,b));
cp(nm('jspdf/dist/jspdf.umd.min.js'),'libs/jspdf.umd.min.js');cp(nm('jszip/dist/jszip.min.js'),'libs/jszip.min.js');
cp(nm('pdfjs-dist/build/pdf.min.js'),'libs/pdf.min.js');cp(nm('pdfjs-dist/build/pdf.worker.min.js'),'libs/pdf.worker.min.js');
// Fuentes: solo los alfabetos latinos (español, inglés, francés, alemán, italiano, catalán, portugués)
let css='';
for(const [pkg,file,fam] of [['@fontsource-variable/roboto-flex','full.css','Roboto Flex'],['@fontsource-variable/literata','opsz.css','Literata'],['@fontsource-variable/literata','opsz-italic.css','Literata']]){
  const src=fs.readFileSync(nm(pkg+'/'+file),'utf8');
  for(const block of src.split(/(?=\/\*)/)){if(!/latin/.test(block.split('\n')[0]))continue;
    const m=block.match(/url\(\.\/files\/([^)]+)\)/);if(!m)continue;cp(nm(pkg+'/files/'+m[1]),'fonts/'+m[1]);
    css+=block.replace(/font-family: '[^']+'/,`font-family: '${fam}'`).replace(/url\(\.\/files\//g,'url(./')+'\n'}}
fs.writeFileSync(path.join(www,'fonts','fonts.css'),css);
let html=fs.readFileSync(path.join(root,'src','app.html'),'utf8');
html=html.replace(/<link[^>]+fonts\.googleapis\.com[^>]*>/g,'<link rel="stylesheet" href="fonts/fonts.css">');
html=html.replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/jspdf\/[^'"]+\/jspdf\.umd\.min\.js/g,'libs/jspdf.umd.min.js')
  .replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/jszip\/[^'"]+\/jszip\.min\.js/g,'libs/jszip.min.js')
  .replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/pdf\.js\/[^'"]+\/pdf\.worker\.min\.js/g,'libs/pdf.worker.min.js')
  .replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/pdf\.js\/[^'"]+\/pdf\.min\.js/g,'libs/pdf.min.js');
const head='<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#000000"><title>Temvra</title>';
if(!/^<!doctype/i.test(html.trim()))html=`<!doctype html><html lang="es"><head>${head}</head><body>${html}<script src="native.js"></script></body></html>`;
else html=html.replace('</body>','<script src="native.js"></script></body>');
fs.writeFileSync(path.join(www,'index.html'),html);cp(path.join(root,'src','native.js'),'native.js');cp(path.join(root,'src','widget.html'),'widget.html');
const left=html.match(/https:\/\/cdnjs[^'"]+/g);console.log('www listo ·',(fs.statSync(path.join(www,'index.html')).size/1024|0)+' KB',left?'· aún externos: '+left.join(', '):'· todo local');
