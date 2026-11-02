# Dibuja el icono de Temvra y genera todos los tamaños de Android.
import math,os
from PIL import Image,ImageDraw,ImageFilter
R='android/app/src/main/res'
def logo(size,fg=(255,255,255,255),bg=None,scale=1.0,rounded=False):
    S=size*4;im=Image.new('RGBA',(S,S),(0,0,0,0));d=ImageDraw.Draw(im)
    if bg:
        top,bot=(123,120,216),(70,62,180)
        grad=Image.new('RGBA',(S,S));gd=ImageDraw.Draw(grad)
        for y in range(S):t=y/S;gd.line([(0,y),(S,y)],fill=tuple(int(top[i]*(1-t)+bot[i]*t) for i in range(3))+(255,))
        mask=Image.new('L',(S,S),0);ImageDraw.Draw(mask).rounded_rectangle([0,0,S,S],radius=int(S*(0.5 if rounded else 0.23)),fill=255)
        im.paste(grad,(0,0),mask)
    c=S/2;r=S*0.30*scale;w=int(S*0.075*scale)
    # aro con flecha (vuelta)
    d.arc([c-r,c-r,c+r,c+r],start=-60,end=250,fill=fg,width=w)
    a=math.radians(-60);ex,ey=c+r*math.cos(a),c+r*math.sin(a);L=S*0.12*scale
    dx,dy=math.sin(a),-math.cos(a);nx,ny=math.cos(a),math.sin(a)
    d.polygon([(ex+dx*L*1.1,ey+dy*L*1.1),(ex+nx*L*0.85-dx*L*0.2,ey+ny*L*0.85-dy*L*0.2),(ex-nx*L*0.85-dx*L*0.2,ey-ny*L*0.85-dy*L*0.2)],fill=fg)
    # reloj de arena dentro
    hw,hh=S*0.10*scale,S*0.14*scale;lw=max(2,int(S*0.035*scale))
    d.line([(c-hw,c-hh),(c+hw,c-hh)],fill=fg,width=lw);d.line([(c-hw,c+hh),(c+hw,c+hh)],fill=fg,width=lw)
    d.line([(c-hw*0.8,c-hh),(c+hw*0.8,c+hh)],fill=fg,width=lw);d.line([(c+hw*0.8,c-hh),(c-hw*0.8,c+hh)],fill=fg,width=lw)
    d.polygon([(c-hw*0.55,c+hh*0.95),(c+hw*0.55,c+hh*0.95),(c,c+hh*0.35)],fill=fg)
    return im.resize((size,size),Image.LANCZOS)
dens={'mdpi':1,'hdpi':1.5,'xhdpi':2,'xxhdpi':3,'xxxhdpi':4}
for k,f in dens.items():
    p=f'{R}/mipmap-{k}';os.makedirs(p,exist_ok=True)
    logo(int(48*f),bg=True).save(f'{p}/ic_launcher.png');logo(int(48*f),bg=True,rounded=True).save(f'{p}/ic_launcher_round.png')
    logo(int(108*f),scale=0.62).save(f'{p}/ic_launcher_foreground.png')
    q=f'{R}/drawable-{k}';os.makedirs(q,exist_ok=True)
    logo(int(24*f),scale=1.15).save(f'{q}/ic_stat_temvra.png')
open(f'{R}/values/ic_launcher_background.xml','w').write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#5D57C6</color>\n</resources>\n')
# pantallas de carga
def splash(w,h):
    im=Image.new('RGBA',(w,h),(17,19,26,255));s=int(min(w,h)*0.32);lg=logo(s,bg=True);im.paste(lg,((w-s)//2,(h-s)//2),lg);return im.convert('RGB')
import glob
for f in glob.glob(f'{R}/drawable*/splash.png'):
    w,h=Image.open(f).size;splash(w,h).save(f)
logo(512,bg=True).save('resources/icon-512.png')
print('iconos listos')
