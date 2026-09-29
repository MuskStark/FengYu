#!/bin/zsh
# Procedural audio: minimal warm tech underscore + subtle UI SFX (fully synthetic, no licensing).
set -e
cd "$(dirname "$0")"
mkdir -p assets/bgm assets/sfx

# ── BGM bed: A-add9 pad (slow independent LFOs) + 95BPM sub pulse + pink-noise air ──
ffmpeg -y -v error -filter_complex "\
aevalsrc='0.16*sin(2*PI*110*t)*(0.72+0.28*sin(2*PI*0.050*t))+0.13*sin(2*PI*164.81*t+1.3)*(0.60+0.40*sin(2*PI*0.043*t+2.1))+0.12*sin(2*PI*220*t+2.1)*(0.65+0.35*sin(2*PI*0.057*t+4.2))+0.09*sin(2*PI*277.18*t+0.7)*(0.55+0.45*sin(2*PI*0.037*t+1.0))+0.11*sin(2*PI*329.63*t+3.0)*(0.50+0.50*sin(2*PI*0.047*t+3.3))|0.16*sin(2*PI*110.18*t)*(0.72+0.28*sin(2*PI*0.050*t+0.9))+0.13*sin(2*PI*164.93*t+2.2)*(0.60+0.40*sin(2*PI*0.043*t+1.2))+0.12*sin(2*PI*220.2*t+1.1)*(0.65+0.35*sin(2*PI*0.057*t+2.8))+0.09*sin(2*PI*277.3*t+2.9)*(0.55+0.45*sin(2*PI*0.037*t+3.6))+0.11*sin(2*PI*329.75*t+0.4)*(0.50+0.50*sin(2*PI*0.047*t+0.5))':s=44100:d=70[n.pad];\
aevalsrc='0.17*sin(2*PI*55*t)*(0.35+0.65*pow(0.5+0.5*sin(2*PI*95/60*t-PI/2),2))':s=44100:d=70[n.pulse];\
anoisesrc=color=pink:amplitude=0.028:duration=70[n.nz];\
[n.nz]lowpass=f=420,volume='0.55+0.45*sin(2*PI*0.031*t)':eval=frame[n.air];\
[n.pad][n.pulse][n.air]amix=inputs=3:duration=first:normalize=0,highpass=f=35,lowpass=f=3800,aecho=0.7:0.35:90:0.22,afade=t=in:d=2.2,afade=t=out:st=64.6:d=4.2,alimiter=limit=0.85" \
  -c:a pcm_s16le assets/bgm/track.wav
echo "bgm track.wav: $(ffprobe -v quiet -show_entries format=duration -of csv=p=0 assets/bgm/track.wav)s"

# ── SFX (short, subtle) ──
# submit thunk — deep rounded confirmation
ffmpeg -y -v error -f lavfi -i "aevalsrc=0.9*sin(2*PI*82*t)*exp(-18*t)+0.35*sin(2*PI*164*t+0.5)*exp(-30*t)+0.12*(random(0)*2-1)*exp(-90*t):s=44100:d=0.5" -af "lowpass=f=2400" -c:a pcm_s16le assets/sfx/submit-thunk.wav
# step tick — soft UI blip
ffmpeg -y -v error -f lavfi -i "aevalsrc=0.5*sin(2*PI*1250*t)*exp(-55*t):s=44100:d=0.18" -c:a pcm_s16le assets/sfx/step-tick.wav
# approval click — tactile tick + soft body
ffmpeg -y -v error -f lavfi -i "aevalsrc=0.55*(random(0)*2-1)*exp(-140*t)+0.3*sin(2*PI*440*t)*exp(-45*t):s=44100:d=0.22" -af "highpass=f=800,lowpass=f=4500" -c:a pcm_s16le assets/sfx/approval-click.wav
# stat land — rising soft sweep
ffmpeg -y -v error -f lavfi -i "aevalsrc=0.4*sin(2*PI*(300*t+1200*t*t))*exp(-5.5*t)+0.2*sin(2*PI*660*t)*exp(-4*t):s=44100:d=0.6" -c:a pcm_s16le assets/sfx/stat-land.wav
# brand sting — two-note A/E bell
ffmpeg -y -v error -f lavfi -i "aevalsrc=0.34*sin(2*PI*880*t)*exp(-7*t)+0.26*sin(2*PI*1318.5*t)*if(gt(t,0.14),exp(-7*(t-0.14)),0)+0.1*sin(2*PI*1760*t)*if(gt(t,0.14),exp(-9*(t-0.14)),0):s=44100:d=1.4" -c:a pcm_s16le assets/sfx/brand-sting.wav
ls -la assets/sfx/