#!/usr/bin/env python3
"""Makes a test file with the same layout as a real Pixel photo but nothing personal in it.

Keeps every metadata segment (EXIF, C2PA, ICC, XMP, extended XMP, MPF) and the gain map
headers, but swaps in a blank picture, a blank gain map, the given blank video, a made-up
GPS position, a blank EXIF thumbnail and a random HDR+ makernote of the same size. The
MPF index and XMP Item:Length values are rewritten to match.

Usage: make_lookalike_jpeg.py original.jpg out.jpg blank.mp4
"""
import sys,struct,re,io,random
from PIL import Image
random.seed(7)
src,dst=sys.argv[1],sys.argv[2]; video=sys.argv[3] if len(sys.argv)>3 else None
b=open(src,'rb').read()
segs=[]; i=2
while True:
    m=b[i+1]
    if not (0xE0<=m<=0xEF): break
    L=struct.unpack('>H',b[i+2:i+4])[0]; segs.append(bytearray(b[i:i+2+L])); i+=2+L
xmp=[s for s in segs if s[4:33]==b'http://ns.adobe.com/xap/1.0/\0'][0].decode('latin1')
lens=[int(x) for x in re.findall(r'Item:Length="(\d+)"',xmp)]
sois=[m.start() for m in re.finditer(rb'\xff\xd8\xff',b)]
gm_start=sois[2]; gm=b[gm_start:gm_start+lens[0]]
has_video='MotionPhoto' in xmp

def blank(w,h,mode,q=90):
    o=io.BytesIO(); Image.new(mode,(w,h),128 if mode=='L' else (128,128,128)).save(o,'JPEG',quality=q); j=o.getvalue()
    k=2
    while j[k+1] in range(0xE0,0xF0): k+=2+struct.unpack('>H',j[k+2:k+4])[0]
    return j,j[k:]  # whole, body (tables..EOI)
_,prim_body=blank(3840,2160,'RGB')
# gain map: keep its own APP segments, blank body
k=2; gseg=b''
while gm[k+1] in range(0xE0,0xF0):
    L=struct.unpack('>H',gm[k+2:k+4])[0]; gseg+=gm[k:k+2+L]; k+=2+L
_,gbody=blank(1000,563,'L')
gm_new=b'\xff\xd8'+gseg+gbody
vid=open(video,'rb').read() if has_video else b''

# EXIF: fake GPS, blank thumbnail, same length
ex=[s for s in segs if s[4:10]==b'Exif\0\0'][0]
t=10; bo='<' if ex[t:t+2]==b'II' else '>'
u16=lambda o:struct.unpack(bo+'H',ex[o:o+2])[0]; u32=lambda o:struct.unpack(bo+'I',ex[o:o+4])[0]
def ents(ifd): return [t+ifd+2+12*n for n in range(u16(t+ifd))]
ifd0=u32(t+4)
for e in ents(ifd0):
    if u16(e)==0x8825:
        for g in ents(u32(e+8)):
            tag=u16(g); v=u32(g+8)
            vals={2:(17,44,24.0),4:(168,18,54.0),6:(10.0,),17:(90.0,)}.get(tag)
            if vals:
                for n,x in enumerate(vals): struct.pack_into(bo+'II',ex,t+v+8*n,int(x*100),100)
ifd1=u32(t+ifd0+2+12*u16(t+ifd0))
if ifd1:
    off=ln=None
    for e in ents(ifd1):
        if u16(e)==0x201: off=u32(e+8)
        if u16(e)==0x202: ln=u32(e+8)
    th,_=blank(240,135,'RGB',50); assert len(th)<=ln
    ex[t+off:t+off+ln]=th+b'\0'*(ln-len(th))
# Extended XMP: replace the makernote blob with random base64 of the same length
exts=[s for s in segs if s[4:39]==b'http://ns.adobe.com/xmp/extension/\0']
H=4+35+32+8
cat=bytearray(b''.join(s[H:] for s in exts))
m=re.search(rb'HdrPlusMakernote="([^"]*)"',cat)
A=b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
cat[m.start(1):m.end(1)]=bytes(random.choice(A) for _ in range(m.end(1)-m.start(1)-2))+b'=='
p=0
for s in exts:
    n=len(s)-H; s[H:]=cat[p:p+n]; p+=n

def build():
    x=xmp
    it=iter([len(gm_new)]+([len(vid)] if has_video else []))
    x=re.sub(r'Item:Length="\d+"',lambda _: 'Item:Length="%d"'%next(it),x)
    out=[]
    for s in segs:
        if s[4:33]==b'http://ns.adobe.com/xap/1.0/\0':
            payload=x.encode('latin1')[4:]
            s=bytearray(b'\xff\xe1'+struct.pack('>H',len(payload)+2)+payload)
        out.append(s)
    return out
segs2=build()
head=b'\xff\xd8'+b''.join(segs2)
prim=head+prim_body
# MPF: patch sizes and second image offset
mi=head.find(b'MPF\0'); tiff=mi+4
mb='<' if head[tiff:tiff+2]==b'II' else '>'
prim=bytearray(prim)
def r16(o): return struct.unpack(mb+'H',prim[o:o+2])[0]
def r32(o): return struct.unpack(mb+'I',prim[o:o+4])[0]
ifd=r32(tiff+4)
for n in range(r16(tiff+ifd)):
    e=tiff+ifd+2+12*n
    if r16(e)==0xB002:
        ent=tiff+r32(e+8)
        struct.pack_into(mb+'II',prim,ent+4,len(prim),0)
        struct.pack_into(mb+'II',prim,ent+16+4,len(gm_new),len(prim)-tiff)
out=bytes(prim)+gm_new+vid
open(dst,'wb').write(out)
print(dst,len(out),'primary',len(prim),'gainmap',len(gm_new),'video',len(vid))
