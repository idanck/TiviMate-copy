#!/usr/bin/env bash
# Generates the test media the mock provider serves: three visually distinct moving test
# patterns (so a zap is visible in a screenshot and a frozen picture is detectable), each cut
# into 2-second HLS segments, plus one short MP4 for VOD and series episodes.
#
# Usage: make_media.sh <output-dir>
set -euo pipefail

out="${1:?usage: make_media.sh <output-dir>}"
mkdir -p "$out"

duration=300   # seconds of material per channel; the server loops it as a live window
common=(-hide_banner -loglevel error -y
        -c:v libx264 -preset ultrafast -profile:v baseline -pix_fmt yuv420p
        -r 25 -g 50 -keyint_min 50 -sc_threshold 0 -b:v 300k
        -c:a aac -b:a 64k -ar 48000 -ac 2)

variant() {
  local name="$1" filter="$2" freq="$3"
  mkdir -p "$out/$name"
  ffmpeg -f lavfi -i "testsrc2=size=426x240:rate=25:duration=$duration,$filter" \
         -f lavfi -i "sine=frequency=$freq:sample_rate=48000:duration=$duration" \
         "${common[@]}" -shortest \
         -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod \
         -hls_segment_filename "$out/$name/seg_%04d.ts" "$out/$name/index.m3u8"
}

variant a "null"        440
variant b "hue=h=120"   660
variant c "negate"      880

ffmpeg -f lavfi -i "testsrc2=size=640x360:rate=25:duration=60" \
       -f lavfi -i "sine=frequency=520:sample_rate=48000:duration=60" \
       "${common[@]}" -shortest -movflags +faststart "$out/vod.mp4"

echo "segments per channel: $(ls "$out/a"/seg_*.ts | wc -l)"
