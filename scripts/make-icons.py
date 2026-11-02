# Genera todos los iconos de Android a partir de resources/icon-source.png (o de otra imagen cuadrada).
# Uso: python3 scripts/make-icons.py [imagen.png] [1 = versión personal con insignia P]
import sys,os
here=os.path.dirname(os.path.abspath(__file__));repo=os.path.dirname(here)
sys.argv=[sys.argv[0],sys.argv[1] if len(sys.argv)>1 else os.path.join(repo,'resources','icon-source.png'),repo,sys.argv[2] if len(sys.argv)>2 else '1']
# Genera los iconos de Android de Temvra a partir de una imagen cuadrada (esquinas negras = fuera del icono)
import sys,os,glob,numpy as np
from PIL import Image,ImageDraw,ImageFilter,ImageFont
src,repo,personal=sys.argv[1],sys.argv[2],sys.argv[3]=="1"
R=f'{repo}/android/app/src/main/res'
im=Image.open(src).convert('RGB');N=im.size[0];A=np.asarray(im).astype(np.float32)
lum=A.mean(axis=2)
# 1) máscara del cuadrado redondeado: lo que no es negro de las esquinas
m=(lum>14).astype(np.uint8)*255;mask=Image.fromarray(m).filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(1.2))
rounded=im.convert('RGBA');rounded.putalpha(mask)
# 2) versión a sangre (sin esquinas): rellena fuera de la máscara con el color difuminado de alrededor
def fill_out(img,msk,rad=60):
    a=np.asarray(img).astype(np.float32);w=np.asarray(msk).astype(np.float32)[...,None]/255
    num=np.asarray(Image.fromarray((a*w).clip(0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(rad))).astype(np.float32)
    den=np.asarray(Image.fromarray((w[...,0]*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(rad))).astype(np.float32)[...,None]/255
    fill=num/np.maximum(den,1e-3);out=a*w+fill*(1-w);return Image.fromarray(out.clip(0,255).astype(np.uint8))
full=im
for r in (40,90,180):full=fill_out(full,mask if r==40 else Image.fromarray(np.full((N,N),255,np.uint8)) ,r) if r==40 else full
full=fill_out(im,mask,40);full=fill_out(full,Image.fromarray(((lum>14)|(np.asarray(full).mean(axis=2)>14)).astype(np.uint8)*255),120)
# 3) lienzo ampliado para el icono adaptativo (el símbolo queda dentro de la zona segura)
S=int(N/0.80);pad=(S-N)//2
arr=np.pad(np.asarray(full),((pad,S-N-pad),(pad,S-N-pad),(0,0)),mode='edge')
big=Image.fromarray(arr);inner=Image.new('L',(S,S),0);ImageDraw.Draw(inner).rectangle([pad,pad,pad+N,pad+N],fill=255)
big=fill_out(big,inner.filter(ImageFilter.MinFilter(9)),150)
fm=Image.new('L',(N,N),0);ImageDraw.Draw(fm).rectangle([30,30,N-30,N-30],fill=255);big.paste(full,(pad,pad),fm.filter(ImageFilter.GaussianBlur(25)))
# marca de la versión personal: insignia «P» ámbar abajo a la derecha
def badge(img,cx,cy,r):
    img=img.convert('RGBA');d=ImageDraw.Draw(img);d.ellipse([cx-r-r*.14,cy-r-r*.14,cx+r+r*.14,cy+r+r*.14],fill=(255,255,255,255));d.ellipse([cx-r,cy-r,cx+r,cy+r],fill=(245,158,11,255))
    try:f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',int(r*1.3))
    except Exception:f=ImageFont.load_default()
    d.text((cx,cy),'P',font=f,fill=(255,255,255,255),anchor='mm');return img
if personal:
    rounded=badge(rounded,N*.80,N*.80,N*.11)
    big=badge(big,S/2+S*.17,S/2+S*.17,S*.068);full=badge(full,N*.80,N*.80,N*.11).convert('RGB')
# 4) silueta blanca para la notificación (solo el símbolo claro)
sat=A.max(axis=2)-A.min(axis=2);yy,xx=np.mgrid[0:N,0:N];rad=np.hypot(xx-N/2,yy-N/2)<N*.42
wht=np.clip((lum-150)/60,0,1)*np.clip((140-sat)/60,0,1)*rad
wa=Image.fromarray((wht*255).astype(np.uint8));bb=wa.point(lambda v:255 if v>80 else 0).getbbox()
x0,y0,x1,y1=bb;side=max(x1-x0,y1-y0);cx,cy=(x0+x1)//2,(y0+y1)//2;h=side//2+8
wa=wa.crop((cx-h,cy-h,cx+h,cy+h));stat=Image.new('RGBA',wa.size,(255,255,255,0));stat.putalpha(wa)
# 5) escribir todos los tamaños
dens={'mdpi':1,'hdpi':1.5,'xhdpi':2,'xxhdpi':3,'xxxhdpi':4}
circ=lambda s:(lambda mk:(ImageDraw.Draw(mk).ellipse([0,0,s-1,s-1],fill=255),mk)[1])(Image.new('L',(s,s),0))
for k,f in dens.items():
    p=f'{R}/mipmap-{k}';os.makedirs(p,exist_ok=True);s=int(48*f)
    rounded.resize((s,s),Image.LANCZOS).save(f'{p}/ic_launcher.png')
    v=int(S*72/108);o=(S-v)//2;rd=big.crop((o,o,o+v,o+v)).resize((s,s),Image.LANCZOS).convert('RGBA');rd.putalpha(circ(s));rd.save(f'{p}/ic_launcher_round.png')
    big.resize((int(108*f),int(108*f)),Image.LANCZOS).save(f'{p}/ic_launcher_foreground.png')
    q=f'{R}/drawable-{k}';os.makedirs(q,exist_ok=True);t=int(24*f);st=Image.new('RGBA',(t,t),(0,0,0,0));inn=int(t*.92);st.paste(stat.resize((inn,inn),Image.LANCZOS),((t-inn)//2,(t-inn)//2));st.save(f'{q}/ic_stat_temvra.png')
c=np.asarray(full).reshape(-1,3).mean(axis=0).astype(int)
open(f'{R}/values/ic_launcher_background.xml','w').write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n'%tuple(c))
for fpath in glob.glob(f'{R}/drawable*/splash.png'):
    w,hh=Image.open(fpath).size;sp=Image.new('RGBA',(w,hh),(17,19,26,255));s=int(min(w,hh)*.32);lg=rounded.resize((s,s),Image.LANCZOS);sp.paste(lg,((w-s)//2,(hh-s)//2),lg);sp.convert('RGB').save(fpath)
full.resize((512,512),Image.LANCZOS).save(f'{repo}/resources/icon-512.png')
rounded.save(f'{repo}/resources/icon-source.png')
print('iconos listos',repo,'fondo adaptativo #%02X%02X%02X'%tuple(c))
