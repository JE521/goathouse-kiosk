# Generates the 1-page battery-eliminator printout (US Letter). Output: eliminator.svg/.png/.jpg
import html, sys
W,H=816,1056
o=[]
def t(x,y,s,size=11,w='normal',anchor='start',fam='DejaVu Sans',st=''):
    o.append(f'<text x="{x}" y="{y}" font-family="{fam}" font-size="{size}" font-weight="{w}" text-anchor="{anchor}" {st}>{html.escape(s)}</text>')
def l(x1,y1,x2,y2,sw=1.6): o.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#000" stroke-width="{sw}" stroke-linecap="round"/>')
def r(x,y,w,h,sw=1.6,rx=0,fill='none',dash=''): o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="#000" stroke-width="{sw}" {dash}/>')
def p(d,sw=1.6,fill='none'): o.append(f'<path d="{d}" fill="{fill}" stroke="#000" stroke-width="{sw}" stroke-linejoin="round"/>')
def dot(x,y): o.append(f'<circle cx="{x}" cy="{y}" r="3.2" fill="#000"/>')

# ---------- header
t(40,52,'Goathouse Battery Eliminator',24,'bold')
t(40,74,'USB-C powered replacement for a dead or removed tablet battery  ·  rev A  ·  2026-11-19',11.5,st='fill="#333"')
l(40,86,776,86,1)

# ---------- schematic
TOP,BOT=175,295   # + rail, GND rail
# J1 + U1 (often one board)
r(40,140,92,190,1,6,dash='stroke-dasharray="4 3"'); t(86,135,'often one board',9,anchor='middle',st='fill="#444"')
r(48,148,76,174); t(86,206,'J1',12,'bold','middle'); t(86,222,'USB-C',10,anchor='middle'); t(86,236,'socket',10,anchor='middle')
t(118,TOP+4,'VBUS',8,anchor='end'); t(118,BOT+4,'GND',8,anchor='end')
l(124,TOP,152,TOP); l(124,BOT,152,BOT)
r(152,148,96,174); t(200,206,'U1',12,'bold','middle'); t(200,222,'USB-C PD',10,anchor='middle'); t(200,236,'trigger',10,anchor='middle'); t(200,256,'set 9 V',10,'bold','middle')
t(156,TOP+4,'IN+',8); t(156,BOT+4,'IN−',8); t(244,TOP+4,'OUT+',8,anchor='end'); t(244,BOT+4,'OUT−',8,anchor='end')
# F1 fuse
l(248,TOP,282,TOP); r(282,TOP-8,48,16); l(282,TOP,330,TOP,1.2); l(330,TOP,364,TOP)
t(306,TOP-16,'F1',11,'bold','middle'); t(306,TOP+26,'2 A PTC',9.5,anchor='middle')
t(306,TOP-34,'+9 V',10,anchor='middle',st='fill="#333"')
l(248,BOT,364,BOT)
# U2 buck
r(364,148,104,174); t(416,206,'U2',12,'bold','middle'); t(416,222,'buck module',10,anchor='middle'); t(416,236,'≥ 3 A',10,anchor='middle'); t(416,256,'set 4.00 V',10,'bold','middle')
t(368,TOP+4,'IN+',8); t(368,BOT+4,'IN−',8); t(464,TOP+4,'OUT+',8,anchor='end'); t(464,BOT+4,'OUT−',8,anchor='end')
# output rails
l(468,TOP,700,TOP); l(468,BOT,700,BOT)
t(560,TOP-12,'+4.00 V',11,'bold','middle')
# C1 polarized cap at x=520
cx=520; dot(cx,TOP); dot(cx,BOT)
l(cx,TOP,cx,226); l(cx-16,226,cx+16,226,2.2); p(f'M{cx-16},240 Q{cx},232 {cx+16},240',2.2); l(cx,236,cx,BOT)
t(cx-24,224,'+',13,'bold','middle')
t(cx+22,224,'C1',11,'bold'); t(cx+22,238,'2200 µF',9.5); t(cx+22,251,'10 V',9.5)
# D1 zener at x=610, cathode up
zx=610; dot(zx,TOP); dot(zx,BOT)
l(zx,TOP,zx,222); p(f'M{zx-12},246 L{zx+12},246 L{zx},222 Z',1.6)
p(f'M{zx-16},218 L{zx-12},222 L{zx+12},222 L{zx+16},226',1.8); l(zx,246,zx,BOT)
t(zx+22,226,'D1',11,'bold'); t(zx+22,240,'5.1 V 5 W',9.5); t(zx+22,253,'zener',9.5)
# ground symbol
gx=565; l(gx,BOT,gx,BOT+14); l(gx-12,BOT+14,gx+12,BOT+14); l(gx-8,BOT+19,gx+8,BOT+19); l(gx-4,BOT+24,gx+4,BOT+24)
# J2 battery connector + R1
r(700,140,76,190); t(738,196,'J2',12,'bold','middle'); t(738,212,'tablet',10,anchor='middle'); t(738,226,'battery',10,anchor='middle'); t(738,240,'terminals',10,anchor='middle')
t(706,TOP+4,'+',11,'bold'); t(706,BOT+4,'−',12,'bold'); TY=262; t(706,TY+4,'T',10,'bold')
rx_=672; l(700,TY,rx_,TY); dot(rx_,BOT)
# vertical resistor from TY to BOT at rx_
zz=f'M{rx_},{TY} L{rx_},{TY+3}'; y=TY+3
for i in range(6): zz+=f' L{rx_+(7 if i%2==0 else -7)},{y+3}'; y+=4.5
zz+=f' L{rx_},{y+2} L{rx_},{BOT}'; p(zz,1.5)
t(rx_-10,TY+14,'R1',10,'bold','end'); t(rx_-10,TY+27,'10 k',9,anchor='end')
t(408,352,'Polarity: C1 stripe = −   ·   D1 band = + (cathode to +4 V)   ·   tablet charge port is NOT used for power',10,anchor='middle',st='fill="#222"')
l(40,368,776,368,1)

