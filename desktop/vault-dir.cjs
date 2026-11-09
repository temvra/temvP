// Bóveda en una carpeta real del equipo: listar, leer, escribir, mover a la papelera y vigilar los archivos de la carpeta elegida.
// No usa Electron (lo llama main.cjs por IPC), así que se puede probar con Node. Las rutas que llegan de la página son relativas,
// con «/», y nunca pueden salir de la carpeta. Lo que empieza por punto (.obsidian, .trash, .git…) no se toca ni se lista.
const fs=require('node:fs'),fsp=fs.promises,path=require('node:path');
const hidden=n=>n.startsWith('.')||n==='node_modules';
function inside(root,rel){const r=path.resolve(String(root)),f=path.resolve(r,String(rel||''));
  if(f!==r&&!f.startsWith(r+path.sep))throw new Error('Ruta fuera de la carpeta de la bóveda');return f}
const relOf=(root,f)=>path.relative(path.resolve(root),f).split(path.sep).join('/');
const st=s=>({size:s.size,mtime:Math.round(s.mtimeMs)});
async function list(root){const R=path.resolve(String(root)),out=[];
  const s=await fsp.stat(R);if(!s.isDirectory())throw new Error('No es una carpeta');
  async function walk(dir,depth){if(depth>32)return;let es;try{es=await fsp.readdir(dir,{withFileTypes:true})}catch(e){if(dir===R)throw e;return}
    for(const e of es){if(hidden(e.name))continue;const f=path.join(dir,e.name);
      if(e.isDirectory()){out.push({path:relOf(R,f),dir:true});await walk(f,depth+1)}
      else if(e.isFile()){try{out.push(Object.assign({path:relOf(R,f)},st(await fsp.stat(f))))}catch(err){}}}}
  await walk(R,0);return out}
async function read(root,rel,bin){const b=await fsp.readFile(inside(root,rel));return bin?b.toString('base64'):b.toString('utf8')}
// Se escribe en un archivo oculto y se renombra: quien lea la carpeta (Obsidian, otra sincronización) nunca ve una nota a medias
async function write(root,rel,data,bin){const f=inside(root,rel),dir=path.dirname(f);await fsp.mkdir(dir,{recursive:true});
  const buf=bin?Buffer.from(String(data||''),'base64'):Buffer.from(String(data==null?'':data),'utf8'),tmp=path.join(dir,'.'+path.basename(f)+'.temvra-tmp');
  try{await fsp.writeFile(tmp,buf);await fsp.rename(tmp,f)}catch(e){try{await fsp.unlink(tmp)}catch(err){}await fsp.writeFile(f,buf)}
  return st(await fsp.stat(f))}
// Borrar = mover a .trash dentro de la carpeta, como hace Obsidian: se puede recuperar
async function trash(root,rel){const f=inside(root,rel);if(!fs.existsSync(f))return false;let t=inside(root,'.trash/'+rel);
  await fsp.mkdir(path.dirname(t),{recursive:true});if(fs.existsSync(t)){const p=path.parse(t);t=path.join(p.dir,p.name+' '+Date.now()+p.ext)}
  try{await fsp.rename(f,t)}catch(e){await fsp.copyFile(f,t);await fsp.unlink(f)}return true}
async function move(root,from,to){const a=inside(root,from),b=inside(root,to);await fsp.mkdir(path.dirname(b),{recursive:true});
  if(fs.existsSync(b))throw new Error('Ya existe '+to);try{await fsp.rename(a,b)}catch(e){await fsp.copyFile(a,b);await fsp.unlink(a)}return st(await fsp.stat(b))}
async function mkdir(root,rel){await fsp.mkdir(inside(root,rel),{recursive:true});return true}
// Avisa (una vez por ráfaga) cuando otra app cambia algo en la carpeta; lo oculto no cuenta, así que lo que escribe Temvra no se repite
function watch(root,cb){let t=null,w=null;
  try{w=fs.watch(path.resolve(String(root)),{recursive:true},(ev,name)=>{const n=String(name||'');if(n&&n.split(/[\\/]/).some(hidden))return;clearTimeout(t);t=setTimeout(cb,500)})}catch(e){return ()=>{}}
  w.on('error',()=>{});return ()=>{clearTimeout(t);try{w.close()}catch(e){}}}
module.exports={list,read,write,trash,move,mkdir,watch,inside};
