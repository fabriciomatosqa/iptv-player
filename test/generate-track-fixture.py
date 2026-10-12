"""Generate a tiny, original multitrack fixture for actual Media3 UI verification."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/androidTest/assets'
with tempfile.TemporaryDirectory() as temporary:
    subtitle = Path(temporary) / 'subtitle.srt'
    subtitle.write_text('1\n00:00:00,200 --> 00:00:29,900\nLegenda de teste Aurora\n', encoding='utf-8')
    subprocess.run([
        'ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
        '-stream_loop', '-1', '-i', str(assets / 'smoke.mp4'),
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
        '-f', 'lavfi', '-i', 'sine=frequency=550:sample_rate=44100', '-i', str(subtitle),
        '-map', '0:v', '-map', '1:a', '-map', '2:a', '-map', '3:s',
        '-c:v', 'copy', '-c:a', 'aac', '-b:a', '48k', '-c:s', 'mov_text', '-t', '30',
        '-metadata:s:a:0', 'language=por', '-metadata:s:a:1', 'language=eng',
        '-metadata:s:s:0', 'language=por', '-movflags', '+faststart', str(assets / 'tracks.mp4')
    ], check=True)