# ---------- parts list
y=392; t(40,y,'Parts list',15,'bold')
rows=[('Ref','Part','Spec / notes','Qty'),
('J1+U1','USB-C PD trigger board','CH224K or IP2721, USB-C socket on board, jumper set to 9 V (2S: 15 V)','1'),
('F1','PTC resettable fuse','2 A hold / 4 A trip, ≥ 16 V (e.g. Bourns MF-R200)','1'),
('U2','Adjustable buck module','≥ 3 A continuous, e.g. XL4015 (5 A) or LM2596 + heatsink; trimmer pot','1'),
('C1','Electrolytic capacitor','2200 µF 10 V low-ESR (2S: 16 V); soaks up boot / Wi-Fi current spikes','1'),
('D1','Zener diode','1N5338B 5.1 V 5 W (2S: 1N5346B 9.1 V). Idle normally; if U2 fails it clamps and trips F1','1'),
('R1','Resistor ¼ W','10 kΩ — fakes the battery thermistor (≈ 25 °C). Skip if reusing the pack board','1'),
('J2','Battery connection','Best: the old pack’s small circuit board with the cells removed (keeps T / ID / fuel-gauge)','1'),
('—','Wire + charger','20 AWG silicone red/black, ≤ 20 cm to tablet; heat-shrink; USB-C PD charger ≥ 20 W','—')]
cols=[40,92,250,760]; y+=10
for i,row in enumerate(rows):
    y+=20 if i else 16
    if i==0:
        o.append(f'<rect x="40" y="{y-13}" width="736" height="19" fill="#e6e6e6"/>')
    for j,c in enumerate(row):
        t(cols[j]+4 if j<3 else 770,y,c,9.6 if i else 10,'bold' if (i==0 or j==0) else 'normal','end' if j==3 else 'start')
    l(40,y+6,776,y+6,0.5)

# ---------- fits which tablets
y+=34; t(40,y,'Does it fit my tablet?',15,'bold'); y2=y
fit=[('YES','Any phone/tablet with a single-cell Li-ion/Li-Po pack (label 3.7 / 3.8 / 3.85 V). Its own port — micro-USB, USB-C, barrel — doesn’t matter.'),
('2S','7.4 / 7.6 V packs (many Windows / Atom tablets): U1 = 15 V, U2 = 8.00 V, D1 = 1N5346B 9.1 V, C1 16 V. Never feed a 1S tablet 8 V.'),
('SMART','Pack has a chip board (SMBus / HDQ fuel gauge, 4+ pins): reuse that board or the tablet may refuse to boot / show a battery error.'),
('NO','Packs with more cells in series than 2, or anything you can’t identify.')]
for k,(tag,txt) in enumerate(fit):
    yy=y+20+k*16
    if tag: t(40,yy,tag,9.6,'bold')
    t(96,yy,txt,9.8)
# ---------- build & test order (right column)
y=y+20+len(fit)*16+26; bx=40; 
steps=['Plug charger into U1 → meter shows 9.0 V on U1 OUT.',
'U2 output unloaded: turn trimmer to exactly 4.00 V (2S: 8.00).',
'Fit C1 and D1 (check polarity), F1 on the 9 V line. Re-check 4.00 V.',
'Meter the old pack’s T to − resistance; fit R1 to match (or keep pack board).',
'Charger unplugged: wire J2, meter polarity AT the tablet connector.',
'Power up and boot. Under load it must stay ≥ 3.9 V; U2 warm, not hot.',
'Tablet stays off after a power cut? Press power. (Auto-on add-on = rev B.)']
t(bx,y,'Build & test order',15,'bold')
for k,s in enumerate(steps):
    yy=y+20+k*16; t(bx,yy,f'{k+1}.',9.8,'bold'); t(bx+18,yy,s,9.8)
y+=20+len(steps)*16+18
l(40,y,776,y,1)
t(40,y+20,'Safety: remove swollen/damaged packs outdoors, cells punctured = fire; tape cell tabs before cutting. Never connect the tablet charge port while',9.2)
t(40,y+34,'the eliminator is wired unless only briefly for USB data (the tablet’s charger pushes into U2). Set voltage BEFORE connecting a tablet.',9.2)
t(776,1030,'hardware/battery-eliminator/eliminator.py',8,anchor='end',st='fill="#777"')

svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="#fff"/>'+''.join(o)+'</svg>'
open('eliminator.svg','w').write(svg)
