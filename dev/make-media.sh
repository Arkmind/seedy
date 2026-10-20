#!/bin/sh
# Fills the test library with openly licensed and public-domain titles only, so screenshots and demos never show
# copyrighted work: the Blender Foundation's open movies (Creative Commons, with their official artwork on TMDB),
# silent films from before 1928 (public domain), and the series Pioneer One (CC BY-NC-SA) and Sherlock Holmes (1954) and
# One Step Beyond (1959) (public domain). Folders carry the TMDB id so Jellyfin matches the right title.
# Videos are short generated test clips, except Sintel, which is its official trailer (CC BY 3.0, durian.blender.org).
# Runs inside the Jellyfin image, which ships ffmpeg:
#   docker run --rm -v "$PWD/make-media.sh:/make-media.sh:ro" -v "$PWD/data/media:/media" \
#     --entrypoint /bin/sh jellyfin/jellyfin:12.2 /make-media.sh
set -e
FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
OUT=/media
mkdir -p "$OUT/Movies" "$OUT/Shows"

clip() { # $1 output, $2 seconds, $3 colour seed, $4 extra (chapters|tracks|plain)
  dst="$1"; secs="$2"; hue="$3"; kind="$4"
  [ -f "$dst" ] && return 0
  mkdir -p "$(dirname "$dst")"
  base="-f lavfi -i testsrc2=size=1280x720:rate=24,hue=h=$hue -f lavfi -i sine=frequency=$((200 + hue)):sample_rate=48000"
  if [ "$kind" = "tracks" ]; then
    cat > /tmp/ch.txt <<CH
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=20000
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=20000
END=70000
title=The Ridge
[CHAPTER]
TIMEBASE=1/1000
START=70000
END=130000
title=Whiteout
[CHAPTER]
TIMEBASE=1/1000
START=130000
END=$((secs * 1000 - 20000))
title=Relay
[CHAPTER]
TIMEBASE=1/1000
START=$((secs * 1000 - 20000))
END=$((secs * 1000))
title=Credits
CH
    printf '1\n00:00:02,000 --> 00:00:06,000\nWe won'"'"'t make the pass before dark.\n\n2\n00:00:08,000 --> 00:00:12,000\nKeep the beacon on.\n\n3\n00:00:20,000 --> 00:00:26,000\nThe radio came back for eleven minutes.\n' > /tmp/en.srt
    printf '1\n00:00:02,000 --> 00:00:06,000\nNous ne passerons pas le col avant la nuit.\n\n2\n00:00:08,000 --> 00:00:12,000\nGarde la balise allumée.\n' > /tmp/fr.srt
    $FF -nostdin -hide_banner -loglevel error $base -f lavfi -i sine=frequency=660:sample_rate=48000 -i /tmp/ch.txt -i /tmp/fr.srt \
      -map 0:v -map 1:a -map 2:a -map 4:s -map_metadata 3 -map_chapters 3 -t "$secs" \
      -c:v libx264 -preset veryfast -crf 34 -pix_fmt yuv420p -c:a aac -b:a 64k -ac 2 -c:s srt \
      -metadata:s:a:0 language=eng -metadata:s:a:0 title="English" -metadata:s:a:1 language=fra -metadata:s:a:1 title="Français" \
      -metadata:s:s:0 language=fra "$dst"
    cp /tmp/en.srt "${dst%.*}.en.sdh.srt"
  else
    $FF -nostdin -hide_banner -loglevel error $base -t "$secs" -c:v libx264 -preset veryfast -crf 36 -pix_fmt yuv420p -c:a aac -b:a 48k -ac 2 "$dst"
  fi
}

# Sintel: the official trailer (CC BY 3.0 Blender Foundation | durian.blender.org), with chapters and a French
# subtitle track added, so the player shows real footage.
real_sintel() { # $1 output
  dst="$1"
  [ -f "$dst" ] && return 0
  mkdir -p "$(dirname "$dst")"
  curl -fsSL -o /tmp/sintel.mp4 https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4
  cat > /tmp/sch.txt <<CH
;FFMETADATA1
title=Sintel
[CHAPTER]
TIMEBASE=1/1000
START=0
END=12000
title=The Search
[CHAPTER]
TIMEBASE=1/1000
START=12000
END=30000
title=Scales
[CHAPTER]
TIMEBASE=1/1000
START=30000
END=52000
title=The Dragon
CH
  printf '1\n00:00:03,000 --> 00:00:07,000\nJe cherche un bébé dragon.\n\n2\n00:00:20,000 --> 00:00:24,000\nTu ne le trouveras pas ici.\n' > /tmp/sfr.srt
  printf '1\n00:00:03,000 --> 00:00:07,000\nI am looking for a baby dragon.\n\n2\n00:00:20,000 --> 00:00:24,000\nYou will not find it here.\n' > /tmp/sen.srt
  $FF -nostdin -hide_banner -loglevel error -i /tmp/sintel.mp4 -i /tmp/sch.txt -i /tmp/sfr.srt \
    -map 0:v -map 0:a -map 2:s -map_metadata 1 -map_chapters 1 -c:v copy -c:a aac -b:a 128k -c:s srt \
    -metadata:s:a:0 language=eng -metadata:s:s:0 language=fra "$dst"
  cp /tmp/sen.srt "${dst%.*}.en.srt"
}
real_sintel "$OUT/Movies/Sintel (2010) [tmdbid-45745]/Sintel (2010).mkv"

i=0
while IFS='|' read -r m id; do
  [ -n "$m" ] || continue
  i=$((i + 17)); kind=plain; secs=40
  case "$m" in "Tears of Steel (2012)"|"Cosmos Laundromat (2015)") kind=tracks; secs=180;; esac
  clip "$OUT/Movies/$m [tmdbid-$id]/$m.mkv" $secs $((i % 360)) $kind
done <<LIST
Big Buck Bunny (2008)|10378
Elephants Dream (2006)|9761
Tears of Steel (2012)|133701
Cosmos Laundromat (2015)|358332
Spring (2019)|593048
Agent 327 Operation Barbershop (2017)|457784
Coffee Run (2020)|908389
Charge (2022)|1062079
Caminandes Llama Drama (2013)|253777
Caminandes Gran Dillama (2013)|253774
Caminandes Llamigos (2016)|406956
Wing It! (2023)|1177628
Nosferatu (1922)|653
Metropolis (1927)|19
The General (1926)|961
Sherlock Jr. (1924)|992
Safety Last! (1923)|22596
The Kid (1921)|10098
The Cabinet of Dr. Caligari (1920)|234
A Trip to the Moon (1902)|775
LIST

ep() { # $1 show folder, $2 season, $3 episode count, $4 hue
  s=$(printf '%02d' "$2")
  e=1
  while [ $e -le "$3" ]; do
    ee=$(printf '%02d' $e)
    name=$(echo "$1" | sed 's/ ([0-9]*).*$//')
    kind=plain; secs=40
    [ "$e" -le 2 ] && [ "$2" -eq 1 ] && kind=tracks && secs=180
    clip "$OUT/Shows/$1/Season $s/$name - S${s}E${ee}.mkv" $secs $(( ($4 + e * 7) % 360 )) $kind
    e=$((e + 1))
  done
}
ep "Pioneer One (2010) [tmdbid-33050]" 1 6 10
ep "Sherlock Holmes (1954) [tmdbid-6560]" 1 8 60
ep "One Step Beyond (1959) [tmdbid-10377]" 1 6 120
echo "media ready"
